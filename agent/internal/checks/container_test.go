package checks

import (
	"context"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeRuntime serves canned container answers on a unix socket, the way Docker and Podman do. The
// socket sits in a short temp folder because unix socket paths are limited to about 100 characters.
type fakeRuntime struct {
	socket string

	mu      sync.Mutex
	methods []string
}

func newFakeRuntime(t *testing.T, bodies map[string]string) *fakeRuntime {
	t.Helper()
	dir, err := os.MkdirTemp("", "sm")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	f := &fakeRuntime{socket: filepath.Join(dir, "r.sock")}
	l, err := net.Listen("unix", f.socket)
	if err != nil {
		t.Skipf("unix sockets are not available here: %v", err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.methods = append(f.methods, r.Method)
		f.mu.Unlock()
		body, ok := bodies[r.URL.Path]
		switch {
		case !ok:
			http.Error(w, `{"message":"No such container"}`, http.StatusNotFound)
		case body == "boom":
			http.Error(w, `{"message":"boom"}`, http.StatusInternalServerError)
		default:
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(body))
		}
	})}
	go func() { _ = srv.Serve(l) }()
	t.Cleanup(func() { _ = srv.Close() })
	return f
}

func (f *fakeRuntime) requests() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.methods...)
}

// inspectJSON builds an inspect answer. The environment holds a secret that must never be reported.
func inspectJSON(state string) string {
	return `{"Id":"abc","Name":"/web","RestartCount":2,` +
		`"Config":{"Image":"nginx:1.27","Env":["DB_PASSWORD=hunter2"]},"State":` + state + `}`
}

func TestContainerStates(t *testing.T) {
	started := time.Now().Add(-90 * time.Minute).UTC().Format(time.RFC3339Nano)
	finished := time.Now().Add(-5 * time.Minute).UTC().Format(time.RFC3339Nano)
	cases := []struct {
		name  string
		state string
		want  string
		text  string
	}{
		{"running without a health check", `{"Status":"running","StartedAt":"` + started + `"}`, Up, "running, up 1h30m"},
		{"healthy", `{"Status":"running","StartedAt":"` + started + `","Health":{"Status":"healthy"}}`, Up, "running, healthy"},
		{"health check without a result yet", `{"Status":"running","StartedAt":"` + started + `","Health":{"Status":"starting"}}`, Warn, "health check still starting"},
		{"unhealthy", `{"Status":"running","StartedAt":"` + started + `","Health":{"Status":"unhealthy"}}`, Down, "running but unhealthy"},
		{"podman without a health check", `{"Status":"running","StartedAt":"` + started + `","Health":{"Status":""}}`, Up, "running, up"},
		{"restart loop", `{"Status":"restarting","ExitCode":1}`, Warn, "restarting (last exit code 1)"},
		{"paused", `{"Status":"paused"}`, Down, "paused"},
		{"exited", `{"Status":"exited","ExitCode":137,"OOMKilled":true,"FinishedAt":"` + finished + `"}`, Down, "exited with code 137 (out of memory), 5m ago"},
		{"never started", `{"Status":"created","FinishedAt":"0001-01-01T00:00:00Z"}`, Down, "created"},
		{"no state", `{}`, Unknown, "did not report a state"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rt := newFakeRuntime(t, map[string]string{"/containers/web/json": inspectJSON(tc.state)})
			r := run(t, Check{ID: "c1", Type: "CONTAINER", Target: "web", Config: map[string]any{"socket": rt.socket}})
			if r.Status != tc.want {
				t.Fatalf("status = %s (%s), want %s", r.Status, r.Message, tc.want)
			}
			if !strings.Contains(r.Message, tc.text) || !strings.HasPrefix(r.Message, "web (nginx:1.27): ") {
				t.Fatalf("message = %q, want it to hold %q", r.Message, tc.text)
			}
			if strings.Contains(r.Message, "hunter2") || strings.Contains(r.Message, "DB_PASSWORD") {
				t.Fatalf("the container's environment leaked into the message: %q", r.Message)
			}
		})
	}
}

func TestContainerMissingAndRuntimeFailures(t *testing.T) {
	rt := newFakeRuntime(t, map[string]string{"/containers/broken/json": "boom"})
	cfg := map[string]any{"socket": rt.socket}

	if r := run(t, Check{Type: "CONTAINER", Target: "gone", Config: cfg}); r.Status != Down || !strings.Contains(r.Message, "not found") {
		t.Fatalf("missing container: %+v", r)
	}
	if r := run(t, Check{Type: "CONTAINER", Target: "broken", Config: cfg}); r.Status != Unknown || !strings.Contains(r.Message, "HTTP 500") {
		t.Fatalf("runtime error: %+v", r)
	}
	// A runtime that is not there is a measurement that could not be taken, not a down container.
	gone := map[string]any{"socket": filepath.Join(filepath.Dir(rt.socket), "none.sock")}
	if r := run(t, Check{Type: "CONTAINER", Target: "web", Config: gone}); r.Status != Unknown || !strings.Contains(r.Message, "cannot query container runtime") {
		t.Fatalf("no runtime: %+v", r)
	}
}

func TestContainerRejectsOddNamesWithoutAskingTheRuntime(t *testing.T) {
	rt := newFakeRuntime(t, nil)
	for _, bad := range []string{"", "  ", "../etc", "a b", "web/json?x=1", "-x", "web\nx", strings.Repeat("a", 256)} {
		r := run(t, Check{Type: "CONTAINER", Target: bad, Config: map[string]any{"socket": rt.socket}})
		if r.Status != Unknown {
			t.Fatalf("target %q accepted: %+v", bad, r)
		}
	}
	if got := rt.requests(); len(got) != 0 {
		t.Fatalf("the runtime was asked %v for a bad name", got)
	}
}

func TestContainerCheckOnlyReads(t *testing.T) {
	rt := newFakeRuntime(t, map[string]string{
		"/containers/web/json": inspectJSON(`{"Status":"running"}`),
		"/containers/json":     `[]`,
	})
	run(t, Check{Type: "CONTAINER", Target: "web", Config: map[string]any{"socket": rt.socket}})
	if _, err := ListContainers(context.Background(), rt.socket); err != nil {
		t.Fatal(err)
	}
	got := rt.requests()
	if len(got) != 2 {
		t.Fatalf("requests = %v, want 2", got)
	}
	for _, m := range got {
		if m != http.MethodGet {
			t.Fatalf("the agent sent %s to the container runtime; it may only read", m)
		}
	}
}

func TestListContainers(t *testing.T) {
	rt := newFakeRuntime(t, map[string]string{"/containers/json": `[
		{"Names":["/web","/proxy/web"],"Image":"nginx:1.27","State":"running","Status":"Up 3 days (healthy)"},
		{"Names":["/proxy/db","/db"],"Image":"postgres:17","State":"exited","Status":"Exited (0) 2 hours ago"},
		{"Names":["/app/cache"],"Image":"redis:8","State":"running","Status":"Up 1 hour"}]`})
	list, err := ListContainers(context.Background(), rt.socket)
	if err != nil {
		t.Fatal(err)
	}
	var got []string
	for _, c := range list {
		got = append(got, c.Name+"|"+c.Image+"|"+c.State)
	}
	want := "app/cache|redis:8|running,db|postgres:17|exited,web|nginx:1.27|running"
	if strings.Join(got, ",") != want {
		t.Fatalf("containers = %v, want %s", got, want)
	}
}

func TestResolveSocket(t *testing.T) {
	if got, err := resolveSocket("unix:///x/y.sock"); err != nil || got != "/x/y.sock" {
		t.Fatalf("configured socket = %q, %v", got, err)
	}
	dir := t.TempDir()
	sock := filepath.Join(dir, "d.sock")
	if err := os.WriteFile(sock, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("DOCKER_HOST", "unix://"+sock)
	if got, err := resolveSocket(""); err != nil || got != sock {
		t.Fatalf("DOCKER_HOST socket = %q, %v", got, err)
	}
	// Nothing found: the error names where it looked, so the admin knows what to set.
	t.Setenv("DOCKER_HOST", "tcp://elsewhere:2375")
	t.Setenv("XDG_RUNTIME_DIR", dir)
	t.Setenv("HOME", dir)
	t.Setenv("USERPROFILE", dir)
	if _, err := os.Stat("/var/run/docker.sock"); err != nil {
		if _, err := os.Stat("/run/podman/podman.sock"); err != nil {
			if _, err := resolveSocket(""); err == nil || !strings.Contains(err.Error(), "set the check's socket") {
				t.Fatalf("no socket: %v", err)
			}
		}
	}
}

func TestSinceText(t *testing.T) {
	now := time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)
	cases := map[string]string{
		"2026-10-25T11:59:30Z":     "30s",
		"2026-10-25T11:55:00Z":     "5m",
		"2026-10-25T09:30:00Z":     "2h30m",
		"2026-10-21T08:00:00Z":     "4d4h",
		"2026-10-25T12:00:05Z":     "0s",
		"0001-01-01T00:00:00Z":     "",
		"not a time":               "",
		"2026-10-25T11:00:00.123Z": "59m",
	}
	for in, want := range cases {
		if got := sinceText(in, now); got != want {
			t.Errorf("sinceText(%q) = %q, want %q", in, got, want)
		}
	}
}
