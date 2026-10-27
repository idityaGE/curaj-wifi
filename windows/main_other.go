//go:build !windows

package main

import (
	"fmt"
	"os"
)

// Windows builds only (see main.go). The !windows file exists so the module
// still compiles everywhere for tests; internal/ packages are portable.
func main() {
	fmt.Fprintln(os.Stderr, "curaj-wifi: this build is for Windows; see docs/WINDOWS.md")
	os.Exit(1)
}
