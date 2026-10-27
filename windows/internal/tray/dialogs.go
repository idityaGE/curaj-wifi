//go:build windows

package tray

import (
	"fmt"
	"os"
	"time"
	"unsafe"

	"curaj-wifi/internal/agent"
	"curaj-wifi/internal/api"
	"curaj-wifi/internal/portal"
)

// Dialogs (plan A.4). MessageBoxW buttons are fixed (OK/Cancel, Yes/No) —
// the copy carries the meaning; mockup button labels map onto these.

func (t *trayApp) api() *api.Client {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.apiCli == nil {
		c, err := api.NewClientFromFiles(api.DefaultDataDir())
		if err != nil {
			return nil
		}
		t.apiCli = c
	}
	return t.apiCli
}

func (t *trayApp) startBusy() {
	t.mu.Lock()
	t.busy = true
	t.mu.Unlock()
}

func (t *trayApp) endBusy() {
	t.mu.Lock()
	t.busy = false
	t.mu.Unlock()
}

// confirmLogout: question dialog, default = Cancel (plan A.4).
func (t *trayApp) confirmLogout() {
	go func() {
		text := "This ends your campus Wi-Fi session.\n\n" +
			"Auto connect stays OFF until you sign in again."
		r := messageBox("Log out of the campus Wi-Fi?", text, mbOKCancel|mbDefBtn2|mbIconQuest)
		if r != 1 { // IDOK
			return
		}
		t.startBusy()
		defer t.endBusy()
		c := t.api()
		if c == nil {
			t.errorDialog("Couldn't log out", "The curaj-wifi service is not reachable.", agent.Snapshot{})
			return
		}
		res, err := c.Logout()
		if err != nil {
			t.errorDialog("Couldn't log out", err.Error(), agent.Snapshot{})
			return
		}
		if !res.OK {
			t.errorDialog("Couldn't log out", res.Msg, agent.Snapshot{LastError: res.Msg})
		}
	}()
}

// confirmClose: destructive, shows name + start time + duration (A.4).
func (t *trayApp) confirmClose(s portal.Session) {
	go func() {
		started := s.StartTime
		if started == "" {
			started = "unknown"
		}
		text := fmt.Sprintf("%s\nStarted %s · Active %s\n\n"+
			"That device will immediately lose internet.",
			s.Name, started, s.Duration)
		r := messageBox("Close this session?", text, mbOKCancel|mbDefBtn2|mbIconWarn)
		if r != 1 {
			return
		}
		t.startBusy()
		defer t.endBusy()
		c := t.api()
		if c == nil {
			t.errorDialog("Couldn't close the session",
				"The curaj-wifi service is not reachable.", agent.Snapshot{})
			return
		}
		res, err := c.CloseSession(s.SesNo)
		switch {
		case err != nil:
			t.errorDialog("Couldn't close the session", err.Error(), agent.Snapshot{})
		case res.OK:
			kind := "session_closed"
			t.balloon("Old session closed — signing in…", "CURAJ Wi-Fi", niifInfo, kind, 10*time.Second, time.Now())
		default:
			t.errorDialog("Couldn't close the session", res.Msg, agent.Snapshot{LastError: res.Msg})
		}
	}()
}

// errorDialog: friendly sentence → what-to-do → technical detail line →
// pointer to the log (A.4; MessageBoxW has no View-log button, so the tip
// replaces it and the menu keeps a View log item).
func (t *trayApp) errorDialog(title, rawErr string, snap agent.Snapshot) {
	head, advice := agent.Friendly(rawErr)
	var b []byte // build the text
	b = append(b, (head + "\n\n" + advice + "\n\n")...)
	if rawErr != "" {
		detail := rawErr
		if len(detail) > 300 {
			detail = detail[:300] + "…"
		}
		b = append(b, ("Detail: " + detail + "\n")...)
		if snap.ErrAt != "" {
			b = append(b, ("Time: " + snap.ErrAt + "\n")...)
		}
		b = append(b, ("\nThe log has more detail — View log in the tray menu.")...)
	}
	messageBox(title, string(b), mbOK|mbIconWarn)
}

// setupAccount launches the elevated interactive setup, as the plan
// prescribes (the tray runs unelevated and must not hold config ACLs).
func (t *trayApp) setupAccount() {
	exe, err := os.Executable()
	if err != nil {
		return
	}
	// "runas" verb = UAC prompt.
	call(procShellExecuteW, 0,
		uintptr(unsafe.Pointer(utf16("runas"))),
		uintptr(unsafe.Pointer(utf16(exe))),
		uintptr(unsafe.Pointer(utf16("config"))),
		0, swShownormal)
	t.balloon("Follow the setup window — the service picks up your account automatically.",
		"CURAJ Wi-Fi", niifInfo, "setup", 10*time.Second, time.Now())
}

func messageBox(title, text string, flags uintptr) int {
	r, _, _ := procMessageBoxW.Call(
		0,
		uintptr(unsafe.Pointer(utf16(text))),
		uintptr(unsafe.Pointer(utf16(title))),
		flags)
	return int(r)
}

// shellOpen opens a path with its default association.
func shellOpen(path string) {
	call(procShellExecuteW, 0,
		uintptr(unsafe.Pointer(utf16("open"))),
		uintptr(unsafe.Pointer(utf16(path))),
		0, 0, swShownormal)
}

// hideConsole: tray/CLI share one console-subsystem exe; the tray must not
// pop a console window (plan risk note).
func hideConsole() {
	h := call(procGetConsoleWindow)
	if h != 0 {
		call(procShowWindow, h, swHide)
	}
}

func fatalBox(err error) {
	messageBox("CURAJ Wi-Fi", "Tray client error:\n"+err.Error(), mbOK|mbIconError)
	os.Exit(1)
}
