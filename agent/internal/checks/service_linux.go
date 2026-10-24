//go:build linux

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

var unitName = regexp.MustCompile(`^[A-Za-z0-9:_.@\\][A-Za-z0-9:_.@\\-]*$`)

// queryService asks systemd. A bare name such as "nginx" means "nginx.service".
func queryService(ctx context.Context, name string) (status, detail string, err error) {
	unit := name
	if !strings.Contains(unit, ".") {
		unit += ".service"
	}
	if !unitName.MatchString(unit) {
		return "", "", fmt.Errorf("invalid unit name %q", name)
	}
	out, err := exec.CommandContext(ctx, "systemctl", "show", unit,
		"--property=LoadState,ActiveState,SubState").Output()
	if err != nil {
		return "", "", err
	}
	props := map[string]string{}
	sc := bufio.NewScanner(bytes.NewReader(out))
	for sc.Scan() {
		if k, v, ok := strings.Cut(sc.Text(), "="); ok {
			props[k] = v
		}
	}
	if props["LoadState"] == "not-found" {
		return Down, "not installed", nil
	}
	active, sub := props["ActiveState"], props["SubState"]
	desc := active + " (" + sub + ")"
	switch active {
	case "active":
		return Up, desc, nil
	case "activating", "reloading":
		return Warn, desc, nil
	default:
		return Down, desc, nil
	}
}

// ListServices lists systemd service units. Nothing here is monitored; it only helps an admin pick names.
func ListServices(ctx context.Context) ([]ServiceInfo, error) {
	out, err := exec.CommandContext(ctx, "systemctl", "list-units", "--type=service", "--all",
		"--no-legend", "--no-pager", "--plain").Output()
	if err != nil {
		return nil, err
	}
	var list []ServiceInfo
	sc := bufio.NewScanner(bytes.NewReader(out))
	for sc.Scan() {
		f := strings.Fields(sc.Text())
		if len(f) < 4 {
			continue
		}
		list = append(list, ServiceInfo{
			Name:    f[0],
			Display: strings.Join(f[4:], " "),
			State:   f[2] + " (" + f[3] + ")",
		})
	}
	sort.Slice(list, func(i, j int) bool { return list[i].Name < list[j].Name })
	return list, nil
}
