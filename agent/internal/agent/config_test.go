package agent

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

// standIn makes DefaultConfigPath see the program at exe, or fail to find it, for one test.
func standIn(t *testing.T, exe string, err error) {
	t.Helper()
	old := executable
	executable = func() (string, error) { return exe, err }
	t.Cleanup(func() { executable = old })
}

// realDir is a temp folder with any links in its path resolved, as DefaultConfigPath resolves them.
func realDir(t *testing.T) string {
	t.Helper()
	dir, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestDefaultConfigPathIsBesideTheProgram(t *testing.T) {
	dir := realDir(t)
	standIn(t, filepath.Join(dir, "svcmon-agent", "svcmon"), nil)
	// The folder does not have to exist yet: enrolling creates it.
	if got, want := DefaultConfigPath(), filepath.Join(dir, "svcmon-agent", "agent.json"); got != want {
		t.Fatalf("DefaultConfigPath() = %q, want %q", got, want)
	}
}

func TestDefaultConfigPathFollowsALinkToTheProgram(t *testing.T) {
	programDir, linkDir := realDir(t), realDir(t)
	program := filepath.Join(programDir, "svcmon")
	if err := os.WriteFile(program, []byte("x"), 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(linkDir, "svcmon")
	if err := os.Symlink(program, link); err != nil {
		t.Skipf("cannot make a symbolic link here: %v", err)
	}
	standIn(t, link, nil)
	if got, want := DefaultConfigPath(), filepath.Join(programDir, "agent.json"); got != want {
		t.Fatalf("DefaultConfigPath() = %q, want %q (beside the program, not the link)", got, want)
	}
}

func TestDefaultConfigPathFallsBackWhenTheProgramCannotBeFound(t *testing.T) {
	standIn(t, "", errors.New("no executable path"))
	got := DefaultConfigPath()
	if got != systemConfigPath() || filepath.Base(got) != "agent.json" {
		t.Fatalf("DefaultConfigPath() = %q, want the system-wide agent.json %q", got, systemConfigPath())
	}
}
