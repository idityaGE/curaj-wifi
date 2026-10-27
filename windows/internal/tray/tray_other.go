//go:build !windows

// Package tray is Windows-only (native Shell_NotifyIconW/menus). This stub
// keeps the module testable on Linux.
package tray

// Run never runs on non-Windows.
func Run(version string) {}
