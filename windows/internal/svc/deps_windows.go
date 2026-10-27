//go:build windows

package svc

import (
	"fmt"
	"net"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"curaj-wifi/internal/config"
	"curaj-wifi/internal/portal"
)

// config_DataDir keeps the import surface small.
func config_DataDir() string { return config.DefaultDir() }

var (
	netshSsidRe = regexp.MustCompile(`(?m)^\s{2,}SSID\s*:\s*(.+)$`)
	netshNameRe = regexp.MustCompile(`(?m)^\s*Name\s*:\s*(.+)$`)
)

// wifiInfo returns (interfaceName, ssid) for the connected WLAN interface,
// best-effort via netsh (localisation may make the labels unparsable; the
// probe is the real ground truth for campus detection).
func wifiInfo() (string, string) {
	out, err := exec.Command("netsh", "wlan", "show", "interfaces").CombinedOutput()
	if err != nil {
		return "", ""
	}
	text := string(out)
	name := firstGroup(netshNameRe, text)
	ssid := ""
	for _, m := range netshSsidRe.FindAllStringSubmatch(text, -1) {
		v := strings.TrimSpace(m[1])
		if v != "" && v != "--" {
			ssid = v
			break
		}
	}
	return strings.TrimSpace(name), strings.TrimSpace(ssid)
}

func firstGroup(re *regexp.Regexp, text string) string {
	if m := re.FindStringSubmatch(text); m != nil {
		return m[1]
	}
	return ""
}

// ifaceIP returns the interface's first IPv4 address.
func ifaceIP(name string) string {
	if name == "" {
		return ""
	}
	iface, err := net.InterfaceByName(name)
	if err != nil {
		return ""
	}
	addrs, err := iface.Addrs()
	if err != nil {
		return ""
	}
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok && ipn.IP.To4() != nil && !ipn.IP.IsLoopback() {
			return ipn.IP.To4().String()
		}
	}
	return ""
}

// udpSourceIP asks the OS which source address it would use to reach the
// portal (no packets are sent).
func udpSourceIP() string {
	d := net.Dialer{Timeout: 2 * time.Second}
	conn, err := d.Dial("udp", net.JoinHostPort(portal.DefaultPortalHost, "80"))
	if err != nil {
		return ""
	}
	defer conn.Close()
	if ua, ok := conn.LocalAddr().(*net.UDPAddr); ok && ua.IP.To4() != nil {
		return ua.IP.To4().String()
	}
	return ""
}

// wifiIP picks the best local IPv4 for source binding:
// 1. the WLAN interface by name (netsh), 2. the default-route source.
func wifiIP() string {
	name, _ := wifiInfo()
	if ip := ifaceIP(name); ip != "" {
		return ip
	}
	return udpSourceIP()
}

// ACL helpers. The data dir is SYSTEM+Administrators only (it holds the
// DPAPI config); the token and port files get a Users read grant because
// the unelevated tray must read them.

const (
	sidSystem        = `*S-1-5-18`
	sidAdministrators = `*S-1-5-32-544`
	sidUsers         = `*S-1-5-32-545`
)

// TightenDataDirACL restricts the data directory to SYSTEM+Administrators.
func TightenDataDirACL(dir string) error {
	return icacls(dir, []string{
		"/inheritance:r",
		"/grant:r", sidSystem + ":(OI)(CI)F",
		"/grant:r", sidAdministrators + ":(OI)(CI)F",
	})
}

// GrantUserRead gives BUILTIN\Users read on a file (token/port files).
func GrantUserRead(path string) error {
	return icacls(path, []string{
		"/inheritance:r",
		"/grant:r", sidSystem + ":F",
		"/grant:r", sidAdministrators + ":F",
		"/grant:r", sidUsers + ":R",
	})
}

func icacls(path string, args []string) error {
	full := append([]string{path}, args...)
	out, err := exec.Command("icacls", full...).CombinedOutput()
	if err != nil {
		return fmt.Errorf("icacls %s: %v: %s", path, err, strings.TrimSpace(string(out)))
	}
	return nil
}

func PrepareUserReadableFiles(dir string) error {
	if err := GrantUserRead(filepath.Join(dir, "api-token.txt")); err != nil {
		return err
	}
	return GrantUserRead(filepath.Join(dir, "port.txt"))
}
