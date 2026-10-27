//go:build windows

package tray

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"curaj-wifi/internal/agent"
	"curaj-wifi/internal/api"
	"curaj-wifi/internal/icons"

	"golang.org/x/sys/windows"
)

type trayApp struct {
	version string

	hwnd      windows.HWND
	hinstance windows.Handle
	nid       notifyIconDataW

	iconMu sync.Mutex
	icons  map[string]HICON // tint -> HICON at shell small size

	mu       sync.Mutex
	snap     agent.Snapshot
	haveSnap bool
	busy     bool // a menu command is in flight

	apiCli *api.Client

	balloonMu   sync.Mutex
	balloonLast map[string]time.Time
}

var theTray *trayApp

// Run is the tray entrypoint (user session). Blocks until Exit.
func Run(version string) {
	hideConsole()
	theTray = &trayApp{
		version:     version,
		balloonLast: make(map[string]time.Time),
	}
	panicOnError()
	if err := theTray.run(); err != nil {
		fatalBox(err)
	}
}

func (t *trayApp) run() error {
	t.hinstance = GetModuleHandle()
	className := utf16("curaj-wifi-tray")

	wc := wndClassEx{
		CbSize:        uint32(unsafe.Sizeof(wndClassEx{})),
		LpfnWndProc:   syscall.NewCallback(wndProc),
		HInstance:     t.hinstance,
		LpszClassName: className,
	}
	if call(procRegisterClassExW, uintptr(unsafe.Pointer(&wc))) == 0 {
		return fmt.Errorf("RegisterClassExW failed")
	}

	wndName := utf16("curaj-wifi")
	hwnd := windows.HWND(call(procCreateWindowExW, 0,
		uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(wndName)),
		0, // WS_OVERLAPPED? 0 = plain hidden window
		0, 0, 0, 0, 0, 0, uintptr(t.hinstance), 0))
	if hwnd == 0 {
		return fmt.Errorf("CreateWindowExW failed")
	}
	t.hwnd = hwnd

	if err := t.initIcons(); err != nil {
		return err
	}
	if err := t.addTrayIcon(); err != nil {
		return fmt.Errorf("Shell_NotifyIconW add: %w", err)
	}

	go t.pollLoop()

	// Message pump.
	var msg struct {
		HWnd    windows.HWND
		Message uint32
		WParam  uintptr
		LParam  uintptr
		Time    uint32
		PT      point
		Private uint32
	}
	for {
		r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&msg)), 0, 0, 0)
		if r == 0 || r == 0xFFFFFFFF {
			break // WM_QUIT (or error)
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&msg)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&msg)))
	}
	procShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&t.nid)))
	return nil
}

// wndProc: tray callback messages + quit.
func wndProc(hwnd windows.HWND, msg uint32, wParam, lParam uintptr) uintptr {
	t := theTray
	if t == nil {
		return call(procDefWindowProcW, uintptr(hwnd), uintptr(msg), wParam, lParam)
	}
	switch msg {
	case wmTray:
		switch lParam {
		case wmRButtonUp, wmLButtonUp:
			t.showMenu()
		}
		return 0
	case wmDestroy:
		call(procPostQuitMessage, 0)
		return 0
	}
	return call(procDefWindowProcW, uintptr(hwnd), uintptr(msg), wParam, lParam)
}

// addTrayIcon registers the icon with the shell.
func (t *trayApp) addTrayIcon() error {
	t.nid = notifyIconDataW{
		CbSize:           uint32(unsafe.Sizeof(t.nid)),
		HWnd:             t.hwnd,
		UID:              1,
		UFlags:           nifMessage | nifIcon | nifTip,
		UCallbackMessage: wmTray,
		HIcon:            t.iconFor(agent.StateNotConfigured),
	}
	copyTip(t.nid.SzTip[:], "CURAJ Wi-Fi — starting…")
	r, _, err := procShellNotifyIconW.Call(nimAdd, uintptr(unsafe.Pointer(&t.nid)))
	if r == 0 {
		return err
	}
	return nil
}

// updateIcon re-tints + re-tooltips when the snapshot changed.
func (t *trayApp) updateIcon(snap agent.Snapshot) {
	t.nid.UFlags = nifIcon | nifTip
	t.nid.HIcon = t.iconFor(snap.State)
	copyTip(t.nid.SzTip[:], tooltipFor(snap))
	procShellNotifyIconW.Call(nimModify, uintptr(unsafe.Pointer(&t.nid)))
}

func copyTip(dst []uint16, s string) {
	u, err := windows.UTF16FromString(s)
	if err != nil {
		return
	}
	n := copy(dst, u)
	if n < len(dst) {
		dst[n] = 0
	}
}

// iconFor picks the tinted icon for a state (cached HICONs).
func (t *trayApp) iconFor(state string) HICON {
	tint := tintFor(state)
	t.iconMu.Lock()
	defer t.iconMu.Unlock()
	if h, ok := t.icons[tint]; ok {
		return h
	}
	return t.icons["white"]
}

func tintFor(state string) string {
	switch state {
	case agent.StateOnline:
		return "green"
	case agent.StateAuthenticating, agent.StateSessionLimit:
		return "amber"
	case agent.StateFailed:
		return "red"
	case agent.StateNoWifi:
		return "gray"
	default:
		return "white"
	}
}

func (t *trayApp) initIcons() error {
	size := int(call(procGetSystemMetrics, smCxSmIcon))
	if size <= 0 || size > 64 {
		size = 16
	}
	t.icons = make(map[string]HICON, 5)
	for _, tint := range []string{"green", "amber", "red", "white", "gray"} {
		data, err := icons.Get(tint)
		if err != nil {
			return fmt.Errorf("embedded icon %s: %w", tint, err)
		}
		h, err := loadIconFromICO(data, size)
		if err != nil {
			return fmt.Errorf("icon %s: %w", tint, err)
		}
		t.icons[tint] = h
	}
	return nil
}

// pollLoop pulls /status every 2 s; icon/tooltip/balloon changes are safe
// from a non-window thread. While a menu is open the new state simply
// applies after it closes (menus are modal — the plan's "stale + Refresh"
// pattern; the honest behaviour is documented in A.3).
func (t *trayApp) pollLoop() {
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	var lastVersion uint64
	var lastState string
	for range tick.C {
		if t.apiCli == nil {
			c, err := api.NewClientFromFiles(api.DefaultDataDir())
			if err != nil {
				continue // service not ready yet; retry next tick
			}
			t.apiCli = c
		}
		snap, err := t.apiCli.Status()
		if err != nil {
			continue
		}
		t.mu.Lock()
		t.snap = snap
		t.haveSnap = true
		t.mu.Unlock()

		if snap.Version != lastVersion {
			lastVersion = snap.Version
			t.updateIcon(snap)
		}
		prev := lastState
		lastState = snap.State
		if prev == "" || prev == snap.State {
			continue
		}
		t.onTransition(prev, snap)
	}
}

// onTransition derives throttled balloons from state changes (plan A.5).
func (t *trayApp) onTransition(prev string, snap agent.Snapshot) {
	now := time.Now()
	switch snap.State {
	case agent.StateOnline:
		t.balloon("Signed in — you're online.", "CURAJ Wi-Fi", niifInfo, "online", time.Hour, now)
	case agent.StateSessionLimit:
		t.balloon("Both sessions are in use — close one from the tray.",
			"CURAJ Wi-Fi", niifWarning, "session_limit", 10*time.Minute, now)
	case agent.StateFailed:
		if snap.Attempt >= 3 {
			head, _ := agent.Friendly(snap.LastError)
			t.balloon("Wi-Fi sign-in keeps failing: "+head,
				"CURAJ Wi-Fi", niifError, "failed", 30*time.Minute, now)
		}
	case agent.StateAutoOff:
		if prev == agent.StateLoggingOut {
			t.balloon("Logged out. Auto connect is off.", "CURAJ Wi-Fi", niifInfo, "logout", time.Hour, now)
		}
	case agent.StateNotConfigured:
		t.balloon("Set up your CURAJ account — right-click the tray icon.",
			"CURAJ Wi-Fi", niifInfo, "needs_setup", 1<<40, now)
	}
}

// balloon shows a throttled tray balloon notification.
func (t *trayApp) balloon(text, title string, flags uint32, kind string, throttle time.Duration, now time.Time) {
	t.balloonMu.Lock()
	if last, ok := t.balloonLast[kind]; ok && now.Sub(last) < throttle {
		t.balloonMu.Unlock()
		return
	}
	t.balloonLast[kind] = now
	t.balloonMu.Unlock()

	nid := t.nid
	nid.UFlags = nifInfo
	copyTip(nid.SzInfoTitle[:], title)
	copyTip(nid.SzInfo[:], text)
	nid.DwInfoFlags = flags
	procShellNotifyIconW.Call(nimModify, uintptr(unsafe.Pointer(&nid)))
}

func logFilePath() string {
	pd := os.Getenv("ProgramData")
	if pd == "" {
		pd = `C:\ProgramData`
	}
	return filepath.Join(pd, "curaj-wifi", "curaj-wifi.log")
}
