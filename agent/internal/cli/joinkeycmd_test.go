package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"svcmon/internal/hubapi"
)

// joinHub is just enough of the hub's join-key API to see what the CLI sends.
type joinHub struct {
	mu       sync.Mutex
	keys     []hubapi.TokenView
	requests []recorded
}

func (h *joinHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer tok" {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	h.mu.Lock()
	h.requests = append(h.requests, recorded{r.Method, r.URL.Path, body})
	keys := append([]hubapi.TokenView(nil), h.keys...)
	h.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/api/v1/join-keys":
		_ = json.NewEncoder(w).Encode(keys)
	case r.Method == http.MethodPost && r.URL.Path == "/api/v1/join-keys":
		issued := hubapi.IssuedToken{ID: "key-1234567", Name: body["name"].(string), Scope: "JOIN", Token: "svm_j_secret"}
		if c, ok := body["cluster"].(string); ok {
			issued.Cluster = c
		}
		if _, ok := body["expiresInDays"]; ok {
			exp := int64(1_900_000_000_000)
			issued.ExpiresAt = &exp
		}
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(issued)
	case r.Method == http.MethodDelete && strings.HasPrefix(r.URL.Path, "/api/v1/join-keys/"):
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (h *joinHub) last() recorded {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.requests[len(h.requests)-1]
}

func newJoinHub(t *testing.T, keys ...hubapi.TokenView) (*joinHub, string, func(args ...string) (int, string, string)) {
	t.Helper()
	hub := &joinHub{keys: keys}
	srv := httptest.NewServer(hub)
	t.Cleanup(srv.Close)
	run := func(args ...string) (int, string, string) {
		var out, errOut bytes.Buffer
		code := Run(append([]string{"--hub", srv.URL, "--token", "tok"}, args...), &out, &errOut)
		return code, out.String(), errOut.String()
	}
	return hub, srv.URL, run
}

func TestJoinKeyCreatePrintsTheKeyAndTheInstallLine(t *testing.T) {
	hub, base, run := newJoinHub(t)
	code, out, errOut := run("join-key", "create", "--name", "app servers", "--cluster", "prod", "--expires-days", "90")
	if code != exitOK {
		t.Fatalf("exit %d: %s", code, errOut)
	}
	sent := hub.last().Body
	if sent["name"] != "app servers" || sent["cluster"] != "prod" || sent["expiresInDays"] != float64(90) {
		t.Fatalf("sent %v", sent)
	}
	for _, want := range []string{"svm_j_secret", "agent install --hub " + base + " --token svm_j_secret", `cluster "prod"`, "expires"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
}

func TestJoinKeyCreateWithoutExpirySendsNone(t *testing.T) {
	hub, _, run := newJoinHub(t)
	code, out, _ := run("join-key", "create", "--name", "fleet")
	if code != exitOK {
		t.Fatal(out)
	}
	sent := hub.last().Body
	if _, ok := sent["expiresInDays"]; ok {
		t.Fatalf("no expiry asked for, but sent %v", sent)
	}
	if _, ok := sent["cluster"]; ok {
		t.Fatalf("no cluster asked for, but sent %v", sent)
	}
	if !strings.Contains(out, "no expiry") {
		t.Fatalf("output: %s", out)
	}
}

func TestJoinKeyCreateRejectsMistakesBeforeContactingTheHub(t *testing.T) {
	hub, _, run := newJoinHub(t)
	for _, args := range [][]string{
		{"join-key", "create"},
		{"join-key", "create", "--name", "x", "--expires-days", "-1"},
	} {
		if code, _, _ := run(args...); code == exitOK {
			t.Errorf("%v should fail", args)
		}
	}
	if len(hub.requests) != 0 {
		t.Fatalf("contacted the hub: %v", hub.requests)
	}
}

func TestJoinKeyListShowsUseAndNeverTheKey(t *testing.T) {
	used := int64(1_800_000_000_000)
	_, _, run := newJoinHub(t,
		hubapi.TokenView{ID: "aaaa1111-x", Name: "fleet", Scope: "JOIN", CreatedAt: used, UsedAt: &used, UseCount: 7},
		hubapi.TokenView{ID: "bbbb2222-y", Name: "staging", Scope: "JOIN", CreatedAt: used, Cluster: "staging"})
	code, out, _ := run("join-key", "list")
	if code != exitOK {
		t.Fatal(out)
	}
	for _, want := range []string{"SERVERS", "fleet", "any", "7", "staging", "never"} {
		if !strings.Contains(out, want) {
			t.Errorf("list lacks %q:\n%s", want, out)
		}
	}
}

func TestJoinKeyRevokeByIDPrefix(t *testing.T) {
	hub, _, run := newJoinHub(t,
		hubapi.TokenView{ID: "aaaa1111-x", Name: "fleet", Scope: "JOIN"},
		hubapi.TokenView{ID: "aaaa2222-y", Name: "other", Scope: "JOIN"})
	code, out, _ := run("join-key", "revoke", "aaaa1")
	if code != exitOK {
		t.Fatal(out)
	}
	if got := hub.last(); got.Method != http.MethodDelete || got.Path != "/api/v1/join-keys/aaaa1111-x" {
		t.Fatalf("got %v", got)
	}
	if code, _, errOut := run("join-key", "revoke", "aaaa"); code == exitOK || !strings.Contains(errOut, "matches 2") {
		t.Fatalf("an ambiguous prefix should fail: %d %s", code, errOut)
	}
}
