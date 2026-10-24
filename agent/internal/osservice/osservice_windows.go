//go:build windows

package osservice

import (
	"context"
	"errors"
	"fmt"
	"time"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

// IsService reports whether the process was started by the Service Control Manager.
func IsService() bool {
	ok, err := svc.IsWindowsService()
	return err == nil && ok
}

// RunService hands control to the Service Control Manager; run is cancelled when Windows stops the service.
func RunService(name string, run func(ctx context.Context) error) error {
	return svc.Run(name, &handler{run: run})
}

type handler struct {
	run func(ctx context.Context) error
}

func (h *handler) Execute(_ []string, requests <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	status <- svc.Status{State: svc.StartPending}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- h.run(ctx) }()
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}

	for {
		select {
		case req := <-requests:
			switch req.Cmd {
			case svc.Interrogate:
				status <- req.CurrentStatus
			case svc.Stop, svc.Shutdown:
				status <- svc.Status{State: svc.StopPending}
				cancel()
				select {
				case <-done:
				case <-time.After(15 * time.Second):
				}
				return false, 0
			}
		case err := <-done:
			if err != nil {
				return true, 1
			}
			return false, 0
		}
	}
}

// Install registers the service (automatic start, restart on failure) and starts it. Needs an elevated prompt.
func Install(o Options) error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("cannot open the service manager (run from an elevated Administrator prompt): %w", err)
	}
	defer m.Disconnect()

	if s, err := m.OpenService(o.Name); err == nil {
		s.Close()
		return fmt.Errorf("service %q is already installed; run 'svcmon agent uninstall' first", o.Name)
	}
	s, err := m.CreateService(o.Name, o.Exe, mgr.Config{
		DisplayName: o.Display,
		Description: o.Description,
		StartType:   mgr.StartAutomatic,
	}, o.Args...)
	if err != nil {
		return err
	}
	defer s.Close()

	_ = s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 30 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 60 * time.Second},
	}, 86400)
	return s.Start()
}

// Uninstall stops and removes the service.
func Uninstall(name string) error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("cannot open the service manager (run from an elevated Administrator prompt): %w", err)
	}
	defer m.Disconnect()
	s, err := m.OpenService(name)
	if err != nil {
		if errors.Is(err, windows.ERROR_SERVICE_DOES_NOT_EXIST) {
			return fmt.Errorf("service %q is not installed", name)
		}
		return err
	}
	defer s.Close()

	if st, err := s.Query(); err == nil && st.State != svc.Stopped {
		if _, err := s.Control(svc.Stop); err == nil {
			deadline := time.Now().Add(20 * time.Second)
			for time.Now().Before(deadline) {
				if st, err := s.Query(); err != nil || st.State == svc.Stopped {
					break
				}
				time.Sleep(300 * time.Millisecond)
			}
		}
	}
	return s.Delete()
}
