//go:build !windows && !linux && !darwin

package osservice

import (
	"context"
	"fmt"
	"runtime"
)

func IsService() bool { return false }

func RunService(string, func(ctx context.Context) error) error {
	return fmt.Errorf("services are not supported on %s", runtime.GOOS)
}

func Install(Options) error {
	return fmt.Errorf("installing a service is not supported on %s; run 'svcmon agent run' under your init system", runtime.GOOS)
}

func Uninstall(string) error {
	return fmt.Errorf("services are not supported on %s", runtime.GOOS)
}
