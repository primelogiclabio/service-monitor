package agent

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"svcmon/internal/hubapi"
)

func TestSaveChecksRoundTripsAndReplacesTheFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, ChecksFileName)
	want := []hubapi.DeclaredCheck{
		{Type: "container", Target: "web", IntervalSeconds: 20, Config: map[string]any{"socket": "/run/podman/podman.sock"}},
		{ID: "disk-root", Name: "Root disk", Type: "disk", Target: "/", FailuresBeforeDown: 2},
	}
	if err := SaveChecks(path, want); err != nil {
		t.Fatal(err)
	}
	got, sum, err := LoadChecks(path)
	if err != nil || sum == "" || len(got) != 2 || got[0].Target != "web" || got[1].ID != "disk-root" || got[0].IntervalSeconds != 20 {
		t.Fatalf("read back: %+v %q %v", got, sum, err)
	}

	// Writing again replaces the file, and an empty list is a statement that there are none, not a missing file.
	if err := SaveChecks(path, nil); err != nil {
		t.Fatal(err)
	}
	got, sum, err = LoadChecks(path)
	if err != nil || got == nil || len(got) != 0 || sum == "" {
		t.Fatalf("empty list: %+v %q %v", got, sum, err)
	}
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("temporary files were left behind: %v", entries)
	}
}

func TestCheckKeyIsTheIdOrTheTypeAndTarget(t *testing.T) {
	a := hubapi.DeclaredCheck{Type: "disk", Target: "/"}
	b := hubapi.DeclaredCheck{Type: " DISK ", Target: " / "}
	if CheckKey(a) != CheckKey(b) {
		t.Fatal("the same type and target, however written, is one check")
	}
	if CheckKey(a) == CheckKey(hubapi.DeclaredCheck{Type: "disk", Target: "/data"}) {
		t.Fatal("a different target is a different check")
	}
	withID := hubapi.DeclaredCheck{ID: "root", Type: "disk", Target: "/"}
	if CheckKey(withID) == CheckKey(a) || CheckKey(withID) != CheckKey(hubapi.DeclaredCheck{ID: "root", Type: "cpu"}) {
		t.Fatal("an id replaces the type and target")
	}
}

// hubAnswering is a hub that hands out one list, or fails every request with a status.
func hubAnswering(t *testing.T, list []hubapi.AgentCheck, failWith *atomic.Int32) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if code := failWith.Load(); code != 0 {
			w.WriteHeader(int(code))
			return
		}
		switch r.URL.Path {
		case "/api/v1/agent/config":
			_ = json.NewEncoder(w).Encode(hubapi.AgentConfig{ServerID: "s1", ServerName: "web01", HeartbeatSeconds: 1,
				HubTime: time.Now().UnixMilli(), Checks: list})
		case "/api/v1/agent/report":
			_ = json.NewEncoder(w).Encode(hubapi.ReportResponse{})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	t.Cleanup(srv.Close)
	return srv
}

func cachedAgent(hub, cachePath string) *Agent {
	a := New(&FileConfig{Hub: hub, Name: "web01", ServerID: "s1", Token: "t"}, log.New(io.Discard, "", 0), "test")
	a.SetCacheFile(cachePath)
	return a
}

func TestAgentKeepsTheHubsListAndStartsOnItWhenTheHubIsDown(t *testing.T) {
	check, closeL := tcpCheck(t)
	defer closeL()
	cache := filepath.Join(t.TempDir(), CacheFileName)
	var status atomic.Int32
	srv := hubAnswering(t, []hubapi.AgentCheck{check}, &status)

	a := cachedAgent(srv.URL, cache)
	if _, fromCache, err := a.fetchConfigRetry(context.Background()); err != nil || fromCache {
		t.Fatalf("a hub that answers: fromCache %v, %v", fromCache, err)
	}
	first, err := os.ReadFile(cache)
	if err != nil {
		t.Fatalf("the list was not kept: %v", err)
	}
	// An unchanged list is not written again; the hub's clock reading changes on every poll and must not count.
	stamp := modTime(t, cache)
	time.Sleep(20 * time.Millisecond)
	if _, err := a.fetchConfig(context.Background()); err != nil {
		t.Fatal(err)
	}
	again, _ := os.ReadFile(cache)
	if string(again) != string(first) || !modTime(t, cache).Equal(stamp) {
		t.Fatal("the cache was written again although the list did not change")
	}

	// A new agent process while the hub is unreachable (nothing listens on port 1) runs the kept list.
	down := cachedAgent("http://127.0.0.1:1", cache)
	cfg, fromCache, err := down.fetchConfigRetry(context.Background())
	if err != nil || !fromCache || len(cfg.Checks) != 1 || cfg.Checks[0].ID != "c1" {
		t.Fatalf("hub down: %+v fromCache %v %v", cfg, fromCache, err)
	}
	// So does a hub that fails on its side.
	status.Store(http.StatusBadGateway)
	if _, fromCache, err := cachedAgent(srv.URL, cache).fetchConfigRetry(context.Background()); err != nil || !fromCache {
		t.Fatalf("a failing hub: fromCache %v %v", fromCache, err)
	}
}

func TestHeaderValuesAreNotKeptAndThoseChecksWaitForTheHub(t *testing.T) {
	check, closeL := tcpCheck(t)
	defer closeL()
	web := hubapi.AgentCheck{ID: "c2", Type: "HTTP", Target: "http://127.0.0.1:1/health", IntervalSeconds: 30,
		TimeoutMs: 2000, Config: map[string]any{"keyword": "ok",
			"headers": map[string]any{"Authorization": "Bearer s3cret-value"}}}
	cache := filepath.Join(t.TempDir(), CacheFileName)
	var status atomic.Int32
	srv := hubAnswering(t, []hubapi.AgentCheck{check, web}, &status)

	cfg, err := cachedAgent(srv.URL, cache).fetchConfig(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	// Only the file goes without: the running list keeps the header.
	if h, _ := cfg.Checks[1].Config["headers"].(map[string]any); h["Authorization"] != "Bearer s3cret-value" {
		t.Fatalf("the running list lost its header: %+v", cfg.Checks[1].Config)
	}
	data, err := os.ReadFile(cache)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "s3cret") || strings.Contains(string(data), "Authorization") {
		t.Fatalf("a header was kept on disk:\n%s", data)
	}

	// Started while the hub is down, the agent runs the rest; the check that needs its header waits.
	var logged strings.Builder
	down := cachedAgent("http://127.0.0.1:1", cache)
	down.log = log.New(&logged, "", 0)
	got, fromCache, err := down.fetchConfigRetry(context.Background())
	if err != nil || !fromCache || len(got.Checks) != 1 || got.Checks[0].ID != "c1" {
		t.Fatalf("hub down: %+v fromCache %v %v", got, fromCache, err)
	}
	if !strings.Contains(logged.String(), "1 check(s) need HTTP header values from the hub") {
		t.Fatalf("the waiting check was not logged: %q", logged.String())
	}
}

func TestAnOlderCacheWithHeaderValuesRunsAndIsRewrittenWithout(t *testing.T) {
	web := hubapi.AgentCheck{ID: "c2", Type: "HTTP", Target: "http://127.0.0.1:1/", IntervalSeconds: 30,
		Config: map[string]any{"headers": map[string]any{"X-Api-Key": "k-value"}}}
	cache := filepath.Join(t.TempDir(), CacheFileName)
	data, _ := json.Marshal(hubapi.AgentConfig{ServerID: "s1", ServerName: "web01", HeartbeatSeconds: 30,
		Checks: []hubapi.AgentCheck{web}})
	if err := os.WriteFile(cache, data, 0o600); err != nil {
		t.Fatal(err)
	}
	// An agent before this change kept the values; its file still runs as it is.
	got := cachedAgent("http://127.0.0.1:1", cache).loadCache()
	if got == nil || len(got.Checks) != 1 || got.Checks[0].Config["headers"] == nil {
		t.Fatalf("an older file: %+v", got)
	}

	// The hub's first answer replaces it with one without the values.
	var status atomic.Int32
	srv := hubAnswering(t, []hubapi.AgentCheck{web}, &status)
	if _, err := cachedAgent(srv.URL, cache).fetchConfig(context.Background()); err != nil {
		t.Fatal(err)
	}
	if data, _ := os.ReadFile(cache); strings.Contains(string(data), "k-value") {
		t.Fatalf("the older file was not rewritten:\n%s", data)
	}
}

func modTime(t *testing.T, path string) time.Time {
	t.Helper()
	fi, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	return fi.ModTime()
}

func TestCachedListIsNotUsedWhenTheHubAnswersNo(t *testing.T) {
	check, closeL := tcpCheck(t)
	defer closeL()
	cache := filepath.Join(t.TempDir(), CacheFileName)
	var status atomic.Int32
	srv := hubAnswering(t, []hubapi.AgentCheck{check}, &status)
	if _, err := cachedAgent(srv.URL, cache).fetchConfig(context.Background()); err != nil {
		t.Fatal(err)
	}

	// The hub answered and refused: the token is revoked or the server deleted. Running on would be wrong.
	status.Store(http.StatusUnauthorized)
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	if cfg, fromCache, err := cachedAgent(srv.URL, cache).fetchConfigRetry(ctx); err == nil || fromCache {
		t.Fatalf("a refusing hub: %+v fromCache %v", cfg, fromCache)
	}

	// A list kept for another server (the agent was enrolled again) is not this server's.
	other := New(&FileConfig{Hub: "http://127.0.0.1:1", ServerID: "other", Token: "t"}, log.New(io.Discard, "", 0), "test")
	other.SetCacheFile(cache)
	if other.loadCache() != nil {
		t.Fatal("a list for another server was used")
	}
	// Nothing kept, or a file that is not a list: nothing to start on.
	if cachedAgent("http://127.0.0.1:1", filepath.Join(t.TempDir(), CacheFileName)).loadCache() != nil {
		t.Fatal("a missing cache gave a list")
	}
	if err := os.WriteFile(cache, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if cachedAgent("http://127.0.0.1:1", cache).loadCache() != nil {
		t.Fatal("a broken cache gave a list")
	}
}

func TestRunChecksFromTheKeptListWhileTheHubIsDown(t *testing.T) {
	check, closeL := tcpCheck(t)
	defer closeL()
	check.IntervalSeconds = 1
	cache := filepath.Join(t.TempDir(), CacheFileName)
	var status atomic.Int32
	srv := hubAnswering(t, []hubapi.AgentCheck{check}, &status)
	if _, err := cachedAgent(srv.URL, cache).fetchConfig(context.Background()); err != nil {
		t.Fatal(err)
	}

	status.Store(http.StatusServiceUnavailable)
	a := cachedAgent(srv.URL, cache)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { _ = a.Run(ctx); close(done) }()
	waitFor(t, 6*time.Second, "a result from the kept list", func() bool {
		a.mu.Lock()
		defer a.mu.Unlock()
		return len(a.buf) >= 1 && a.buf[0].res.CheckID == "c1"
	})
	cancel()
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("agent did not stop")
	}
}
