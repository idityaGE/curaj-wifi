//go:build windows

// Package tray implements the plan Part A GUI: tray icon + native menus +
// native MessageBox dialogs, no window framework. Everything is thin Win32
// via golang.org/x/sys — the service owns the network; the tray is a view.
package tray

import (
	"fmt"

	"golang.org/x/sys/windows"
)

// Handle types not provided by x/sys/windows.
type (
	HICON   = windows.Handle
	HBITMAP = windows.Handle
	HMENU   = windows.Handle
)

var (
	modUser32   = windows.NewLazySystemDLL("user32.dll")
	modShell32  = windows.NewLazySystemDLL("shell32.dll")
	modGdi32    = windows.NewLazySystemDLL("gdi32.dll")
	modKernel32 = windows.NewLazySystemDLL("kernel32.dll")

	procRegisterClassExW     = modUser32.NewProc("RegisterClassExW")
	procCreateWindowExW      = modUser32.NewProc("CreateWindowExW")
	procDefWindowProcW       = modUser32.NewProc("DefWindowProcW")
	procGetMessageW          = modUser32.NewProc("GetMessageW")
	procTranslateMessage     = modUser32.NewProc("TranslateMessage")
	procDispatchMessageW     = modUser32.NewProc("DispatchMessageW")
	procPostMessageW         = modUser32.NewProc("PostMessageW")
	procPostQuitMessage      = modUser32.NewProc("PostQuitMessage")
	procSetForegroundWindow  = modUser32.NewProc("SetForegroundWindow")
	procTrackPopupMenuEx     = modUser32.NewProc("TrackPopupMenuEx")
	procCreatePopupMenu      = modUser32.NewProc("CreatePopupMenu")
	procAppendMenuW          = modUser32.NewProc("AppendMenuW")
	procDestroyMenu          = modUser32.NewProc("DestroyMenu")
	procSetMenuDefaultItem   = modUser32.NewProc("SetMenuDefaultItem")
	procDestroyWindow        = modUser32.NewProc("DestroyWindow")
	procMessageBoxW          = modUser32.NewProc("MessageBoxW")
	procGetCursorPos         = modUser32.NewProc("GetCursorPos")
	procShowWindow           = modUser32.NewProc("ShowWindow")
	procGetSystemMetrics     = modUser32.NewProc("GetSystemMetrics")
	procCreateIconIndirect   = modUser32.NewProc("CreateIconIndirect")
	procDestroyIcon          = modUser32.NewProc("DestroyIcon")
	procGetDC                = modUser32.NewProc("GetDC")
	procReleaseDC            = modUser32.NewProc("ReleaseDC")
	procCreateDIBSection     = modGdi32.NewProc("CreateDIBSection")
	procDeleteObject         = modGdi32.NewProc("DeleteObject")
	procGetConsoleWindow     = modKernel32.NewProc("GetConsoleWindow")
	procShellNotifyIconW     = modShell32.NewProc("Shell_NotifyIconW")
	procShellExecuteW        = modShell32.NewProc("ShellExecuteW")
)

const (
	// WM_APP tray callback.
	wmTray = 0x8000 + 1 // WM_APP+1

	wmRButtonUp   = 0x0205
	wmLButtonUp   = 0x0204
	wmCommand     = 0x0111
	wmNull        = 0x0000
	wmClose       = 0x0010
	wmDestroy     = 0x0002

	nimAdd    = 0
	nimModify = 1
	nimDelete = 2

	nifMessage = 0x0001
	nifIcon    = 0x0002
	nifTip     = 0x0004
	nifInfo    = 0x0010

	niifInfo    = 1
	niifWarning = 2
	niifError   = 3

	swHide      = 0
	swShownormal = 1

	// Menu flags.
	mfString   = 0x0000
	mfPopup    = 0x0010
	mfSeparator = 0x0800
	mfGrayed   = 0x0001
	mfDisabled = 0x0002
	mfChecked  = 0x0008

	tpmReturnCmd  = 0x0100
	tpmRightButton = 0x0002
	tpmNonotify   = 0x0080

	mbOK        = 0x0000
	mbOKCancel  = 0x0001
	mbYesNo     = 0x0004
	mbDefBtn2   = 0x000100
	mbIconWarn  = 0x000030
	mbIconQuest = 0x000020
	mbIconError = 0x000010

	smCxSmIcon = 49
)

type point struct{ X, Y int32 }

type notifyIconDataW struct {
	CbSize   uint32
	HWnd     windows.HWND
	UID      uint32
	UFlags   uint32
	UCallbackMessage uint32
	HIcon    HICON
	SzTip    [128]uint16
	DwState      uint32
	DwStateMask  uint32
	SzInfo       [256]uint16
	UVersion     uint32
	SzInfoTitle  [64]uint16
	DwInfoFlags  uint32
	GuidItem     windows.GUID
	HBalloonIcon HICON
}

type wndClassEx struct {
	CbSize        uint32
	Style         uint32
	LpfnWndProc   uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     windows.Handle
	HIcon         HICON
	HCursor       HICON
	HbrBackground windows.Handle
	LpszMenuName  *uint16
	LpszClassName *uint16
	HIconSm       HICON
}

type bitmapInfoHeader struct {
	Size          uint32
	Width         int32
	Height        int32
	Planes        uint16
	BitCount      uint16
	Compression   uint32
	SizeImage     uint32
	XPPM          int32
	YPPM          int32
	ClrUsed       uint32
	ClrImportant  uint32
}

type iconInfo struct {
	Icon     int32 // BOOL
	XHotspot uint32
	YHotspot uint32
	HbmMask  HBITMAP
	HbmColor HBITMAP
}

func utf16(s string) *uint16 { p, _ := windows.UTF16PtrFromString(s); return p }

func call(proc *windows.LazyProc, args ...uintptr) uintptr {
	r, _, _ := proc.Call(args...)
	return r
}

func panicOnError() {
	for _, m := range []*windows.LazyDLL{modUser32, modShell32, modGdi32, modKernel32} {
		if err := m.Load(); err != nil {
			panic(fmt.Errorf("tray: %w", err))
		}
	}
}

// GetModuleHandle returns our module handle.
func GetModuleHandle() windows.Handle {
	h, _, _ := modKernel32.NewProc("GetModuleHandleW").Call(0)
	return windows.Handle(h)
}
