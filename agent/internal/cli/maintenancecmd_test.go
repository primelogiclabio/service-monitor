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
	"time"

	"svcmon/internal/agent"
	"svcmon/internal/hubapi"
)

// maintenanceHub is just enough of the hub to see what the maintenance commands send. One server, web01, which may
// be in maintenance; only one token works, an admin's or an agent's depending on the test.
type maintenanceHub struct {
	mu       sync.Mutex
	token    string
	open     *hubapi.Maintenance
	refuse   int // answer the next POST with this status
	requests []recorded
}

func (h *maintenanceHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get("Authorization") != "Bearer "+h.token {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	h.mu.Lock()
	defer h.mu.Unlock()
	h.requests = append(h.requests, recorded{r.Method, r.URL.Path, body})
	w.Header().Set("Content-Type", "application/json")
	switch {
	case r.Method == http.MethodGet && r.URL.Path == "/api/v1/status":
		sv := hubapi.ServerView{ID: "5e7a1c00-aaaa", Name: "web01", Status: "UP", AgentOnline: true, Maintenance: h.open}
		_ = json.NewEncoder(w).Encode(hubapi.Status{Clusters: []hubapi.ClusterView{{Name: "default", Servers: []hubapi.ServerView{sv}}}})
	case r.Method == http.MethodPost && (r.URL.Path == "/api/v1/servers/5e7a1c00-aaaa/maintenance" || r.URL.Path == "/api/v1/agent/maintenance"):
		if h.refuse != 0 {
			w.WriteHeader(h.refuse)
			_, _ = w.Write([]byte(`{"error":"agent_maintenance_off","message":"Agents may not open maintenance windows on this hub"}`))
			return
		}
		minutes := int(body["minutes"].(float64))
		reason, _ := body["reason"].(string)
		h.open = &hubapi.Maintenance{ServerID: "5e7a1c00-aaaa", EndsAt: time.Now().Add(time.Duration(minutes) * time.Minute).UnixMilli(),
			Reason: reason, StartedBy: "ops", StartedByKind: "token", UntilBack: body["untilBack"] == true}
		_ = json.NewEncoder(w).Encode(h.open)
	case r.Method == http.MethodDelete && (r.URL.Path == "/api/v1/servers/5e7a1c00-aaaa/maintenance" || r.URL.Path == "/api/v1/agent/maintenance"):
		if h.open == nil {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"error":"no_maintenance","message":"No maintenance window is open for this server"}`))
			return
		}
		h.open = nil
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusNotFound)
	}
}

func (h *maintenanceHub) last() recorded {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.requests[len(h.requests)-1]
}

func newMaintenanceHub(t *testing.T, token string) (*maintenanceHub, string) {
	t.Helper()
	hub := &maintenanceHub{token: token}
	srv := httptest.NewServer(hub)
	t.Cleanup(srv.Close)
	return hub, srv.URL
}

func runCLI(args ...string) (int, string, string) {
	var out, errOut bytes.Buffer
	code := Run(args, &out, &errOut)
	return code, out.String(), errOut.String()
}

func TestMaintenanceStartListAndEnd(t *testing.T) {
	hub, url := newMaintenanceHub(t, "tok")
	admin := []string{"--hub", url, "--token", "tok"}

	code, out, errOut := runCLI(append(admin, "maintenance", "start", "WEB01", "1h30m", "--reason", "kernel update")...)
	if code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	sent := hub.last()
	if sent.Method != "POST" || sent.Path != "/api/v1/servers/5e7a1c00-aaaa/maintenance" {
		t.Fatalf("unexpected request %+v", sent)
	}
	if sent.Body["minutes"] != float64(90) || sent.Body["reason"] != "kernel update" || sent.Body["untilBack"] != true {
		t.Fatalf("body: %v", sent.Body)
	}
	if !strings.Contains(out, "Alerts for web01 are held back until") || !strings.Contains(out, "once everything") {
		t.Fatalf("output: %s", out)
	}

	code, out, _ = runCLI(append(admin, "maintenance", "list")...)
	if code != exitOK || !strings.Contains(out, "web01") || !strings.Contains(out, "kernel update") || !strings.Contains(out, "token ops") {
		t.Fatalf("exit %d, list: %s", code, out)
	}
	code, out, _ = runCLI(append(admin, "server", "web01")...)
	if code != exitOK || !strings.Contains(out, "MAINTENANCE until") {
		t.Fatalf("exit %d, server: %s", code, out)
	}

	code, out, errOut = runCLI(append(admin, "maintenance", "end", "5e7a")...)
	if code != exitOK || hub.last().Method != "DELETE" || !strings.Contains(out, "Maintenance of web01 ended") {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	code, _, errOut = runCLI(append(admin, "maintenance", "end", "web01")...)
	if code != exitUnknown || !strings.Contains(errOut, "No maintenance window is open") {
		t.Fatalf("ending twice: exit %d, %s", code, errOut)
	}
	code, out, _ = runCLI(append(admin, "maintenance", "list")...)
	if code != exitOK || !strings.Contains(out, "No server is in maintenance") {
		t.Fatalf("exit %d, list: %s", code, out)
	}
}

func TestMaintenanceStartFullTime(t *testing.T) {
	hub, url := newMaintenanceHub(t, "tok")
	code, out, errOut := runCLI("--hub", url, "--token", "tok", "maintenance", "start", "web01", "45", "--full")
	if code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	if hub.last().Body["minutes"] != float64(45) || hub.last().Body["untilBack"] != false {
		t.Fatalf("body: %v", hub.last().Body)
	}
	if !strings.Contains(out, "when ended with 'end'") {
		t.Fatalf("output: %s", out)
	}
}

func TestMaintenanceRefusesBadInputBeforeAskingTheHub(t *testing.T) {
	hub, url := newMaintenanceHub(t, "tok")
	admin := []string{"--hub", url, "--token", "tok"}
	for _, tc := range []struct {
		args []string
		want string
	}{
		{[]string{"maintenance", "start", "web01", "0"}, "at least one minute"},
		{[]string{"maintenance", "start", "web01", "30s"}, "at least one minute"},
		{[]string{"maintenance", "start", "web01", "25h"}, "at most 24 hours"},
		{[]string{"maintenance", "start", "web01", "soon"}, "invalid duration"},
		{[]string{"maintenance", "start", "web01"}, "usage"},
		{[]string{"maintenance", "pause", "web01"}, "unknown maintenance command"},
	} {
		code, _, errOut := runCLI(append(admin, tc.args...)...)
		if code != exitUnknown || !strings.Contains(errOut, tc.want) {
			t.Errorf("%v: exit %d, %q; want %q", tc.args, code, errOut, tc.want)
		}
	}
	if len(hub.requests) != 0 {
		t.Fatalf("sent %v", hub.requests)
	}
	code, _, errOut := runCLI(append(admin, "maintenance", "start", "db09", "10m")...)
	if code != exitUnknown || !strings.Contains(errOut, `no server matches "db09"`) {
		t.Fatalf("exit %d, %s", code, errOut)
	}
}

func TestWindowMinutesRoundsUpToWholeMinutes(t *testing.T) {
	for in, want := range map[string]int{"20": 20, "20m": 20, "1h30m": 90, "90s": 2, "24h": 1440} {
		got, err := windowMinutes(in, maxWindow)
		if err != nil || got != want {
			t.Errorf("windowMinutes(%q) = %d, %v; want %d", in, got, err, want)
		}
	}
}

func agentConfig(t *testing.T, hubURL string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "agent.json")
	cfg := &agent.FileConfig{Hub: hubURL, Name: "web01", ServerID: "5e7a1c00-aaaa", Token: "agent-token"}
	if err := cfg.Save(path); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestAgentMaintenanceUsesTheAgentsOwnCredential(t *testing.T) {
	hub, url := newMaintenanceHub(t, "agent-token")
	cfg := agentConfig(t, url)

	code, out, errOut := runCLI("agent", "maintenance", "20m", "--reason", "reboot", "--config", cfg)
	if code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	if hub.last().Method != "POST" || hub.last().Path != "/api/v1/agent/maintenance" || hub.last().Body["minutes"] != float64(20) {
		t.Fatalf("request %+v", hub.last())
	}
	if !strings.Contains(out, "Alerts for this server are held back") {
		t.Fatalf("output: %s", out)
	}
	code, _, errOut = runCLI("agent", "maintenance", "start", "15", "--full", "--config", cfg)
	if code != exitOK || hub.last().Body["untilBack"] != false {
		t.Fatalf("exit %d: %s, %+v", code, errOut, hub.last())
	}

	code, out, errOut = runCLI("agent", "maintenance", "end", "--config", cfg)
	if code != exitOK || hub.last().Method != "DELETE" || !strings.Contains(out, "ended") {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	code, _, errOut = runCLI("agent", "maintenance", "end", "--config", cfg)
	if code != exitUnknown || !strings.Contains(errOut, "no maintenance window is open") {
		t.Fatalf("exit %d: %s", code, errOut)
	}
	code, _, errOut = runCLI("agent", "maintenance", "--config", cfg)
	if code != exitUnknown || !strings.Contains(errOut, "usage") {
		t.Fatalf("exit %d: %s", code, errOut)
	}
}

func TestAgentMaintenanceTurnedOffPointsAtAnAdmin(t *testing.T) {
	hub, url := newMaintenanceHub(t, "agent-token")
	hub.refuse = http.StatusForbidden
	code, _, errOut := runCLI("agent", "maintenance", "20m", "--config", agentConfig(t, url))
	if code != exitUnknown || !strings.Contains(errOut, "may not open") || !strings.Contains(errOut, "svcmon maintenance start") {
		t.Fatalf("exit %d: %s", code, errOut)
	}
}
