//go:build windows

package tray

import (
	"fmt"
	"strings"
	"unsafe"

	"curaj-wifi/internal/agent"
	"curaj-wifi/internal/portal"

	"golang.org/x/sys/windows"
)

// Menu item IDs.
const (
	idTitle = 1 // bold, inert
	idStatus = 2 // bold, inert (the always-truthful status line)

	idLogout = 10
	idSignin = 11
	idTryAgain = 12
	idLastError = 13
	idSetup = 14
	idRefresh = 15
	idViewLog = 16
	idExit = 17

	idSessionRow = 100 // + index, capped at 2 (protocol max)
)

// tooltipFor implements plan A.1's per-state tooltips.
func tooltipFor(snap agent.Snapshot) string {
	prefix := "CURAJ Wi-Fi — "
	switch snap.State {
	case agent.StateOnline:
		if snap.IP != "" {
			return prefix + "Online · " + snap.IP
		}
		return prefix + "Online"
	case agent.StateAuthenticating:
		if snap.Attempt > 0 {
			return fmt.Sprintf("%sSigning in… (attempt %d)", prefix, snap.Attempt)
		}
		return prefix + "Signing in…"
	case agent.StateSessionLimit:
		n := sessionCount(snap)
		return fmt.Sprintf("%s%d sessions active — close one", prefix, n)
	case agent.StateFailed:
		head, _ := agent.Friendly(snap.LastError)
		head = strings.TrimSuffix(head, ".")
		return prefix + head
	case agent.StateAutoOff:
		return prefix + "Auto connect is off"
	case agent.StateNotConfigured:
		return prefix + "Set up your account"
	case agent.StateNoWifi:
		return prefix + "Not on campus Wi-Fi"
	default:
		return prefix + snap.Detail
	}
}

func sessionCount(snap agent.Snapshot) int {
	if n := len(snap.Sessions); n > 0 {
		return n
	}
	return 2
}

// buildMenu renders the plan A.2 per-state menu. The skeleton is constant;
// items appear/disappear. Win32 menus are modal and don't repaint live, so
// the snapshot the menu was built from is what the user acts on — hence the
// visible "Refresh" item everywhere sessions appear.
func (t *trayApp) buildMenu(snap agent.Snapshot, have bool) HMENU {
	m := HMENU(call(procCreatePopupMenu))
	if m == 0 {
		return 0
	}
	b := &menuBuilder{h: m}

	// Title + status line (bold via SetMenuDefaultItem on the title).
	b.item(idTitle, "CURAJ Wi-Fi", mfGrayed)
	statusText := "Starting…"
	if have {
		statusText = statusLineFor(snap)
	}
	b.item(idStatus, statusText, mfGrayed)
	b.sep()

	if have {
		switch snap.State {
		case agent.StateOnline:
			b.item(idLogout, "Log out…", mfString)
			b.sep()
			t.sessionsSubmenu(b, snap)
			b.sep()
			b.item(0, "Auto connect\t✓ On", mfGrayed)
			b.sep()
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)

		case agent.StateAuthenticating:
			t.sessionsSubmenu(b, snap)
			b.sep()
			b.item(0, "Auto connect\t✓ On", mfGrayed)
			b.sep()
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)

		case agent.StateSessionLimit:
			t.sessionsSubmenu(b, snap)
			b.sep()
			b.item(idTryAgain, "Try again", t.disabledIf(t.busy))
			b.item(0, "Auto connect\t✓ On", mfGrayed)
			b.sep()
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)

		case agent.StateFailed:
			b.item(idTryAgain, "Try again", t.disabledIf(t.busy))
			b.item(idLastError, "Last error…", mfString)
			b.sep()
			t.sessionsSubmenu(b, snap)
			b.sep()
			b.item(0, "Auto connect\t✓ On", mfGrayed)
			b.sep()
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)

		case agent.StateAutoOff:
			b.item(idSignin, "Sign in now", t.disabledIf(t.busy))
			b.sep()
			t.sessionsSubmenu(b, snap)
			b.sep()
			b.item(0, "Auto connect\t✗ Off", mfGrayed)
			b.sep()
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)

		case agent.StateNotConfigured:
			b.item(idSetup, "Set up account…", mfString)
			b.sep()
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)

		case agent.StateNoWifi:
			b.item(0, "Auto connect\t✓ On", mfGrayed)
			b.sep()
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)

		default:
			b.item(idViewLog, "View log", mfString)
			b.item(idExit, "Exit", mfString)
		}
	} else {
		b.item(idViewLog, "View log", mfString)
		b.item(idExit, "Exit", mfString)
	}

	// Bold the title.
	call(procSetMenuDefaultItem, uintptr(m), 0, 1)
	return m
}

type menuBuilder struct {
	h   HMENU
	sepAdded bool
}

func (b *menuBuilder) item(id int, text string, flags uint32) {
	t16, _ := windows.UTF16FromString(text)
	call(procAppendMenuW, uintptr(b.h), uintptr(flags|mfString), uintptr(id),
		uintptr(unsafe.Pointer(&t16[0])))
	b.sepAdded = false
}

func (b *menuBuilder) sep() {
	if b.sepAdded {
		return // no double separators
	}
	call(procAppendMenuW, uintptr(b.h), uintptr(mfSeparator), 0, 0)
	b.sepAdded = true
}

func (t *trayApp) disabledIf(busy bool) uint32 {
	if busy {
		return mfDisabled
	}
	return mfString
}

// statusLineFor is the one truthful status line (plan A.6 rule 6): it
// reflects the service's last verified state.
func statusLineFor(snap agent.Snapshot) string {
	switch snap.State {
	case agent.StateOnline:
		if snap.IP != "" {
			return "● Online · " + snap.IP
		}
		return "● Online"
	case agent.StateAuthenticating:
		if snap.Attempt > 1 {
			return fmt.Sprintf("● Signing in… (attempt %d)", snap.Attempt)
		}
		return "● Signing in…"
	case agent.StateSessionLimit:
		return fmt.Sprintf("● %d sessions active — close one", sessionCount(snap))
	case agent.StateFailed:
		if snap.RetryIn > 0 {
			return fmt.Sprintf("● Couldn't sign in — retrying in %s", humanShort(snap.RetryIn))
		}
		return "● Couldn't sign in"
	case agent.StateAutoOff:
		return "○ Auto connect is off"
	case agent.StateNotConfigured:
		return "○ Set up your account"
	case agent.StateNoWifi:
		return "· Not on campus Wi-Fi"
	case agent.StateLoggingOut:
		return "● Logging out…"
	default:
		return snap.Detail
	}
}

func humanShort(seconds int) string {
	if seconds < 60 {
		return fmt.Sprintf("%ds", seconds)
	}
	m := seconds / 60
	if m < 2 {
		return "1 min"
	}
	return fmt.Sprintf("%d min", m)
}

// sessionsSubmenu builds Sessions ▸ (rows are click-to-close), per A.3.
func (t *trayApp) sessionsSubmenu(b *menuBuilder, snap agent.Snapshot) {
	label := fmt.Sprintf("Sessions (%d)", len(snap.Sessions))
	sub := HMENU(call(procCreatePopupMenu))
	if sub == 0 {
		return
	}
	sb := &menuBuilder{h: sub}

	switch {
	case t.busySnap(snap):
		sb.item(0, "Loading…", mfGrayed)
		sb.item(idRefresh, "Refresh", mfString)
	case len(snap.Sessions) == 0:
		sb.item(0, "(no sessions)", mfGrayed)
		sb.item(idRefresh, "Refresh", mfString)
	default:
		for i, s := range snap.Sessions {
			if i >= 2 { // protocol max: 2 sessions
				break
			}
			sb.item(idSessionRow+i, rowText(s), mfString)
		}
		sb.item(idRefresh, "Refresh", mfString)
	}
	call(procAppendMenuW, uintptr(b.h), uintptr(mfPopup|mfString), uintptr(sub),
		uintptr(unsafe.Pointer(utf16(label))))
}

func (t *trayApp) busySnap(snap agent.Snapshot) bool {
	return snap.SessionsBusy || t.busy
}

// rowText: name · duration (the human anchor).
func rowText(s portal.Session) string {
	name := s.Name
	if name == "" {
		name = s.AccountID
	}
	dur := s.Duration
	if dur == "" && s.StartTime != "" {
		dur = "since " + s.StartTime
	}
	return fmt.Sprintf("%s · %s", name, dur)
}

// showMenu builds + tracks the menu, then dispatches the chosen item
// against the snapshot the menu was built from (stale + Refresh pattern).
func (t *trayApp) showMenu() {
	t.mu.Lock()
	snap, have := t.snap, t.haveSnap
	t.mu.Unlock()

	menu := t.buildMenu(snap, have)
	if menu == 0 {
		return
	}
	defer call(procDestroyMenu, uintptr(menu))

	var pt point
	call(procGetCursorPos, uintptr(unsafe.Pointer(&pt)))
	// Without SetForegroundWindow the menu ignores outside clicks.
	call(procSetForegroundWindow, uintptr(t.hwnd))
	id := call(procTrackPopupMenuEx, uintptr(menu),
		uintptr(tpmReturnCmd|tpmRightButton|tpmNonotify),
		uintptr(pt.X), uintptr(pt.Y), uintptr(t.hwnd), 0)
	// Let the shell regain focus so re-opening works.
	procPostMessageW.Call(uintptr(t.hwnd), wmNull, 0, 0)

	t.dispatch(int(id), snap)
}

// dispatch runs the chosen menu item. Actions that talk to the service run
// in a goroutine (menus must not block the pump); dialogs are shown from
// the action goroutine when the result arrives.
func (t *trayApp) dispatch(id int, snap agent.Snapshot) {
	if id == 0 {
		return
	}
	switch id {
	case idLogout:
		t.confirmLogout()
	case idSignin:
		t.startBusy()
		go func() {
			defer t.endBusy()
			res, err := t.api().LoginNow()
			if err == nil && !res.OK {
				t.errorDialog("Couldn't sign in", res.Msg, snap)
			} else if err != nil {
				t.errorDialog("Couldn't sign in", err.Error(), snap)
			}
		}()
	case idTryAgain:
		t.startBusy()
		go func() {
			defer t.endBusy()
			res, err := t.api().TryAgain()
			if err == nil && !res.OK {
				t.errorDialog("Couldn't sign in", res.Msg, snap)
			} else if err != nil {
				t.errorDialog("Couldn't sign in", err.Error(), snap)
			}
		}()
	case idLastError:
		t.errorDialog("Last error", snap.LastError, snap)
	case idRefresh:
		t.startBusy()
		go func() {
			defer t.endBusy()
			_ = t.api().RefreshSessions()
		}()
	case idSetup:
		t.setupAccount()
	case idViewLog:
		shellOpen(logFilePath())
	case idExit:
		call(procDestroyWindow, uintptr(t.hwnd))
	}
	if id >= idSessionRow && id < idSessionRow+2 && id-idSessionRow < len(snap.Sessions) {
		t.confirmClose(snap.Sessions[id-idSessionRow])
	}
}
