//go:build darwin

package checks

import (
	"bufio"
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"regexp"
	"sort"
	"strings"
)

var launchLabel = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]*$`)

// queryService asks launchd about a job label. A loaded job with a PID is running; a loaded job without
// one is reported DOWN, which suits keep-alive daemons but not on-demand jobs.
func queryService(ctx context.Context, name string) (status, detail string, err error) {
	if !launchLabel.MatchString(name) {
		return "", "", fmt.Errorf("invalid launchd label %q", name)
	}
	out, err := exec.CommandContext(ctx, "launchctl", "list", name).CombinedOutput()
	if err != nil {
		var ee *exec.ExitError
		if asExit(err, &ee) {
			return Down, "not loaded", nil
		}
		return "", "", err
	}
	if bytes.Contains(out, []byte(`"PID"`)) {
		return Up, "running", nil
	}
	return Down, "loaded, not running", nil
}

func asExit(err error, target **exec.ExitError) bool {
	ee, ok := err.(*exec.ExitError)
	if ok {
		*target = ee
	}
	return ok
}

// ListServices lists launchd jobs. Nothing here is monitored; it only helps an admin pick names.
func ListServices(ctx context.Context) ([]ServiceInfo, error) {
	out, err := exec.CommandContext(ctx, "launchctl", "list").Output()
	if err != nil {
		return nil, err
	}
	var list []ServiceInfo
	sc := bufio.NewScanner(bytes.NewReader(out))
	first := true
	for sc.Scan() {
		if first { // header line
			first = false
			continue
		}
		f := strings.Fields(sc.Text())
		if len(f) < 3 {
			continue
		}
		state := "loaded"
		if f[0] != "-" {
			state = "running (pid " + f[0] + ")"
		}
		list = append(list, ServiceInfo{Name: f[2], State: state})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Name < list[j].Name })
	return list, nil
}
