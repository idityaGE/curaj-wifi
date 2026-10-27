//go:build windows

// Package svc hosts the Windows service wrapper and install/uninstall
// helpers (plan B.2: service runs at boot, survives logoff, single writer
// of portal traffic; the tray is only a view + command poster).
package svc

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	"curaj-wifi/internal/app"
	"curaj-wifi/internal/scrub"

	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

// ServiceName is the SCM name.
const ServiceName = "curaj-wifi"

// dirs holds all filesystem paths (plan: C:\ProgramData\curaj-wifi\).
type dirs struct {
	Root   string
	Config string
	Log    string
	Token  string
	Port   string
}

func dataDirs() dirs {
	root := config_DataDir()
	return dirs{
		Root:   root,
		Config: root, // config.json lives directly in Root
		Log:    root, // curaj-wifi.log next to config
		Token:  root,
		Port:   root,
	}
}

// Service implements svc.Handler.
type Service struct{}

func (Service) Execute(args []string, r <-chan svc.ChangeRequest, s chan<- svc.Status) (bool, uint32) {
	const accepts = svc.AcceptStop | svc.AcceptShutdown
	s <- svc.Status{State: svc.StartPending}

	d := dataDirs()
	log, err := scrub.NewFile(filepath.Join(d.Log, "curaj-wifi.log"), 5<<20, false)
	if err != nil {
		return false, 1
	}
	log.Writef("service starting (SCM)")

	stop := make(chan struct{})
	a, err := app.Start(d.Root, app.Deps{Log: log, IPFunc: wifiIP})
	if err != nil {
		log.Writef("service start failed: %v", err)
		return false, 2
	}
	go a.Run(stop)

	s <- svc.Status{State: svc.Running, Accepts: accepts}
	log.Writef("service running")

	for c := range r {
		switch c.Cmd {
		case svc.Interrogate:
			s <- c.CurrentStatus
		case svc.Stop, svc.Shutdown:
			s <- svc.Status{State: svc.StopPending}
			close(stop)
			a.Stop()
			log.Writef("service stopped")
			return false, 0
		}
	}
	return false, 0
}

// RunService is the `service` mode entry: blocks until SCM says stop.
func RunService() error {
	return svc.Run(ServiceName, Service{})
}

// IsWindowsService reports whether SCM launched us.
func IsWindowsService() bool {
	ok, _ := svc.IsWindowsService()
	return ok
}

// InstallAll creates the service (auto start, restart-on-crash recovery),
// prepares data-dir ACLs and starts the service.
func InstallAll() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	exe, err = filepath.Abs(exe)
	if err != nil {
		return err
	}
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("open SCM (run elevated): %w", err)
	}
	defer m.Disconnect()

	// Idempotent: remove a previous instance first.
	if s, err := m.OpenService(ServiceName); err == nil {
		s.Delete()
		s.Close()
		time.Sleep(500 * time.Millisecond)
	}

	svcCfg := mgr.Config{
		DisplayName:  "CURAJ Wi-Fi client",
		Description:  "Automatic sign-in for the CURAJ CAMPUS CONNECT portal. Runs at boot; the tray icon is just a view.",
		StartType:    mgr.StartAutomatic,
		ErrorControl: mgr.ErrorNormal,
	}
	s, err := m.CreateService(ServiceName, exe, svcCfg)
	if err != nil {
		return fmt.Errorf("create service: %w", err)
	}
	defer s.Close()

	// Restart on crash: quick, then slower, reset after a day.
	if err := s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 30 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 60 * time.Second},
	}, 86400); err != nil {
		fmt.Fprintf(os.Stderr, "warning: recovery actions not set: %v\n", err)
	}

	d := dataDirs()
	if err := os.MkdirAll(d.Root, 0o750); err != nil {
		return err
	}
	if err := TightenDataDirACL(d.Root); err != nil {
		fmt.Fprintf(os.Stderr, "warning: ACL tighten failed: %v\n", err)
	}

	if err := s.Start(); err != nil {
		return fmt.Errorf("start service: %w", err)
	}
	fmt.Println("service installed and started:", ServiceName)
	return nil
}

// RemoveAll stops and deletes the service.
func RemoveAll() error {
	m, err := mgr.Connect()
	if err != nil {
		return fmt.Errorf("open SCM (run elevated): %w", err)
	}
	defer m.Disconnect()
	s, err := m.OpenService(ServiceName)
	if err != nil {
		fmt.Println("service not installed; nothing to remove")
		return nil
	}
	defer s.Close()
	if st, err := s.Query(); err == nil && st.State != svc.Stopped {
		if _, err := s.Control(svc.Stop); err != nil {
			fmt.Fprintf(os.Stderr, "warning: stop failed: %v\n", err)
		}
		for i := 0; i < 50; i++ {
			time.Sleep(200 * time.Millisecond)
			if st, err := s.Query(); err == nil && st.State == svc.Stopped {
				break
			}
		}
	}
	if err := s.Delete(); err != nil {
		return err
	}
	fmt.Println("service removed")
	return nil
}

// Status prints the service state (used by install.ps1 verification).
func Status() (string, error) {
	m, err := mgr.Connect()
	if err != nil {
		return "", err
	}
	defer m.Disconnect()
	s, err := m.OpenService(ServiceName)
	if err != nil {
		return "not installed", nil
	}
	defer s.Close()
	st, err := s.Query()
	if err != nil {
		return "", err
	}
	switch st.State {
	case svc.Stopped:
		return "stopped", nil
	case svc.Running:
		return "running", nil
	case svc.StartPending:
		return "starting", nil
	case svc.StopPending:
		return "stopping", nil
	default:
		return fmt.Sprintf("state %d", st.State), nil
	}
}
