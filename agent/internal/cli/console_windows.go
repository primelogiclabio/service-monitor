//go:build windows

package cli

import "golang.org/x/sys/windows"

// enableANSI turns on escape-sequence handling so --watch can clear the screen on Windows consoles.
func enableANSI() {
	h, err := windows.GetStdHandle(windows.STD_OUTPUT_HANDLE)
	if err != nil {
		return
	}
	var mode uint32
	if windows.GetConsoleMode(h, &mode) == nil {
		_ = windows.SetConsoleMode(h, mode|windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING)
	}
}
