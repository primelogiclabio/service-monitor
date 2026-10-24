package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"svcmon/internal/agent"
	"svcmon/internal/hubapi"
)

// registerHub accepts one enrollment token and answers like the hub's /agent/register.
type registerHub struct {
	mu       sync.Mutex
	tokens   []string // Authorization headers seen
	accepted string   // the one enrollment token that works
}

func (h *registerHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h.mu.Lock()
	h.tokens = append(h.tokens, r.Header.Get("Authorization"))
	h.mu.Unlock()
	if r.URL.Path != "/api/v1/agent/register" || r.Header.Get("Authorization") != "Bearer "+h.accepted {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(hubapi.RegisterResponse{ServerID: "server-new-0001", Token: "svm_g_new-agent-token", HeartbeatSeconds: 30})
}

func (h *registerHub) calls() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.tokens)
}

func enrolledConfig(t *testing.T, hub string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.json")
	old := &agent.FileConfig{Hub: hub, Name: "web01", ServerID: "server-old-0001", Token: "svm_g_old-agent-token"}
	if err := old.Save(path); err != nil {
		t.Fatal(err)
	}
	return path
}

func newEnv() (*env, *bytes.Buffer) {
	var out bytes.Buffer
	return &env{out: &out, err: &out}, &out
}

func TestInstallKeepsAnExistingEnrollmentWhenNoTokenIsGiven(t *testing.T) {
	hub := &registerHub{accepted: "enroll-token"}
	srv := httptest.NewServer(hub)
	defer srv.Close()
	path := enrolledConfig(t, srv.URL)

	e, out := newEnv()
	cfg, err := e.ensureEnrolled("", "", "", "", path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ServerID != "server-old-0001" || hub.calls() != 0 {
		t.Fatalf("expected the old enrollment and no hub call, got %+v after %d calls", cfg, hub.calls())
	}
	if !strings.Contains(out.String(), "Already enrolled") {
		t.Fatalf("output: %q", out.String())
	}
}

func TestInstallWithATokenEnrollsAgainOverTheOldConfig(t *testing.T) {
	hub := &registerHub{accepted: "enroll-token"}
	srv := httptest.NewServer(hub)
	defer srv.Close()
	path := enrolledConfig(t, srv.URL)

	e, out := newEnv()
	// No --hub: the hub in the old config is used.
	cfg, err := e.ensureEnrolled("", "enroll-token", "web01", "", path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.ServerID != "server-new-0001" {
		t.Fatalf("got %+v", cfg)
	}
	saved, err := agent.LoadConfig(path)
	if err != nil || saved.ServerID != "server-new-0001" || saved.Token != "svm_g_new-agent-token" {
		t.Fatalf("the config on disk was not replaced: %+v, %v", saved, err)
	}
	if !strings.Contains(out.String(), "Replacing the enrollment") {
		t.Fatalf("output: %q", out.String())
	}
}

func TestAFailedEnrollmentLeavesTheOldConfigAlone(t *testing.T) {
	hub := &registerHub{accepted: "enroll-token"}
	srv := httptest.NewServer(hub)
	defer srv.Close()
	path := enrolledConfig(t, srv.URL)

	e, _ := newEnv()
	if _, err := e.ensureEnrolled("", "already-used-token", "web01", "", path); err == nil {
		t.Fatal("expected the hub to refuse the token")
	}
	saved, err := agent.LoadConfig(path)
	if err != nil || saved.ServerID != "server-old-0001" {
		t.Fatalf("the old config should be untouched: %+v, %v", saved, err)
	}
}

func TestInstallNeedsHubAndTokenWhenNotEnrolled(t *testing.T) {
	path := filepath.Join(t.TempDir(), "agent.json")
	for _, c := range []struct{ hub, token string }{{"", ""}, {"http://hub", ""}, {"", "enroll-token"}} {
		e, _ := newEnv()
		_, err := e.ensureEnrolled(c.hub, c.token, "", "", path)
		if err == nil || !strings.Contains(err.Error(), "not enrolled yet") {
			t.Fatalf("hub=%q token=%q: got %v", c.hub, c.token, err)
		}
	}
}
