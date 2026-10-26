// Package osservice installs the agent as a native service (Windows service, systemd unit, launchd daemon).
package osservice

// Name is the service name used on every platform.
const Name = "svcmon-agent"

// Options describes the service to install.
type Options struct {
	Name        string
	Display     string
	Description string
	Exe         string   // absolute path of the svcmon binary
	Args        []string // arguments the service manager passes, e.g. "agent", "run", "--config", path
}

// DefaultOptions fills in the names; the caller supplies Exe and Args.
func DefaultOptions(exe string, args []string) Options {
	return Options{
		Name:        Name,
		Display:     "Service Monitor agent", // the hub's service is "Service Monitor hub"
		Description: "Service Monitor agent: runs this server's checks (services, containers, CPU, memory, disk, network) and reports them to the hub.",
		Exe:         exe,
		Args:        args,
	}
}
