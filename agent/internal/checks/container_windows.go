package checks

import (
	"context"
	"net"
	"time"

	"github.com/Microsoft/go-winio"
)

// defaultPipes are where Docker Desktop and the Podman machine listen on Windows.
var defaultPipes = []string{
	`\\.\pipe\docker_engine`,
	`\\.\pipe\dockerDesktopLinuxEngine`,
	`\\.\pipe\podman-machine-default`,
}

func dialPipe(ctx context.Context, path string) (net.Conn, error) {
	return winio.DialPipeContext(ctx, path)
}

// pipeReachable asks whether something answers on the pipe. A pipe has no file to look for, and
// merely connecting does no harm: nothing is sent.
func pipeReachable(path string) bool {
	timeout := 300 * time.Millisecond
	conn, err := winio.DialPipe(path, &timeout)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}
