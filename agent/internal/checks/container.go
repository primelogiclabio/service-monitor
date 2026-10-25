package checks

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// maxRuntimeBody caps what is read from the runtime. An inspect answer carries the container's
// environment and labels, which can be large.
const maxRuntimeBody = 4 << 20

// containerName is what Docker and Podman accept as a container name, which also covers an ID prefix.
// Keeping to it means the target can go into the request path as it is.
var containerName = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,254}$`)

// ContainerInfo describes one container found on this machine (used by "svcmon agent containers").
type ContainerInfo struct {
	Name   string
	Image  string
	State  string
	Status string
}

// containerInspect holds the few fields of the runtime's inspect answer that the check uses. The
// environment, mounts and labels are never decoded, so they cannot end up in a message.
type containerInspect struct {
	RestartCount int `json:"RestartCount"`
	Config       struct {
		Image string `json:"Image"`
	} `json:"Config"`
	State struct {
		Status     string `json:"Status"`
		ExitCode   int    `json:"ExitCode"`
		OOMKilled  bool   `json:"OOMKilled"`
		StartedAt  string `json:"StartedAt"`
		FinishedAt string `json:"FinishedAt"`
		Health     *struct {
			Status string `json:"Status"`
		} `json:"Health"`
	} `json:"State"`
}

// containerCheck asks the container runtime (Docker or Podman, over its local socket) about one
// container. The agent only ever sends GET requests, so it can read state but not change anything.
func containerCheck(ctx context.Context, c Check) Result {
	name := strings.TrimSpace(c.Target)
	if name == "" {
		return result(c, Unknown, nil, nil, "container name is empty")
	}
	if !containerName.MatchString(name) {
		return result(c, Unknown, nil, nil, "invalid container name")
	}
	socket, err := resolveSocket(cfgString(c.Config, "socket", ""))
	if err != nil {
		return result(c, Unknown, nil, nil, err.Error())
	}
	var in containerInspect
	code, err := runtimeGet(ctx, socket, "/containers/"+name+"/json", &in)
	switch {
	case err != nil:
		return result(c, Unknown, nil, nil, "cannot query container runtime: "+runtimeError(ctx, err, c.Timeout))
	case code == http.StatusNotFound:
		return result(c, Down, nil, nil, name+": container not found")
	case code != http.StatusOK:
		return result(c, Unknown, nil, nil, fmt.Sprintf("container runtime answered HTTP %d", code))
	}
	status, detail := judgeContainer(in, time.Now())
	label := name
	if in.Config.Image != "" {
		label += " (" + in.Config.Image + ")"
	}
	return result(c, status, nil, nil, label+": "+detail)
}

// judgeContainer turns the runtime's view of a container into a status and a short description.
func judgeContainer(in containerInspect, now time.Time) (status, detail string) {
	st := in.State
	restarts := fmt.Sprintf("%d restart(s)", in.RestartCount)
	health := ""
	if st.Health != nil {
		health = st.Health.Status
	}
	switch st.Status {
	case "":
		return Unknown, "the runtime did not report a state"
	case "running":
		up := "up " + sinceText(st.StartedAt, now)
		switch health {
		case "unhealthy":
			return Down, "running but unhealthy, " + up + ", " + restarts
		case "starting":
			return Warn, "running, health check still starting, " + up + ", " + restarts
		case "healthy":
			return Up, "running, healthy, " + up + ", " + restarts
		default:
			return Up, "running, " + up + ", " + restarts
		}
	case "restarting":
		return Warn, fmt.Sprintf("restarting (last exit code %d), %s", st.ExitCode, restarts)
	default:
		detail = st.Status
		if st.Status == "exited" || st.Status == "dead" {
			detail += fmt.Sprintf(" with code %d", st.ExitCode)
			if st.OOMKilled {
				detail += " (out of memory)"
			}
			if ago := sinceText(st.FinishedAt, now); ago != "" {
				detail += ", " + ago + " ago"
			}
		}
		return Down, detail
	}
}

// sinceText renders the time since an RFC 3339 timestamp such as "3d4h". The runtimes write year 1
// for "never", which gives an empty string.
func sinceText(stamp string, now time.Time) string {
	t, err := time.Parse(time.RFC3339Nano, stamp)
	if err != nil || t.Year() <= 1 {
		return ""
	}
	d := now.Sub(t)
	switch {
	case d < 0:
		return "0s"
	case d < time.Minute:
		return fmt.Sprintf("%ds", int(d.Seconds()))
	case d < time.Hour:
		return fmt.Sprintf("%dm", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%dh%dm", int(d.Hours()), int(d.Minutes())%60)
	default:
		return fmt.Sprintf("%dd%dh", int(d.Hours())/24, int(d.Hours())%24)
	}
}

// ListContainers lists the containers the runtime knows, running or not. Nothing here is monitored;
// it only helps an admin pick names.
func ListContainers(ctx context.Context, configuredSocket string) ([]ContainerInfo, error) {
	socket, err := resolveSocket(configuredSocket)
	if err != nil {
		return nil, err
	}
	var raw []struct {
		Names  []string `json:"Names"`
		Image  string   `json:"Image"`
		State  string   `json:"State"`
		Status string   `json:"Status"`
	}
	code, err := runtimeGet(ctx, socket, "/containers/json?all=1", &raw)
	if err != nil {
		return nil, errors.New("cannot query container runtime: " + runtimeError(ctx, err, 0))
	}
	if code != http.StatusOK {
		return nil, fmt.Errorf("container runtime answered HTTP %d", code)
	}
	list := make([]ContainerInfo, 0, len(raw))
	for _, r := range raw {
		list = append(list, ContainerInfo{Name: primaryName(r.Names), Image: r.Image, State: r.State, Status: r.Status})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Name < list[j].Name })
	return list, nil
}

// primaryName picks the container's own name. Docker lists link aliases of other containers too, as
// "/app/db", and writes every name with a leading slash.
func primaryName(names []string) string {
	for _, n := range names {
		if n = strings.TrimPrefix(n, "/"); n != "" && !strings.Contains(n, "/") {
			return n
		}
	}
	if len(names) > 0 {
		return strings.TrimPrefix(names[0], "/")
	}
	return ""
}

// isPipe tells a Windows named pipe from a unix socket path. People write a pipe as \\.\pipe\name,
// as //./pipe/name, or the way DOCKER_HOST does, npipe:////./pipe/name.
func isPipe(socket string) bool {
	return strings.HasPrefix(socket, `\\.\pipe\`) || strings.HasPrefix(socket, "//./pipe/") ||
		strings.HasPrefix(socket, "npipe://")
}

// pipePath turns the written forms of a named pipe into \\.\pipe\name.
func pipePath(socket string) string {
	return strings.ReplaceAll(strings.TrimPrefix(socket, "npipe://"), "/", `\`)
}

// socketExists says whether a candidate is worth trying. A socket is a file; a pipe is only found by
// connecting to it.
func socketExists(socket string) bool {
	if isPipe(socket) {
		return pipeReachable(pipePath(socket))
	}
	_, err := os.Stat(socket)
	return err == nil
}

// socketCandidates are the places a container runtime usually listens, most common first.
func socketCandidates() []string {
	var list []string
	if h := os.Getenv("DOCKER_HOST"); strings.HasPrefix(h, "unix://") {
		list = append(list, strings.TrimPrefix(h, "unix://"))
	} else if strings.HasPrefix(h, "npipe://") {
		list = append(list, h)
	}
	list = append(list, defaultPipes...)
	list = append(list, "/var/run/docker.sock", "/run/podman/podman.sock")
	if dir := os.Getenv("XDG_RUNTIME_DIR"); dir != "" {
		list = append(list, filepath.Join(dir, "podman", "podman.sock"), filepath.Join(dir, "docker.sock"))
	}
	if home, err := os.UserHomeDir(); err == nil {
		list = append(list, filepath.Join(home, ".docker", "run", "docker.sock"))
	}
	return list
}

// resolveSocket returns the socket or pipe to use: the one the check names, or else the first usual
// place that exists.
func resolveSocket(configured string) (string, error) {
	if configured != "" {
		return strings.TrimPrefix(configured, "unix://"), nil
	}
	candidates := socketCandidates()
	for _, p := range candidates {
		if socketExists(p) {
			return p, nil
		}
	}
	return "", fmt.Errorf("no container runtime socket found (looked for %s); set the check's socket",
		strings.Join(candidates, ", "))
}

// runtimeGet sends one GET to the runtime and decodes a 200 answer into out. Any other status is
// returned for the caller to judge. GET is the only method this agent code has, on purpose: the
// runtime's socket is root-equivalent, and a monitor must not be able to start or stop anything.
func runtimeGet(ctx context.Context, socket, path string, out any) (int, error) {
	client := &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			if isPipe(socket) {
				return dialPipe(ctx, pipePath(socket))
			}
			var d net.Dialer
			return d.DialContext(ctx, "unix", socket)
		},
		DisableKeepAlives: true,
	}}
	defer client.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, "http://runtime"+path, nil)
	if err != nil {
		return 0, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return resp.StatusCode, nil
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxRuntimeBody)).Decode(out); err != nil {
		return 0, fmt.Errorf("unreadable answer: %w", err)
	}
	return resp.StatusCode, nil
}

// runtimeError explains a failed call, with a hint for the usual cause on a real server.
func runtimeError(ctx context.Context, err error, timeout time.Duration) string {
	msg := "timed out"
	if timeout > 0 || ctx.Err() != context.DeadlineExceeded {
		msg = describe(ctx, err, timeout)
	}
	if errors.Is(err, fs.ErrPermission) {
		msg += " (the agent needs access to the runtime: run it as root or an administrator, or add its user to the runtime's group)"
	}
	return msg
}
