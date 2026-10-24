//go:build !windows && !linux && !darwin

package checks

import (
	"context"
	"fmt"
	"runtime"
)

func queryService(context.Context, string) (status, detail string, err error) {
	return "", "", fmt.Errorf("service checks are not supported on %s", runtime.GOOS)
}

// ListServices is not supported on this platform.
func ListServices(context.Context) ([]ServiceInfo, error) {
	return nil, fmt.Errorf("listing services is not supported on %s", runtime.GOOS)
}
