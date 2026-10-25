package agent

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"svcmon/internal/checks"
	"svcmon/internal/hubapi"
)

// ChecksFileName is the file an administrator may put beside agent.json to declare this server's checks.
const ChecksFileName = "checks.json"

// ChecksPath is where the checks file sits for an agent config at cfgPath.
func ChecksPath(cfgPath string) string {
	return filepath.Join(filepath.Dir(cfgPath), ChecksFileName)
}

type checksFile struct {
	Checks []hubapi.DeclaredCheck `json:"checks"`
}

// LoadChecks reads the checks file. A file that is not there declares nothing and is not an error (the
// checksum is empty then), so deleting it does not switch every declared check off; an empty list does.
// Unknown settings are errors, so a typo is found here instead of being ignored.
func LoadChecks(path string) (list []hubapi.DeclaredCheck, sum string, err error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, "", nil
	}
	if err != nil {
		return nil, "", err
	}
	// Windows editors and PowerShell write a UTF-8 byte order mark that JSON does not allow.
	dec := json.NewDecoder(bytes.NewReader(bytes.TrimPrefix(data, []byte("\xef\xbb\xbf"))))
	dec.DisallowUnknownFields()
	var f checksFile
	if err := dec.Decode(&f); err != nil {
		return nil, "", fmt.Errorf("%s: %w", path, err)
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, "", fmt.Errorf("%s: unexpected data after the checks list", path)
	}
	if f.Checks == nil {
		return nil, "", fmt.Errorf(`%s: "checks" is missing (use an empty list to declare that there are none)`, path)
	}
	for i, c := range f.Checks {
		if !slices.Contains(checks.Types, strings.ToUpper(strings.TrimSpace(c.Type))) {
			return nil, "", fmt.Errorf("%s: check %d has type %q; use one of %s", path, i+1, c.Type,
				strings.Join(checks.Types, ", "))
		}
	}
	sha := sha256.Sum256(data)
	return f.Checks, hex.EncodeToString(sha[:]), nil
}

// SetChecksFile makes the agent declare the checks in this file to its hub: when it starts and whenever the file
// has changed, looked at on each config poll.
func (a *Agent) SetChecksFile(path string) { a.checksPath = path }

// Declare sends the whole list to the hub, which creates, updates and switches off checks to match.
func (a *Agent) Declare(ctx context.Context, list []hubapi.DeclaredCheck) (*hubapi.DeclareResponse, error) {
	send := make([]hubapi.DeclaredCheck, len(list))
	for i, c := range list {
		c.Type = strings.ToUpper(strings.TrimSpace(c.Type))
		send[i] = c
	}
	var resp hubapi.DeclareResponse
	err := a.client.Do(ctx, "PUT", "/agent/checks", map[string]any{"checks": send}, &resp)
	if err != nil {
		return nil, err
	}
	return &resp, nil
}

// syncDeclared declares the checks file when it is new or has changed since the hub last took it. It says
// whether the hub took a declaration, so the caller can fetch its check list again at once.
func (a *Agent) syncDeclared(ctx context.Context) bool {
	if a.checksPath == "" {
		return false
	}
	list, sum, err := LoadChecks(a.checksPath)
	if err != nil {
		a.logDeclareProblem("checks file not used: %v", err)
		return false
	}
	if sum == "" || sum == a.declaredSum {
		return false
	}
	resp, err := a.Declare(ctx, list)
	if err != nil {
		a.logDeclareProblem("cannot declare the checks file to the hub: %v", err)
		return false
	}
	a.declaredSum = sum
	a.declareErr = ""
	a.logDeclared(list, resp)
	return true
}

// logDeclareProblem logs a problem once; the same one is not repeated on every config poll.
func (a *Agent) logDeclareProblem(format string, args ...any) {
	msg := fmt.Sprintf(format, args...)
	if msg != a.declareErr {
		a.declareErr = msg
		a.log.Print(msg)
	}
}

func (a *Agent) logDeclared(list []hubapi.DeclaredCheck, resp *hubapi.DeclareResponse) {
	counts := map[string]int{}
	for _, r := range resp.Results {
		counts[r.Result]++
	}
	a.log.Printf("declared %d check(s) from %s: %d created, %d taken over, %d updated, %d unchanged, %d rejected",
		len(list), filepath.Base(a.checksPath), counts["created"], counts["taken over"], counts["updated"],
		counts["unchanged"], counts["rejected"])
	for _, r := range resp.Results {
		if r.Result != "rejected" {
			continue
		}
		what := "entry " + fmt.Sprint(r.Index+1)
		if r.Index >= 0 && r.Index < len(list) {
			what = fmt.Sprintf("entry %d (%s %s)", r.Index+1, list[r.Index].Type, list[r.Index].Target)
		}
		a.log.Printf("the hub rejected %s: %s", what, r.Error)
	}
	if len(resp.Removed) > 0 {
		a.log.Printf("switched off in the hub, no longer in the checks file: %s", strings.Join(resp.Removed, ", "))
	}
}
