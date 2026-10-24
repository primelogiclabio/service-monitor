//go:build linux

package osservice

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"strings"
)

const unitPath = "/etc/systemd/system/svcmon-agent.service"

// IsService is always false: systemd runs the agent as an ordinary foreground process.
func IsService() bool { return false }

// RunService is only used on Windows.
func RunService(string, func(ctx context.Context) error) error {
	return errors.New("not running under a service manager")
}

// Install writes a systemd unit and enables it. CAP_NET_RAW lets ping work without a sysctl change.
func Install(o Options) error {
	if os.Geteuid() != 0 {
		return errors.New("installing a systemd unit needs root; re-run with sudo")
	}
	unit := fmt.Sprintf(`[Unit]
Description=%s
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=%s %s
Restart=always
RestartSec=5
AmbientCapabilities=CAP_NET_RAW
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
`, o.Display, o.Exe, strings.Join(quoteAll(o.Args), " "))
	if err := os.WriteFile(unitPath, []byte(unit), 0o644); err != nil {
		return err
	}
	for _, args := range [][]string{{"daemon-reload"}, {"enable", "--now", o.Name + ".service"}} {
		if out, err := exec.Command("systemctl", args...).CombinedOutput(); err != nil {
			return fmt.Errorf("systemctl %s: %v: %s", strings.Join(args, " "), err, out)
		}
	}
	return nil
}

// Uninstall stops, disables and removes the unit.
func Uninstall(name string) error {
	if os.Geteuid() != 0 {
		return errors.New("removing a systemd unit needs root; re-run with sudo")
	}
	_ = exec.Command("systemctl", "disable", "--now", name+".service").Run()
	if err := os.Remove(unitPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return exec.Command("systemctl", "daemon-reload").Run()
}

func quoteAll(args []string) []string {
	out := make([]string, len(args))
	for i, a := range args {
		if strings.ContainsAny(a, " \t\"'\\") {
			out[i] = `"` + strings.ReplaceAll(a, `"`, `\"`) + `"`
		} else {
			out[i] = a
		}
	}
	return out
}
