//go:build darwin

package osservice

import (
	"context"
	"errors"
	"fmt"
	"html"
	"os"
	"os/exec"
	"strings"
)

const (
	label     = "dev.svcmon.agent"
	plistPath = "/Library/LaunchDaemons/dev.svcmon.agent.plist"
)

// IsService is always false: launchd runs the agent as an ordinary foreground process.
func IsService() bool { return false }

// RunService is only used on Windows.
func RunService(string, func(ctx context.Context) error) error {
	return errors.New("not running under a service manager")
}

// Install writes a launchd daemon plist and loads it.
func Install(o Options) error {
	if os.Geteuid() != 0 {
		return errors.New("installing a launchd daemon needs root; re-run with sudo")
	}
	var args strings.Builder
	for _, a := range append([]string{o.Exe}, o.Args...) {
		args.WriteString("    <string>" + html.EscapeString(a) + "</string>\n")
	}
	plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>%s</string>
  <key>ProgramArguments</key>
  <array>
%s  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict>
</plist>
`, label, args.String())
	if err := os.WriteFile(plistPath, []byte(plist), 0o644); err != nil {
		return err
	}
	if out, err := exec.Command("launchctl", "load", "-w", plistPath).CombinedOutput(); err != nil {
		return fmt.Errorf("launchctl load: %v: %s", err, out)
	}
	return nil
}

// Uninstall unloads and removes the daemon.
func Uninstall(string) error {
	if os.Geteuid() != 0 {
		return errors.New("removing a launchd daemon needs root; re-run with sudo")
	}
	_ = exec.Command("launchctl", "unload", "-w", plistPath).Run()
	if err := os.Remove(plistPath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}
