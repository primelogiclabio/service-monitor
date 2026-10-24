package agent

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"svcmon/internal/hubapi"
)

// fakeHub records reports and serves a fixed check list, enforcing the bearer token like the real hub.
type fakeHub struct {
	t       *testing.T
	checks  []hubapi.AgentCheck
	mu      sync.Mutex
	reports []hubapi.ReportRequest
	fail    bool
}

func (f *fakeHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer agent-token" {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	switch r.URL.Path {
	case "/api/v1/agent/config":
		_ = json.NewEncoder(w).Encode(hubapi.AgentConfig{
			ServerID: "s1", ServerName: "web01", HeartbeatSeconds: 1,
			HubTime: time.Now().UnixMilli(), Checks: f.checks,
		})
	case "/api/v1/agent/report":
		if f.fail {
			w.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		var req hubapi.ReportRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			f.t.Errorf("bad report body: %v", err)
		}
		f.reports = append(f.reports, req)
		_ = json.NewEncoder(w).Encode(hubapi.ReportResponse{Accepted: len(req.Results), HubTime: time.Now().UnixMilli()})
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (f *fakeHub) resultCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, r := range f.reports {
		n += len(r.Results)
	}
	return n
}

func newAgent(t *testing.T, hub *fakeHub) *Agent {
	srv := httptest.NewServer(hub)
	t.Cleanup(srv.Close)
	cfg := &FileConfig{Hub: srv.URL, Name: "web01", ServerID: "s1", Token: "agent-token"}
	return New(cfg, log.New(io.Discard, "", 0), "test")
}

func tcpCheck(t *testing.T) (hubapi.AgentCheck, func()) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return hubapi.AgentCheck{ID: "c1", Type: "TCP", Target: l.Addr().String(), IntervalSeconds: 5, TimeoutMs: 2000},
		func() { l.Close() }
}

func TestRunOnceRunsAssignedChecksAndReports(t *testing.T) {
	check, closeL := tcpCheck(t)
	defer closeL()
	hub := &fakeHub{t: t, checks: []hubapi.AgentCheck{check}}
	a := newAgent(t, hub)

	results, err := a.RunOnce(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(results) != 1 || results[0].Status != "UP" || results[0].CheckID != "c1" {
		t.Fatalf("results: %+v", results)
	}
	if hub.resultCount() != 1 {
		t.Fatalf("hub saw %d results", hub.resultCount())
	}
	if hub.reports[0].AgentVersion != "test" || hub.reports[0].OS == "" {
		t.Fatalf("report metadata missing: %+v", hub.reports[0])
	}
}

func TestRejectedTokenIsAnError(t *testing.T) {
	hub := &fakeHub{t: t}
	srv := httptest.NewServer(hub)
	defer srv.Close()
	a := New(&FileConfig{Hub: srv.URL, Token: "wrong"}, log.New(io.Discard, "", 0), "test")
	if _, err := a.RunOnce(context.Background()); err == nil {
		t.Fatal("expected an error for a rejected token")
	}
}

func TestRunLoopReportsAndSurvivesHubOutage(t *testing.T) {
	check, closeL := tcpCheck(t)
	defer closeL()
	check.IntervalSeconds = 1
	hub := &fakeHub{t: t, checks: []hubapi.AgentCheck{check}}
	a := newAgent(t, hub)

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _ = a.Run(ctx); close(done) }()

	waitFor(t, 6*time.Second, "first results", func() bool { return hub.resultCount() >= 1 })

	// Outage: reports fail, results accumulate in the buffer.
	hub.mu.Lock()
	hub.fail = true
	hub.mu.Unlock()
	time.Sleep(3 * time.Second)
	a.mu.Lock()
	buffered := len(a.buf)
	a.mu.Unlock()
	if buffered < 1 {
		t.Fatal("results should be buffered while the hub is down")
	}

	// Recovery: the buffer is replayed.
	hub.mu.Lock()
	hub.fail = false
	before := 0
	for _, r := range hub.reports {
		before += len(r.Results)
	}
	hub.mu.Unlock()
	waitFor(t, 6*time.Second, "buffered results replayed", func() bool { return hub.resultCount() >= before+buffered })

	cancel()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("agent did not stop")
	}
}

func TestBufferIsBounded(t *testing.T) {
	a := New(&FileConfig{Hub: "http://127.0.0.1:1", Token: "t"}, log.New(io.Discard, "", 0), "test")
	for range maxBuffer + 100 {
		a.enqueue(resultFor("c"))
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.buf) != maxBuffer {
		t.Fatalf("buffer = %d, want %d", len(a.buf), maxBuffer)
	}
	if a.buf[0].seq != 101 {
		t.Fatalf("oldest results should be dropped first, first seq = %d", a.buf[0].seq)
	}
}

func TestConfigRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "sub", "agent.json")
	in := &FileConfig{Hub: "http://hub:8080", Name: "web01", ServerID: "s1", Token: "svm_g_x"}
	if err := in.Save(path); err != nil {
		t.Fatal(err)
	}
	out, err := LoadConfig(path)
	if err != nil || *out != *in {
		t.Fatalf("round trip: %+v %v", out, err)
	}
	if _, err := LoadConfig(filepath.Join(t.TempDir(), "missing.json")); err == nil {
		t.Fatal("missing file should error")
	}
}

func waitFor(t *testing.T, d time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(d)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}
