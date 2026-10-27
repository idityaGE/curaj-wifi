//go:build !windows

// Package svc is Windows-only. This stub keeps the module testable on Linux.
package svc

// RunService never runs on non-Windows.
func RunService() error { return nil }
