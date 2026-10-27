//go:build windows

// Command curaj-wifi is one dual-mode exe (plan: service + tray + CLI).
//
//	curaj-wifi.exe                 → service if launched by SCM, else tray
//	curaj-wifi.exe service         → run as Windows service (SCM only)
//	curaj-wifi.exe tray            → the tray client (user session)
//	curaj-wifi.exe install         → elevated: install + start the service
//	curaj-wifi.exe uninstall       → elevated: stop + remove the service
//	curaj-wifi.exe config          → elevated: interactive account setup
//	curaj-wifi.exe status          → CLI view of the service state
//	curaj-wifi.exe login           → "Sign in now" (re-enables Auto connect)
//	curaj-wifi.exe logout          → verified logout
//	curaj-wifi.exe try-again       → reset backoff and attempt now
//	curaj-wifi.exe sessions        → list active sessions (--json for machine)
//	curaj-wifi.exe close <id>      → close one session (verified)
//	curaj-wifi.exe log             → print the tail of the service log
//	curaj-wifi.exe version         → print the version
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"

	"curaj-wifi/internal/agent"
	"curaj-wifi/internal/api"
	"curaj-wifi/internal/config"
	"curaj-wifi/internal/svc"
	"curaj-wifi/internal/tray"
)

var version = "1.0"

func main() {
	args := os.Args[1:]
	cmd := ""
	if len(args) > 0 {
		cmd, args = args[0], args[1:]
	}

	// No args: SCM → service, double-click → tray.
	if cmd == "" {
		if svc.IsWindowsService() {
			cmd = "service"
		} else {
			cmd = "tray"
		}
	}

	switch cmd {
	case "service":
		if err := svc.RunService(); err != nil {
			fail(err)
		}
	case "tray":
		tray.Run(version)
	case "install":
		elevateCheck()
		if err := svc.InstallAll(); err != nil {
			fail(err)
		}
	case "uninstall":
		elevateCheck()
		if err := svc.RemoveAll(); err != nil {
			fail(err)
		}
	case "config":
		elevateCheck()
		runConfig()
	case "status":
		runStatus()
	case "login":
		runCmdSimple("login")
	case "logout":
		runCmdSimple("logout")
	case "try-again":
		runCmdSimple("try-again")
	case "sessions":
		runSessions(args)
	case "close":
		if len(args) != 1 {
			fmt.Fprintln(os.Stderr, "usage: curaj-wifi close <session-id>")
			os.Exit(2)
		}
		runClose(args[0])
	case "log":
		runLog(args)
	case "version":
		fmt.Println("curaj-wifi", version)
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q — try: status, login, logout, sessions, close, log, config, install, uninstall, tray, service, version\n", cmd)
		os.Exit(2)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, "error:", err)
	os.Exit(1)
}

func client() *api.Client {
	c, err := api.NewClientFromFiles(api.DefaultDataDir())
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		fmt.Fprintln(os.Stderr, "The curaj-wifi service is not running or not installed.")
		os.Exit(3)
	}
	return c
}

// elevateCheck refuses silently-confused non-elevated runs.
func elevateCheck() {
	if !isElevated() {
		fmt.Fprintln(os.Stderr, "this command needs administrator rights (run elevated)")
		os.Exit(4)
	}
}

func runStatus() {
	snap, err := client().Status()
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	fmt.Printf("state:       %s\n", snap.State)
	fmt.Printf("detail:      %s\n", snap.Detail)
	if snap.IP != "" {
		fmt.Printf("ip:          %s\n", snap.IP)
	}
	fmt.Printf("auto connect: %v\n", snap.AutoConnect)
	if snap.LastError != "" {
		head, advice := agent.Friendly(snap.LastError)
		fmt.Printf("last error:  %s (%s)\n", snap.ErrAt, snap.LastError)
		fmt.Printf("what to do:  %s %s\n", head, advice)
	}
	if len(snap.Sessions) > 0 {
		fmt.Println("sessions:")
		for _, s := range snap.Sessions {
			fmt.Printf("  %-12s %-19s %-9s %s\n", s.SesNo, s.StartTime, s.Duration, s.AccountID)
		}
	}
}

func runCmdSimple(kind string) {
	res, err := doCmd(kind)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	if res.OK {
		fmt.Println(res.Msg)
		return
	}
	fmt.Fprintln(os.Stderr, res.Msg)
	os.Exit(1)
}

func doCmd(kind string) (agent.CmdResult, error) {
	c := client()
	return c.CallCmdFor(kind)
}

func runSessions(args []string) {
	jsonOut := false
	for _, a := range args {
		if a == "--json" {
			jsonOut = true
		}
	}
	c := client()
	sessions, _, err := c.Sessions(true)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	if jsonOut {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		_ = enc.Encode(sessions)
		return
	}
	if len(sessions) == 0 {
		fmt.Println("no active sessions")
		return
	}
	fmt.Printf("%-12s %-19s %-9s %-10s %s\n", "ID", "STARTED", "DURATION", "TOTAL", "ACCOUNT")
	for _, s := range sessions {
		fmt.Printf("%-12s %-19s %-9s %-10s %s\n", s.SesNo, s.StartTime, s.Duration, s.Total, s.AccountID)
	}
	fmt.Println("\nClose one: curaj-wifi close <ID>")
}

func runClose(sesno string) {
	res, err := client().CloseSession(sesno)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	if res.OK {
		fmt.Println(res.Msg)
		return
	}
	fmt.Fprintln(os.Stderr, res.Msg)
	os.Exit(1)
}

func runLog(args []string) {
	lines := 50
	if len(args) > 0 {
		fmt.Sscanf(args[0], "%d", &lines)
	}
	data, err := os.ReadFile(logPath())
	if err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
	text := string(data)
	ls := strings.Split(strings.TrimRight(text, "\n"), "\n")
	if len(ls) > lines {
		ls = ls[len(ls)-lines:]
	}
	for _, l := range ls {
		fmt.Println(l)
	}
}

func logPath() string {
	return api.DefaultDataDir() + string(os.PathSeparator) + "curaj-wifi.log"
}

// runConfig is the interactive (elevated) account setup.
func runConfig() {
	r := bufio.NewReader(os.Stdin)
	fmt.Println("CURAJ Wi-Fi — account setup")
	fmt.Println("Your university login for the campus portal (phone number / username).")
	username := prompt(r, "Username: ")
	if username == "" {
		fail(fmt.Errorf("username cannot be empty"))
	}
	password := prompt(r, "Password: ")
	if password == "" {
		fail(fmt.Errorf("password cannot be empty"))
	}
	store := &config.Store{Dir: api.DefaultDataDir()}
	if err := store.Save(config.Data{
		Username: username, Password: password, AutoConnect: true,
		SSID: "CURAJ CAMPUS CONNECT",
	}, config.ProtectorLM{}); err != nil {
		fail(err)
	}
	fmt.Println("Saved (encrypted with Windows DPAPI). The service picks it up within a minute;")
	fmt.Println("use `curaj-wifi status` to check.")
}

func prompt(r *bufio.Reader, label string) string {
	fmt.Print(label)
	line, _ := r.ReadString('\n')
	return strings.TrimSpace(line)
}

// isElevated checks for admin rights by touching something only admins can
// open (the PS installer self-elevates before calling us; this is a guard).
func isElevated() bool {
	f, err := os.Open(`\\.\PHYSICALDRIVE0`)
	if err != nil {
		return false
	}
	f.Close()
	return true
}
