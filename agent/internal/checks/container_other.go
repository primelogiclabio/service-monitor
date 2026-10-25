//go:build !windows

package checks

import (
	"context"
	"errors"
	"net"
)

// defaultPipes is empty: named pipes exist only on Windows.
var defaultPipes []string

func dialPipe(context.Context, string) (net.Conn, error) {
	return nil, errors.New("named pipes exist only on Windows")
}

func pipeReachable(string) bool { return false }
