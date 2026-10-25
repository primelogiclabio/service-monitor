package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"svcmon/internal/hubapi"
)

func writeChecks(t *testing.T, dir, content string) string {
	t.Helper()
	path := filepath.Join(dir, ChecksFileName)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestLoadChecks(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ChecksFileName)

	// No file declares nothing; it is not an error, and deleting the file must not switch checks off.
	if list, sum, err := LoadChecks(path); list != nil || sum != "" || err != nil {
		t.Fatalf("missing file: %v %q %v", list, sum, err)
	}

	writeChecks(t, dir, `{"checks":[
		{"type":"container","target":"web","intervalSeconds":20,"config":{"socket":"/run/podman/podman.sock"}},
		{"id":"disk-root","name":"Root disk","type":"DISK","target":"/","failuresBeforeDown":2}]}`)
	list, sum, err := LoadChecks(path)
	if err != nil || len(list) != 2 || sum == "" {
		t.Fatalf("valid file: %v %q %v", list, sum, err)
	}
	if list[0].Target != "web" || list[0].IntervalSeconds != 20 || list[1].ID != "disk-root" || list[1].FailuresBeforeDown != 2 {
		t.Fatalf("parsed wrongly: %+v", list)
	}
	again, sum2, _ := LoadChecks(path)
	if sum2 != sum || len(again) != 2 {
		t.Fatal("the same file should give the same checksum")
	}

	// Notepad and PowerShell save UTF-8 with a byte order mark; that must not make the file unreadable.
	writeChecks(t, dir, "\xef\xbb\xbf"+`{"checks":[{"type":"cpu"}]}`)
	if list, _, err := LoadChecks(path); err != nil || len(list) != 1 {
		t.Fatalf("a file with a byte order mark: %v %v", list, err)
	}

	// An empty list is a statement: there are none. It differs from a missing file.
	writeChecks(t, dir, `{"checks":[]}`)
	if list, sum, err := LoadChecks(path); err != nil || list == nil || len(list) != 0 || sum == "" {
		t.Fatalf("empty list: %v %q %v", list, sum, err)
	}

	for name, content := range map[string]string{
		"a misspelt setting":  `{"checks":[{"type":"CPU","intervall":5}]}`,
		"a misspelt list":     `{"check":[{"type":"CPU"}]}`,
		"no list":             `{}`,
		"an unknown type":     `{"checks":[{"type":"SQL","target":"x"}]}`,
		"a missing type":      `{"checks":[{"target":"x"}]}`,
		"text after the list": `{"checks":[]} {"checks":[]}`,
		"broken JSON":         `{"checks":[`,
	} {
		writeChecks(t, dir, content)
		if _, _, err := LoadChecks(path); err == nil {
			t.Errorf("%s was accepted", name)
		}
	}
}

// declareHub is a hub that serves an empty check list and takes declarations.
type declareHub struct {
	mu       sync.Mutex
	puts     []map[string]any
	configs  int
	status   int // the answer to a declaration; 0 means 200
	response hubapi.DeclareResponse
}

func (h *declareHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer agent-token" {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	switch {
	case r.URL.Path == "/api/v1/agent/config":
		h.configs++
		_ = json.NewEncoder(w).Encode(hubapi.AgentConfig{ServerID: "s1", HeartbeatSeconds: 1, HubTime: time.Now().UnixMilli()})
	case r.URL.Path == "/api/v1/agent/report":
		_ = json.NewEncoder(w).Encode(hubapi.ReportResponse{HubTime: time.Now().UnixMilli()})
	case r.URL.Path == "/api/v1/agent/checks" && r.Method == http.MethodPut:
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		h.puts = append(h.puts, body)
		if h.status != 0 {
			w.WriteHeader(h.status)
			_, _ = w.Write([]byte(`{"error":"forbidden","message":"Agent-declared checks are turned off on this hub"}`))
			return
		}
		_ = json.NewEncoder(w).Encode(h.response)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (h *declareHub) putCount() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.puts)
}

func declaringAgent(t *testing.T, hub *declareHub, checksPath string, logs io.Writer) *Agent {
	t.Helper()
	srv := httptest.NewServer(hub)
	t.Cleanup(srv.Close)
	a := New(&FileConfig{Hub: srv.URL, Name: "web01", ServerID: "s1", Token: "agent-token"}, log.New(logs, "", 0), "test")
	a.SetChecksFile(checksPath)
	return a
}

func TestDeclaresOnlyWhenTheFileIsNewOrChanged(t *testing.T) {
	dir := t.TempDir()
	hub := &declareHub{response: hubapi.DeclareResponse{Results: []hubapi.DeclareResult{{Result: "created"}}}}
	var logs bytes.Buffer
	a := declaringAgent(t, hub, filepath.Join(dir, ChecksFileName), &logs)
	ctx := context.Background()

	if a.syncDeclared(ctx) || hub.putCount() != 0 {
		t.Fatal("no file: nothing should be sent")
	}

	writeChecks(t, dir, `{"checks":[{"type":"container","target":"web"}]}`)
	if !a.syncDeclared(ctx) || hub.putCount() != 1 {
		t.Fatalf("a new file should be declared (puts=%d)", hub.putCount())
	}
	sent := hub.puts[0]["checks"].([]any)[0].(map[string]any)
	if sent["type"] != "CONTAINER" || sent["target"] != "web" {
		t.Fatalf("sent %v; the type should reach the hub in capitals", sent)
	}
	if a.syncDeclared(ctx) || hub.putCount() != 1 {
		t.Fatal("an unchanged file must not be sent again")
	}

	writeChecks(t, dir, `{"checks":[{"type":"container","target":"web"},{"type":"cpu"}]}`)
	if !a.syncDeclared(ctx) || hub.putCount() != 2 {
		t.Fatalf("a changed file should be declared again (puts=%d)", hub.putCount())
	}
	if !strings.Contains(logs.String(), "declared 2 check(s)") {
		t.Fatalf("log: %q", logs.String())
	}

	// A broken edit is reported and not sent; the hub keeps the last good list.
	writeChecks(t, dir, `{"checks":[{"type":"cpu","intervall":5}]}`)
	if a.syncDeclared(ctx) || hub.putCount() != 2 {
		t.Fatal("a broken file must not be sent")
	}
	if !strings.Contains(logs.String(), "checks file not used") {
		t.Fatalf("the problem should be logged: %q", logs.String())
	}
}

func TestAHubThatRefusesIsRetriedAndLoggedOnce(t *testing.T) {
	dir := t.TempDir()
	path := writeChecks(t, dir, `{"checks":[{"type":"cpu"}]}`)
	hub := &declareHub{status: http.StatusForbidden}
	var logs bytes.Buffer
	a := declaringAgent(t, hub, path, &logs)
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		if a.syncDeclared(ctx) {
			t.Fatal("a refused declaration is not a declaration")
		}
	}
	if hub.putCount() != 3 {
		t.Fatalf("a refused file should be tried again on every poll (puts=%d)", hub.putCount())
	}
	if n := strings.Count(logs.String(), "turned off"); n != 1 {
		t.Fatalf("the same problem was logged %d times: %q", n, logs.String())
	}

	// The admin turns the setting on: the next poll takes it.
	hub.mu.Lock()
	hub.status = 0
	hub.response = hubapi.DeclareResponse{
		Results: []hubapi.DeclareResult{{Index: 0, Result: "rejected", Error: "type is required"}},
		Removed: []string{"old check"},
	}
	hub.mu.Unlock()
	if !a.syncDeclared(ctx) {
		t.Fatal("the hub took the declaration")
	}
	for _, want := range []string{"1 rejected", "the hub rejected entry 1 (cpu ): type is required", "switched off in the hub", "old check"} {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("log lacks %q: %q", want, logs.String())
		}
	}
}

func TestRunDeclaresAtStartAndThenAsksForTheListAgain(t *testing.T) {
	dir := t.TempDir()
	path := writeChecks(t, dir, `{"checks":[{"type":"cpu"}]}`)
	hub := &declareHub{}
	a := declaringAgent(t, hub, path, io.Discard)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _ = a.Run(ctx); close(done) }()
	defer func() { cancel(); <-done }()

	waitFor(t, 6*time.Second, "the declaration", func() bool { return hub.putCount() == 1 })
	// The first config fetch came before the declaration, so the agent must fetch once more to run the new checks.
	waitFor(t, 6*time.Second, "a second config fetch", func() bool {
		hub.mu.Lock()
		defer hub.mu.Unlock()
		return hub.configs >= 2
	})
}

func TestRunOnceDeclaresFirst(t *testing.T) {
	dir := t.TempDir()
	path := writeChecks(t, dir, `{"checks":[{"type":"cpu"}]}`)
	hub := &declareHub{}
	a := declaringAgent(t, hub, path, io.Discard)
	if _, err := a.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if hub.putCount() != 1 {
		t.Fatalf("run --once should declare the file before it fetches the list (puts=%d)", hub.putCount())
	}
}
