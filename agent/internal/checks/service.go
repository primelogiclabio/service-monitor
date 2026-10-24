package checks

import (
	"context"
	"fmt"
	"strings"
)

// ServiceInfo describes one service found on this machine (used by "svcmon agent services").
type ServiceInfo struct {
	Name    string
	Display string
	State   string
}

// serviceCheck asks the operating system's service manager whether the named service is running.
// The name is whatever the OS calls it: a Windows service name, a systemd unit, a launchd label.
func serviceCheck(ctx context.Context, c Check) Result {
	name := strings.TrimSpace(c.Target)
	if name == "" {
		return result(c, Unknown, nil, nil, "service name is empty")
	}
	status, detail, err := queryService(ctx, name)
	if err != nil {
		return result(c, Unknown, nil, nil, "cannot query service manager: "+err.Error())
	}
	return result(c, status, nil, nil, fmt.Sprintf("%s: %s", name, detail))
}
