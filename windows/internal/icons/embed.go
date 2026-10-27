// Package icons holds the state-tinted tray icons (plan A.1): one Wi-Fi
// glyph, five tints (green/amber/red/white/gray), generated at build time
// by tools/genicons and embedded here.
package icons

import (
	"embed"
	"fmt"
)

//go:embed *.ico
var fs embed.FS

// Tints available.
var tints = []string{"green", "amber", "red", "white", "gray"}

// Get returns the .ico bytes for a tint.
func Get(tint string) ([]byte, error) {
	if !valid(tint) {
		return nil, fmt.Errorf("unknown icon tint %q", tint)
	}
	return fs.ReadFile(tint + ".ico")
}

// Tints lists the embedded tints (used by the generator check).
func Tints() []string { return append([]string(nil), tints...) }

func valid(t string) bool {
	for _, v := range tints {
		if v == t {
			return true
		}
	}
	return false
}
