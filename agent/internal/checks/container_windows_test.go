package checks

import (
	"fmt"
	"net/http"
	"os"
	"strings"
	"testing"

	"github.com/Microsoft/go-winio"
)

// Docker Desktop on Windows listens on a named pipe, not a socket file.
func TestContainerOverANamedPipe(t *testing.T) {
	pipe := fmt.Sprintf(`\\.\pipe\svcmon-test-%d`, os.Getpid())
	l, err := winio.ListenPipe(pipe, nil)
	if err != nil {
		t.Skipf("named pipes are not available here: %v", err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "read only", http.StatusMethodNotAllowed)
			return
		}
		switch r.URL.Path {
		case "/containers/web/json":
			_, _ = w.Write([]byte(inspectJSON(`{"Status":"running","Health":{"Status":"healthy"}}`)))
		case "/containers/json":
			_, _ = w.Write([]byte(`[{"Names":["/web"],"Image":"nginx:1.27","State":"running","Status":"Up 1 hour"}]`))
		default:
			http.NotFound(w, r)
		}
	})}
	go func() { _ = srv.Serve(l) }()
	t.Cleanup(func() { _ = srv.Close() })

	// Each way of writing the pipe reaches it.
	for _, written := range []string{pipe, strings.ReplaceAll(pipe, `\`, "/"), "npipe://" + strings.ReplaceAll(pipe, `\`, "/")} {
		r := run(t, Check{Type: "CONTAINER", Target: "web", Config: map[string]any{"socket": written}})
		if r.Status != Up || !strings.Contains(r.Message, "running, healthy") {
			t.Fatalf("socket %q: %+v", written, r)
		}
	}
	if r := run(t, Check{Type: "CONTAINER", Target: "gone", Config: map[string]any{"socket": pipe}}); r.Status != Down {
		t.Fatalf("missing container over the pipe: %+v", r)
	}

	// Discovery finds a pipe given in DOCKER_HOST.
	t.Setenv("DOCKER_HOST", "npipe://"+strings.ReplaceAll(pipe, `\`, "/"))
	if got, err := resolveSocket(""); err != nil || !isPipe(got) {
		t.Fatalf("resolveSocket = %q, %v", got, err)
	}
	r := run(t, Check{Type: "CONTAINER", Target: "web"})
	if r.Status != Up {
		t.Fatalf("discovered pipe: %+v", r)
	}
}
