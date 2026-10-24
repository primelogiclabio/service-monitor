//go:build windows

package checks

import (
	"context"
	"errors"
	"sort"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// queryService reads a service's state from the Service Control Manager with query-only rights, so it
// works for the LocalSystem agent and for an ordinary user trying a check by hand.
func queryService(_ context.Context, name string) (status, detail string, err error) {
	scm, err := windows.OpenSCManager(nil, nil, windows.SC_MANAGER_CONNECT)
	if err != nil {
		return "", "", err
	}
	defer windows.CloseServiceHandle(scm)

	namePtr, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return "", "", err
	}
	h, err := windows.OpenService(scm, namePtr, windows.SERVICE_QUERY_STATUS)
	if err != nil {
		if errors.Is(err, windows.ERROR_SERVICE_DOES_NOT_EXIST) {
			return Down, "not installed", nil
		}
		return "", "", err
	}
	defer windows.CloseServiceHandle(h)

	var ssp windows.SERVICE_STATUS_PROCESS
	var needed uint32
	if err := windows.QueryServiceStatusEx(h, windows.SC_STATUS_PROCESS_INFO,
		(*byte)(unsafe.Pointer(&ssp)), uint32(unsafe.Sizeof(ssp)), &needed); err != nil {
		return "", "", err
	}
	switch ssp.CurrentState {
	case windows.SERVICE_RUNNING:
		return Up, "running", nil
	case windows.SERVICE_START_PENDING:
		return Warn, "starting", nil
	case windows.SERVICE_CONTINUE_PENDING:
		return Warn, "resuming", nil
	case windows.SERVICE_PAUSE_PENDING:
		return Warn, "pausing", nil
	case windows.SERVICE_PAUSED:
		return Warn, "paused", nil
	case windows.SERVICE_STOP_PENDING:
		return Down, "stopping", nil
	case windows.SERVICE_STOPPED:
		return Down, "stopped", nil
	default:
		return Unknown, "unknown state", nil
	}
}

var procSHLoadIndirectString = windows.NewLazySystemDLL("shlwapi.dll").NewProc("SHLoadIndirectString")

// resolveResource turns display names stored as "@%SystemRoot%\system32\x.dll,-200" into readable text.
func resolveResource(s string) string {
	if !strings.HasPrefix(s, "@") {
		return s
	}
	p, err := windows.UTF16PtrFromString(s)
	if err != nil {
		return s
	}
	buf := make([]uint16, 512)
	hr, _, _ := procSHLoadIndirectString.Call(uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&buf[0])),
		uintptr(len(buf)), 0)
	if hr != 0 {
		return s
	}
	return windows.UTF16ToString(buf)
}

// ListServices enumerates Win32 services from the registry (readable by any user) and looks up each
// one's state. Nothing here is monitored; it only helps an admin pick names.
func ListServices(ctx context.Context) ([]ServiceInfo, error) {
	root, err := registry.OpenKey(registry.LOCAL_MACHINE, `SYSTEM\CurrentControlSet\Services`,
		registry.ENUMERATE_SUB_KEYS|registry.QUERY_VALUE)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	names, err := root.ReadSubKeyNames(-1)
	if err != nil {
		return nil, err
	}
	var out []ServiceInfo
	for _, n := range names {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		k, err := registry.OpenKey(root, n, registry.QUERY_VALUE)
		if err != nil {
			continue
		}
		typ, _, terr := k.GetIntegerValue("Type")
		display, _, _ := k.GetStringValue("DisplayName")
		k.Close()
		const win32Own, win32Share = 0x10, 0x20
		if terr != nil || typ&(win32Own|win32Share) == 0 {
			continue
		}
		state := "?"
		if _, detail, qerr := queryService(ctx, n); qerr == nil {
			state = detail
		}
		out = append(out, ServiceInfo{Name: n, Display: resolveResource(display), State: state})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}
