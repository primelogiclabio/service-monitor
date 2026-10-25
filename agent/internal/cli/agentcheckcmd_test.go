package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"svcmon/internal/agent"
	"svcmon/internal/checks"
	"svcmon/internal/hubapi"
)

func agentCheckRun(t *testing.T, args ...string) (code int, out, errOut string) {
	t.Helper()
	var o, e bytes.Buffer
	code = Run(append([]string{"agent", "check"}, args...), &o, &e)
	return code, o.String(), e.String()
}

func checksIn(t *testing.T, cfgPath string) []hubapi.DeclaredCheck {
	t.Helper()
	list, _, err := agent.LoadChecks(agent.ChecksPath(cfgPath))
	if err != nil {
		t.Fatal(err)
	}
	return list
}

func TestAgentCheckAddWritesTheFileAndTellsTheHub(t *testing.T) {
	cfg, sent := declareSetup(t, "", hubapi.DeclareResponse{
		Results: []hubapi.DeclareResult{{Index: 0, CheckID: "11112222-3333", Result: "created"}},
	})
	code, out, errOut := agentCheckRun(t, "add", "container", "web", "--interval", "20", "--failures", "2", "--socket", "/run/podman/podman.sock", "--config", cfg)
	if code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	list := checksIn(t, cfg)
	if len(list) != 1 || list[0].Type != "container" || list[0].Target != "web" || list[0].IntervalSeconds != 20 ||
		list[0].FailuresBeforeDown != 2 || list[0].Config["socket"] != "/run/podman/podman.sock" {
		t.Fatalf("file: %+v", list)
	}
	sentChecks := (*sent)["checks"].([]any)
	if len(sentChecks) != 1 || sentChecks[0].(map[string]any)["type"] != "CONTAINER" {
		t.Fatalf("sent %v", *sent)
	}
	for _, want := range []string{"Added CONTAINER web", "created", "11112222"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}

	// A second one is added to the first, and the hub is given the whole list.
	if code, out, errOut := agentCheckRun(t, "add", "disk", "/", "--warn", "80", "--crit", "90", "--config", cfg); code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	list = checksIn(t, cfg)
	if len(list) != 2 || list[1].Type != "disk" || list[1].Config["warn"] != float64(80) {
		t.Fatalf("file: %+v", list)
	}
	if n := len((*sent)["checks"].([]any)); n != 2 {
		t.Fatalf("the hub was given %d checks, want the whole list of 2", n)
	}
}

func TestAgentCheckAddRefusesWhatWouldBeTheSameCheck(t *testing.T) {
	cfg, _ := declareSetup(t, `{"checks":[{"type":"disk","target":"/"}]}`, hubapi.DeclareResponse{})
	code, _, errOut := agentCheckRun(t, "add", "DISK", "/", "--no-declare", "--config", cfg)
	if code == exitOK || !strings.Contains(errOut, "already in") {
		t.Fatalf("a duplicate: exit %d %q", code, errOut)
	}
	// An id tells a second check of the same type and target apart, and a repeated id is refused.
	if code, _, errOut := agentCheckRun(t, "add", "disk", "/", "--id", "root-warn", "--no-declare", "--config", cfg); code != exitOK {
		t.Fatalf("with an id: exit %d %q", code, errOut)
	}
	code, _, errOut = agentCheckRun(t, "add", "cpu", "--id", "root-warn", "--no-declare", "--config", cfg)
	if code == exitOK || !strings.Contains(errOut, "already used") {
		t.Fatalf("a repeated id: exit %d %q", code, errOut)
	}
	if n := len(checksIn(t, cfg)); n != 2 {
		t.Fatalf("%d checks in the file, want 2", n)
	}
	for _, args := range [][]string{{"add", "sql", "x"}, {"add"}, {"add", "disk", "/", "extra"}, {"add", "cpu", "--interval", "-5"}} {
		if code, _, _ := agentCheckRun(t, append(args, "--no-declare", "--config", cfg)...); code == exitOK {
			t.Errorf("%v was accepted", args)
		}
	}
	if n := len(checksIn(t, cfg)); n != 2 {
		t.Fatalf("a refused add changed the file: %d checks", n)
	}
}

func TestAgentCheckAddKeepsTheFileWhenTheHubCannotBeReached(t *testing.T) {
	dir := t.TempDir()
	cfgPath := filepath.Join(dir, "agent.json")
	if err := (&agent.FileConfig{Hub: "http://127.0.0.1:1", Name: "web01", ServerID: "s1", Token: "agent-token"}).Save(cfgPath); err != nil {
		t.Fatal(err)
	}
	code, _, errOut := agentCheckRun(t, "add", "memory", "--config", cfgPath)
	if code != exitWarn || !strings.Contains(errOut, "file is saved") {
		t.Fatalf("exit %d %q, want a warning that the file is saved", code, errOut)
	}
	if list := checksIn(t, cfgPath); len(list) != 1 || list[0].Type != "memory" {
		t.Fatalf("file: %+v", list)
	}
}

func TestAgentCheckNoDeclareSendsNothing(t *testing.T) {
	cfg, sent := declareSetup(t, "", hubapi.DeclareResponse{})
	code, out, errOut := agentCheckRun(t, "add", "cpu", "--no-declare", "--config", cfg)
	if code != exitOK || *sent != nil || !strings.Contains(out, "Not sent") {
		t.Fatalf("exit %d, sent %v: %s%s", code, *sent, out, errOut)
	}
}

func TestAgentCheckRemove(t *testing.T) {
	cfg, sent := declareSetup(t, `{"checks":[
		{"type":"container","target":"web","name":"Web container"},
		{"type":"container","target":"db"},
		{"id":"root","type":"disk","target":"/"},
		{"type":"cpu"}]}`, hubapi.DeclareResponse{Removed: []string{"gone"}})

	// By name, by id, by type and target, and by a type that only one check has.
	for _, args := range [][]string{{"web container"}, {"root"}, {"container", "db"}} {
		if code, out, errOut := agentCheckRun(t, append([]string{"rm"}, append(args, "--config", cfg)...)...); code != exitOK {
			t.Fatalf("rm %v: exit %d %s%s", args, code, out, errOut)
		}
	}
	if list := checksIn(t, cfg); len(list) != 1 || list[0].Type != "cpu" {
		t.Fatalf("file after three removals: %+v", list)
	}
	code, out, _ := agentCheckRun(t, "rm", "cpu", "--config", cfg)
	if code != exitOK || len(checksIn(t, cfg)) != 0 || !strings.Contains(out, "switched off") {
		t.Fatalf("rm cpu: exit %d %s", code, out)
	}
	// The hub was given the empty list, which is how it learns to switch the checks off.
	if got := (*sent)["checks"].([]any); len(got) != 0 {
		t.Fatalf("the hub was given %v", got)
	}
	if code, _, errOut := agentCheckRun(t, "rm", "nothing", "--config", cfg); code == exitOK || !strings.Contains(errOut, "matches") {
		t.Fatalf("rm of an unknown check: exit %d %q", code, errOut)
	}
}

func TestAgentCheckRemoveNeedsOneMatch(t *testing.T) {
	cfg, _ := declareSetup(t, `{"checks":[{"type":"container","target":"web"},{"type":"container","target":"db"}]}`, hubapi.DeclareResponse{})
	code, _, errOut := agentCheckRun(t, "rm", "container", "--no-declare", "--config", cfg)
	if code == exitOK || !strings.Contains(errOut, "matches 2 checks") {
		t.Fatalf("an ambiguous type: exit %d %q", code, errOut)
	}
	if n := len(checksIn(t, cfg)); n != 2 {
		t.Fatalf("an ambiguous rm changed the file: %d checks", n)
	}
	missing := filepath.Join(t.TempDir(), "agent.json")
	if code, _, errOut := agentCheckRun(t, "rm", "cpu", "--config", missing); code == exitOK || !strings.Contains(errOut, "no checks file") {
		t.Fatalf("no file: exit %d %q", code, errOut)
	}
}

func TestAgentCheckListShowsTheFile(t *testing.T) {
	cfg, _ := declareSetup(t, `{"checks":[{"type":"container","target":"web","intervalSeconds":20},{"id":"root","name":"Root","type":"disk","target":"/"}]}`, hubapi.DeclareResponse{})
	code, out, errOut := agentCheckRun(t, "list", "--config", cfg)
	if code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	for _, want := range []string{"CONTAINER", "web", "20s", "DISK", "Root", "root", "default"} {
		if !strings.Contains(out, want) {
			t.Errorf("list lacks %q:\n%s", want, out)
		}
	}
	if code, out, _ := agentCheckRun(t, "list", "--json", "--config", cfg); code != exitOK || !strings.Contains(out, `"checks"`) || !strings.Contains(out, `"intervalSeconds": 20`) {
		t.Fatalf("--json: exit %d %s", code, out)
	}
	// A server with no file is told how to start one.
	empty := filepath.Join(t.TempDir(), "agent.json")
	if code, out, _ := agentCheckRun(t, "list", "--config", empty); code != exitOK || !strings.Contains(out, "No checks file") {
		t.Fatalf("no file: exit %d %s", code, out)
	}
	if code, _, errOut := agentCheckRun(t, "bogus"); code == exitOK || !strings.Contains(errOut, "unknown agent check command") {
		t.Fatalf("unknown command: exit %d %q", code, errOut)
	}
}

func TestAgentCheckRefusesToRewriteAFileItCannotRead(t *testing.T) {
	cfg, _ := declareSetup(t, `{"checks":[{"type":"cpu","intervall":5}]}`, hubapi.DeclareResponse{})
	before, _ := os.ReadFile(agent.ChecksPath(cfg))
	if code, _, errOut := agentCheckRun(t, "add", "memory", "--no-declare", "--config", cfg); code == exitOK || !strings.Contains(errOut, "intervall") {
		t.Fatalf("exit %d %q", code, errOut)
	}
	after, _ := os.ReadFile(agent.ChecksPath(cfg))
	if string(before) != string(after) {
		t.Fatal("the unreadable file was rewritten")
	}
}

func TestAgentCheckInitWritesAnExampleBesideTheConfig(t *testing.T) {
	cfg := filepath.Join(t.TempDir(), "conf", "agent.json")
	code, out, errOut := agentCheckRun(t, "init", "--config", cfg)
	if code != exitOK {
		t.Fatalf("exit %d: %s%s", code, out, errOut)
	}
	example := agent.ChecksExamplePath(cfg)
	got, err := os.ReadFile(example)
	if err != nil || string(got) != string(agent.ChecksExample) {
		t.Fatalf("the template was not written: %v", err)
	}
	for _, want := range []string{example, "checks.json", "agent check add"} {
		if !strings.Contains(out, want) {
			t.Errorf("output lacks %q:\n%s", want, out)
		}
	}
	// The agent never reads the example, so writing it declares nothing.
	if _, err := os.Stat(agent.ChecksPath(cfg)); err == nil {
		t.Fatal("init created the live checks file")
	}
}

func TestAgentCheckInitNeverReplacesAFileUnlessAsked(t *testing.T) {
	cfg := filepath.Join(t.TempDir(), "agent.json")
	example := agent.ChecksExamplePath(cfg)
	if err := os.WriteFile(example, []byte("my edits"), 0o600); err != nil {
		t.Fatal(err)
	}
	code, _, errOut := agentCheckRun(t, "init", "--config", cfg)
	if code == exitOK || !strings.Contains(errOut, "already exists") {
		t.Fatalf("exit %d %q", code, errOut)
	}
	if got, _ := os.ReadFile(example); string(got) != "my edits" {
		t.Fatalf("the file was changed: %q", got)
	}
	if code, _, errOut := agentCheckRun(t, "init", "--force", "--config", cfg); code != exitOK {
		t.Fatalf("--force: exit %d %q", code, errOut)
	}
	if got, _ := os.ReadFile(example); string(got) != string(agent.ChecksExample) {
		t.Fatal("--force did not replace it")
	}
}

func TestAgentCheckInitStdoutWritesNothing(t *testing.T) {
	dir := t.TempDir()
	code, out, errOut := agentCheckRun(t, "init", "--stdout", "--config", filepath.Join(dir, "agent.json"))
	if code != exitOK || out != string(agent.ChecksExample) {
		t.Fatalf("exit %d, output %q %q", code, out, errOut)
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 0 {
		t.Fatalf("--stdout wrote %v", entries)
	}
}

// The template is a starting point for hand-editing, so what it holds must be what the agent accepts, and the
// other way to build the file (agent check add) must work on a file made from it.
func TestAgentCheckExampleIsAFileTheAgentAccepts(t *testing.T) {
	cfg, _ := declareSetup(t, "", hubapi.DeclareResponse{})
	if err := os.WriteFile(agent.ChecksPath(cfg), agent.ChecksExample, 0o600); err != nil {
		t.Fatal(err)
	}
	list := checksIn(t, cfg)
	seen := map[string]bool{}
	for _, c := range list {
		seen[strings.ToUpper(c.Type)] = true
	}
	for _, typ := range checks.Types {
		if !seen[typ] {
			t.Errorf("the example has no %s check", typ)
		}
	}
	if code, out, errOut := agentCheckRun(t, "add", "cpu", "--id", "second-cpu", "--no-declare", "--config", cfg); code != exitOK {
		t.Fatalf("adding to a file made from the example: exit %d %s%s", code, out, errOut)
	}
	if n := len(checksIn(t, cfg)); n != len(list)+1 {
		t.Fatalf("%d checks, want %d", n, len(list)+1)
	}
}
