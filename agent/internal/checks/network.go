package checks

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"

	probing "github.com/prometheus-community/pro-bing"
)

// httpCheck mirrors the hub's HTTP check. Config keys: expectStatus ("200-299"), keyword, followRedirects
// (true), ignoreTls (false), certWarnDays (14), headers (object).
func httpCheck(ctx context.Context, c Check) Result {
	u, err := url.Parse(c.Target)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
		return result(c, Down, nil, nil, "target must be an http:// or https:// URL")
	}
	follow := cfgBool(c.Config, "followRedirects", true)
	ignoreTLS := cfgBool(c.Config, "ignoreTls", false)
	keyword := cfgString(c.Config, "keyword", "")

	tr := &http.Transport{
		DisableKeepAlives: true,
		TLSClientConfig:   &tls.Config{InsecureSkipVerify: ignoreTLS}, //nolint:gosec // opt-in per check
	}
	defer tr.CloseIdleConnections()
	client := &http.Client{Transport: tr}
	if !follow {
		client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.Target, nil)
	if err != nil {
		return result(c, Down, nil, nil, err.Error())
	}
	req.Header.Set("User-Agent", "svcmon-agent")
	if headers, ok := c.Config["headers"].(map[string]any); ok {
		for k, v := range headers {
			if !strings.EqualFold(k, "host") {
				req.Header.Set(k, fmt.Sprint(v))
			}
		}
	}

	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return result(c, Down, nil, nil, describe(ctx, err, c.Timeout))
	}
	defer resp.Body.Close()

	var body []byte
	if keyword != "" {
		body, _ = io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	} else {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	}
	latency := millis(time.Since(start))

	if !statusMatches(cfgString(c.Config, "expectStatus", "200-299"), resp.StatusCode) {
		return result(c, Down, latency, nil, fmt.Sprintf("HTTP %d not in expected range", resp.StatusCode))
	}
	if keyword != "" && !strings.Contains(string(body), keyword) {
		return result(c, Down, latency, nil, fmt.Sprintf("HTTP %d, keyword not found", resp.StatusCode))
	}

	status := Up
	message := fmt.Sprintf("HTTP %d", resp.StatusCode)
	if resp.TLS != nil && len(resp.TLS.PeerCertificates) > 0 {
		days := int(math.Floor(time.Until(resp.TLS.PeerCertificates[0].NotAfter).Hours() / 24))
		warnDays := int(cfgFloat(c.Config, "certWarnDays", 14))
		switch {
		case days < 0:
			status = Down
			message += fmt.Sprintf(", certificate expired %dd ago", -days)
		case days < warnDays:
			status = Warn
			message += fmt.Sprintf(", certificate expires in %dd", days)
		default:
			message += fmt.Sprintf(", certificate valid %dd", days)
		}
	}
	return result(c, status, latency, nil, message)
}

// statusMatches reports whether code is covered by a spec such as "200-299,301,404".
func statusMatches(spec string, code int) bool {
	for _, part := range strings.Split(spec, ",") {
		p := strings.TrimSpace(part)
		if p == "" {
			continue
		}
		if lo, hi, found := strings.Cut(p, "-"); found {
			l, err1 := strconv.Atoi(strings.TrimSpace(lo))
			h, err2 := strconv.Atoi(strings.TrimSpace(hi))
			if err1 == nil && err2 == nil && code >= l && code <= h {
				return true
			}
		} else if n, err := strconv.Atoi(p); err == nil && n == code {
			return true
		}
	}
	return false
}

// tcpCheck connects to "host:port" (IPv6 as "[::1]:80").
func tcpCheck(ctx context.Context, c Check) Result {
	host, port, err := net.SplitHostPort(strings.TrimSpace(c.Target))
	if err != nil || host == "" {
		return result(c, Down, nil, nil, "target must be host:port")
	}
	if p, err := strconv.Atoi(port); err != nil || p < 1 || p > 65535 {
		return result(c, Down, nil, nil, "port must be 1-65535")
	}
	start := time.Now()
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(host, port))
	if err != nil {
		return result(c, Down, nil, nil, describe(ctx, err, c.Timeout))
	}
	_ = conn.Close()
	return result(c, Up, millis(time.Since(start)), nil, "connected to "+net.JoinHostPort(host, port))
}

var hostPattern = regexp.MustCompile(`^[A-Za-z0-9]([A-Za-z0-9.:%_-]*[A-Za-z0-9])?$`)

// pingCheck sends one ICMP echo. Windows needs the "privileged" mode of the library (it still works
// without elevation); Linux tries unprivileged datagram sockets first and falls back to raw sockets.
func pingCheck(ctx context.Context, c Check) Result {
	host := strings.TrimSpace(c.Target)
	if len(host) > 253 || !hostPattern.MatchString(host) {
		return result(c, Down, nil, nil, "invalid host")
	}
	privileged := runtime.GOOS == "windows"
	rtt, replied, err := pingOnce(ctx, host, c.Timeout, privileged)
	if err != nil && runtime.GOOS == "linux" {
		rtt, replied, err = pingOnce(ctx, host, c.Timeout, !privileged)
	}
	if err != nil {
		if ctx.Err() != nil {
			return result(c, Down, nil, nil, describe(ctx, err, c.Timeout))
		}
		return result(c, Unknown, nil, nil, "cannot send ICMP: "+err.Error()+
			" (Linux: grant CAP_NET_RAW or set net.ipv4.ping_group_range)")
	}
	if !replied {
		return result(c, Down, nil, nil, "no reply from "+host)
	}
	return result(c, Up, millis(rtt), nil, "reply from "+host)
}

func pingOnce(ctx context.Context, host string, timeout time.Duration, privileged bool) (time.Duration, bool, error) {
	p, err := probing.NewPinger(host)
	if err != nil {
		return 0, false, err
	}
	p.Count = 1
	p.Timeout = timeout
	p.SetPrivileged(privileged)
	if err := p.RunWithContext(ctx); err != nil {
		return 0, false, err
	}
	st := p.Statistics()
	return st.AvgRtt, st.PacketsRecv > 0, nil
}
