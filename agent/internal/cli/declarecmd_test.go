package cli

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"svcmon/internal/agent"
	"svcmon/internal/hubapi"
)

func declareSetup(t *testing.T, checksJSON string, answer hubapi.DeclareResponse) (cfgPath string, sent *map[string]any) {
	t.Helper()
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPut || r.URL.Path != "/api/v1/agent/checks" || r.Header.Get("Authorization") != "Bearer agent-token" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		_ = json.NewEncoder(w).Encode(answer)
	}))
	t.Cleanup(srv.Close)
	dir := t.TempDir()
	cfgPath = filepath.Join(dir, "agent.json")
	cfg := &agent.FileConfig{Hub: srv.URL, Name: "web01", ServerID: "s1", Token: "agent-token"}
	if err := cfg.Save(cfgPath); err != nil {
		t.Fatal(err)
	}
	if checksJSON != "" {
		if err := os.WriteFile(filepath.Join(dir, agent.ChecksFileName), []byte(checksJSON), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	return cfgPath, &body
}

func TestAgentDeclareSendsTheFileAndShowsWhatTheHubDid(t *testing.T) {
	cfg, sent := declareSetup(t, `{"checks":[{"type":"container","target":"web"},{"type":"cpu","config":{"warn":500}}]}`,
		hubapi.DeclareResponse{
			Results: []hubapi.DeclareResult{
				{Index: 0, CheckID: "11112222-3333", Result: "created"},
				{Index: 1, Result: "rejected", Error: "warn must be a number between 0 and 100 (percent used)"},
			},
			Removed: []string{"old check"},
		})
	var out, errOut bytes.Buffer
	code := Run([]string{"agent", "declare", "--config", cfg}, &out, &errOut)
	if code != exitWarn {
		t.Fatalf("exit %d, want %d (an entry was rejected): %s%s", code, exitWarn, out.String(), errOut.String())
	}
	checks := (*sent)["checks"].([]any)
	if len(checks) != 2 || checks[0].(map[string]any)["type"] != "CONTAINER" {
		t.Fatalf("sent %v", *sent)
	}
	for _, want := range []string{"created", "CONTAINER", "web", "11112222", "rejected", "CPU", "warn must be", "old check"} {
		if !strings.Contains(out.String(), want) {
			t.Errorf("output lacks %q:\n%s", want, out.String())
		}
	}
}

func TestAgentDeclareDryRunSendsNothing(t *testing.T) {
	cfg, sent := declareSetup(t, `{"checks":[{"type":"cpu"}]}`, hubapi.DeclareResponse{})
	var out, errOut bytes.Buffer
	if code := Run([]string{"agent", "declare", "--dry-run", "--config", cfg}, &out, &errOut); code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out.String(), errOut.String())
	}
	if !strings.Contains(out.String(), "1 check(s)") || *sent != nil {
		t.Fatalf("output %q, sent %v", out.String(), *sent)
	}
}

func TestAgentDeclareNeedsAValidFile(t *testing.T) {
	cfg, _ := declareSetup(t, "", hubapi.DeclareResponse{})
	var out, errOut bytes.Buffer
	if code := Run([]string{"agent", "declare", "--config", cfg}, &out, &errOut); code == exitOK || !strings.Contains(errOut.String(), "no checks file") {
		t.Fatalf("a missing file: exit %d %q", code, errOut.String())
	}
	cfg, _ = declareSetup(t, `{"checks":[{"type":"cpu","intervall":5}]}`, hubapi.DeclareResponse{})
	errOut.Reset()
	if code := Run([]string{"agent", "declare", "--dry-run", "--config", cfg}, &out, &errOut); code == exitOK || !strings.Contains(errOut.String(), "intervall") {
		t.Fatalf("a misspelt setting: exit %d %q", code, errOut.String())
	}
}
