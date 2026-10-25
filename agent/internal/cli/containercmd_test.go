package cli

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCheckAddContainerSendsTheSocket(t *testing.T) {
	var posted map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/api/v1/servers":
			_, _ = w.Write([]byte(`[{"id":"server-0001-abcd","name":"web01"}]`))
		case r.Method == http.MethodPost && r.URL.Path == "/api/v1/checks":
			_ = json.NewDecoder(r.Body).Decode(&posted)
			w.WriteHeader(http.StatusCreated)
			_, _ = w.Write([]byte(`{"id":"check-0001-abcd","name":"container web","type":"CONTAINER","runFrom":"AGENT"}`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	var out, errOut bytes.Buffer
	code := Run([]string{"--hub", srv.URL, "--token", "tok", "check", "add", "container", "web",
		"--server", "web01", "--socket", "/run/podman/podman.sock"}, &out, &errOut)
	if code != 0 {
		t.Fatalf("exit %d: %s%s", code, out.String(), errOut.String())
	}
	if posted["type"] != "CONTAINER" || posted["target"] != "web" || posted["serverId"] != "server-0001-abcd" {
		t.Fatalf("body: %v", posted)
	}
	if cfg, _ := posted["config"].(map[string]any); cfg["socket"] != "/run/podman/podman.sock" {
		t.Fatalf("config: %v", posted["config"])
	}
}

func TestAgentContainersListsAndFilters(t *testing.T) {
	dir, err := os.MkdirTemp("", "sm")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	sock := filepath.Join(dir, "r.sock")
	l, err := net.Listen("unix", sock)
	if err != nil {
		t.Skipf("unix sockets are not available here: %v", err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/containers/json" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_, _ = w.Write([]byte(`[
			{"Names":["/web"],"Image":"nginx:1.27","State":"running","Status":"Up 3 days (healthy)"},
			{"Names":["/db"],"Image":"postgres:17","State":"exited","Status":"Exited (0) 2 hours ago"}]`))
	})}
	go func() { _ = srv.Serve(l) }()
	t.Cleanup(func() { _ = srv.Close() })

	var out, errOut bytes.Buffer
	if code := Run([]string{"agent", "containers", "--socket", sock, "--filter", "POSTGRES"}, &out, &errOut); code != 0 {
		t.Fatalf("exit %d: %s%s", code, out.String(), errOut.String())
	}
	if !strings.Contains(out.String(), "db") || strings.Contains(out.String(), "web") {
		t.Fatalf("filter on the image did not work:\n%s", out.String())
	}
	if !strings.Contains(errOut.String(), "Nothing here is monitored") {
		t.Fatalf("the reminder that nothing is monitored is missing: %q", errOut.String())
	}

	out.Reset()
	if code := Run([]string{"agent", "containers", "--socket", filepath.Join(dir, "none.sock")}, &out, &errOut); code == 0 {
		t.Fatal("a missing runtime should be an error")
	}
}
