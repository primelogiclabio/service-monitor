package checks

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"testing"
	"time"
)

func run(t *testing.T, c Check) Result {
	t.Helper()
	if c.Timeout == 0 {
		c.Timeout = 3 * time.Second
	}
	return Run(context.Background(), c)
}

func TestHTTPStatusAndKeyword(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ok":
			_, _ = w.Write([]byte("hello world"))
		case "/boom":
			w.WriteHeader(500)
		case "/redir":
			http.Redirect(w, r, "/ok", http.StatusFound)
		}
	}))
	defer srv.Close()

	cases := []struct {
		name   string
		path   string
		config map[string]any
		want   string
	}{
		{"ok", "/ok", nil, Up},
		{"server error", "/boom", nil, Down},
		{"server error expected", "/boom", map[string]any{"expectStatus": "500"}, Up},
		{"keyword present", "/ok", map[string]any{"keyword": "world"}, Up},
		{"keyword absent", "/ok", map[string]any{"keyword": "absent"}, Down},
		{"redirect followed", "/redir", nil, Up},
		{"redirect not followed", "/redir", map[string]any{"followRedirects": false}, Down},
		{"redirect accepted", "/redir", map[string]any{"followRedirects": false, "expectStatus": "302"}, Up},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := run(t, Check{ID: "x", Type: "HTTP", Target: srv.URL + tc.path, Config: tc.config})
			if r.Status != tc.want {
				t.Fatalf("status = %s (%s), want %s", r.Status, r.Message, tc.want)
			}
			if r.CheckID != "x" || r.Ts == 0 {
				t.Fatalf("result not stamped: %+v", r)
			}
		})
	}
}

func TestHTTPRejectsOtherSchemesAndTimesOut(t *testing.T) {
	if r := run(t, Check{Type: "HTTP", Target: "file:///etc/passwd"}); r.Status != Down {
		t.Fatalf("file URL accepted: %+v", r)
	}
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(1500 * time.Millisecond)
	}))
	defer slow.Close()
	r := run(t, Check{Type: "HTTP", Target: slow.URL, Timeout: 300 * time.Millisecond})
	if r.Status != Down || !strings.Contains(r.Message, "timed out") {
		t.Fatalf("slow server: %+v", r)
	}
}

func TestTCP(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := l.Addr().String()
	if r := run(t, Check{Type: "TCP", Target: addr}); r.Status != Up || r.LatencyMs == nil {
		t.Fatalf("open port: %+v", r)
	}
	l.Close()
	if r := run(t, Check{Type: "TCP", Target: addr}); r.Status != Down {
		t.Fatalf("closed port: %+v", r)
	}
	for _, bad := range []string{"nonsense", ":80", "host:0", "host:99999"} {
		if r := run(t, Check{Type: "TCP", Target: bad}); r.Status != Down {
			t.Fatalf("target %q accepted: %+v", bad, r)
		}
	}
}

func TestPingValidation(t *testing.T) {
	for _, bad := range []string{"-f", "a b", "host; ls", ""} {
		if r := run(t, Check{Type: "PING", Target: bad}); r.Status != Down || r.Message != "invalid host" {
			t.Fatalf("host %q: %+v", bad, r)
		}
	}
}

func TestPingLoopback(t *testing.T) {
	r := run(t, Check{Type: "PING", Target: "127.0.0.1", Timeout: 3 * time.Second})
	if r.Status == Unknown {
		t.Skipf("ICMP not permitted here: %s", r.Message)
	}
	if r.Status != Up || r.LatencyMs == nil {
		t.Fatalf("loopback ping: %+v", r)
	}
}

func TestHostMetricsAreReadings(t *testing.T) {
	cpu := run(t, Check{Type: "CPU", Timeout: 3 * time.Second})
	mem := run(t, Check{Type: "MEMORY"})
	root := "/"
	if runtime.GOOS == "windows" {
		root = `C:`
	}
	dsk := run(t, Check{Type: "DISK", Target: root})
	for name, r := range map[string]Result{"cpu": cpu, "memory": mem, "disk": dsk} {
		if r.Status == Unknown || r.Value == nil || *r.Value < 0 || *r.Value > 100 {
			t.Fatalf("%s: %+v", name, r)
		}
	}
}

func TestThresholds(t *testing.T) {
	if got := level(50, 80, 90); got != Up {
		t.Fatal(got)
	}
	if got := level(80, 80, 90); got != Warn {
		t.Fatal(got)
	}
	if got := level(95, 80, 90); got != Down {
		t.Fatal(got)
	}
	// A tiny crit threshold makes any reading critical.
	r := run(t, Check{Type: "MEMORY", Config: map[string]any{"warn": 0.0, "crit": 0.0}})
	if r.Status != Down {
		t.Fatalf("crit=0 should be DOWN: %+v", r)
	}
}

func TestServiceCheck(t *testing.T) {
	name := "definitely-not-a-real-service-xyz"
	r := run(t, Check{Type: "SERVICE", Target: name})
	switch r.Status {
	case Down:
		// reported as not installed
	case Unknown:
		t.Skipf("no service manager available: %s", r.Message)
	default:
		t.Fatalf("missing service reported %s: %+v", r.Status, r)
	}
	if runtime.GOOS == "windows" {
		// The Windows Event Log service is present and running on every supported edition.
		if r := run(t, Check{Type: "SERVICE", Target: "EventLog"}); r.Status != Up {
			t.Fatalf("EventLog: %+v", r)
		}
	}
	if r := run(t, Check{Type: "SERVICE", Target: ""}); r.Status != Unknown {
		t.Fatalf("empty name: %+v", r)
	}
}

func TestUnknownTypeIsUnknown(t *testing.T) {
	if r := run(t, Check{Type: "TELEPORT"}); r.Status != Unknown {
		t.Fatalf("%+v", r)
	}
}

func TestStatusSpec(t *testing.T) {
	for spec, code := range map[string]int{"200-299": 204, "200, 301-302,404": 302, "200,404": 404} {
		if !statusMatches(spec, code) {
			t.Fatalf("%q should match %d", spec, code)
		}
	}
	for spec, code := range map[string]int{"200-299": 301, "abc": 200} {
		if statusMatches(spec, code) {
			t.Fatalf("%q should not match %d", spec, code)
		}
	}
}
