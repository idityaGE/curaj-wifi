//go:build windows

package app

import (
	"fmt"
	"os/exec"
	"path/filepath"

	"curaj-wifi/internal/config"
)

// On Windows the production protector is DPAPI with LocalMachine scope:
// the SYSTEM service and the elevated setup both need access across user
// accounts (plan B.2). Never plaintext.
func init() {
	newProtector = func() config.Protector { return config.ProtectorLM{} }

	// Token/port files must be readable by the unelevated tray (Users:R).
	PostPrepare = func(dir string) error {
		for _, name := range []string{"api-token.txt", "port.txt"} {
			if err := grantUserRead(filepath.Join(dir, name)); err != nil {
				return err
			}
		}
		return nil
	}
}

// grantUserRead: SYSTEM+Administrators full, Users read. SIDs are used so
// localised Windows group names cannot break it.
func grantUserRead(path string) error {
	args := []string{
		path,
		"/inheritance:r",
		"/grant:r", "*S-1-5-18:F",      // SYSTEM
		"/grant:r", "*S-1-5-32-544:F",  // Administrators
		"/grant:r", "*S-1-5-32-545:R",  // Users
	}
	out, err := exec.Command("icacls", args...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("icacls %s: %v: %s", path, err, out)
	}
	return nil
}
