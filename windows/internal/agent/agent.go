// Package agent implements the service-side state machine (plan Part B.3).
//
// One goroutine owns ALL portal traffic: the Run loop. API handlers never
// touch the portal — they only Submit commands and read Snapshots.
package agent

import (
	"sort"
	"sync"
	"time"

	"curaj-wifi/internal/config"
	"curaj-wifi/internal/portal"
)

// States (serialised into snapshots for the tray/CLI).
const (
	StateNoWifi         = "no_wifi"
	StateNotConfigured  = "not_configured"
	StateAutoOff        = "auto_connect_off"
	StateOnline         = "online"
	StateAuthenticating = "authenticating"
	StateSessionLimit   = "session_limit"
	StateFailed         = "failed"
	StateLoggingOut     = "logging_out"
)

// Portal is the single-writer portal surface the agent uses. *portal.Client
// satisfies it; tests substitute a fake.
type Portal interface {
	Detect() portal.Detect
	Login(creds portal.Creds, force bool, depth int) portal.Result
	Logout(creds portal.Creds) (bool, string)
	ListSessions(creds portal.Creds) ([]portal.Session, error)
	CloseSession(creds portal.Creds, sesno string) (bool, string)
}

// ConfigProvider re-reads the small config file every tick so setup takes
// effect without a service restart.
type ConfigProvider interface {
	Get() (config.Data, error)
	SetAutoConnect(enabled bool) error
}

// Event kinds pushed to the tray via the event hook.
const (
	EventOnline       = "online"
	EventLogout       = "logout"
	EventSessionLimit = "session_limit"
	EventFailed       = "failed"
	EventNeedsSetup   = "needs_setup"
	EventSessionClose = "session_closed"
	EventEventFailMsg = "fail_msg"
)

type Event struct {
	Kind string
	Text string
}

type EventHook func(Event)

// Command kinds.
const (
	CmdLoginNow        = "login_now"   // "Sign in now" — re-enables Auto connect
	CmdTryAgain        = "try_again"   // "Try again" — resets backoff only
	CmdLogout          = "logout"
	CmdRefreshSessions = "refresh_sessions"
	CmdCloseSession    = "close_session"
)

// Command is posted by API handlers; the agent loop executes it.
type Command struct {
	Kind  string
	Sesno string

	// reply is closed/used internally by Submit.
	reply chan CmdResult
}

// CmdResult is the outcome of a command, awaited by the API handler so the
// tray gets truthful feedback within the 3 s contract (or busy indication).
type CmdResult struct {
	OK  bool
	Msg string
}

// Snapshot is the versioned state the tray and CLI render.
type Snapshot struct {
	Version   uint64 `json:"version"`
	State     string `json:"state"`
	Detail    string `json:"detail"`
	IP        string `json:"ip,omitempty"`
	Attempt   int    `json:"attempt"`
	RetryIn   int    `json:"retry_in_sec"` // seconds until next retry (0 = none)
	LastError string `json:"last_error,omitempty"`
	ErrAt     string `json:"last_error_time,omitempty"`
	StateSince string `json:"state_since,omitempty"`

	AutoConnect bool   `json:"auto_connect"`
	Configured  bool   `json:"configured"`
	SSID        string `json:"ssid"`

	Sessions      []portal.Session `json:"sessions,omitempty"`
	SessionsAt    string           `json:"sessions_fetched_at,omitempty"`
	SessionsBusy  bool             `json:"sessions_busy"`

	LogFile string `json:"log_file,omitempty"`
}

// Agent is the single-writer loop.
type Agent struct {
	portal Portal
	cfgp   ConfigProvider
	log    func(format string, args ...any) // pre-scrubbed logger (scrub.Logger)
	events EventHook
	ipFunc func() string // local Wi-Fi IPv4 (windows impl); may be nil

	mu   sync.Mutex
	snap Snapshot

	cmds chan Command

	// loop-internal (owned by the run goroutine)
	failures     int
	nextAction   time.Time
	limitEvents  time.Time // last session_limit event sent
	failEvents   time.Time // last persistent-failure event sent
	setupNotif   bool
	creds        portal.Creds
	autoConnect  bool
	cfgErr       error
}

// New wires the agent. events may be nil; log must be a scrubbed writer.
func New(p Portal, cfgp ConfigProvider, log func(format string, args ...any),
	events EventHook, ipFunc func() string) *Agent {
	if log == nil {
		log = func(string, ...any) {}
	}
	return &Agent{
		portal: p,
		cfgp:   cfgp,
		log:    log,
		events: events,
		ipFunc: ipFunc,
		cmds:   make(chan Command, 16),
	}
}

// Submit posts a command and returns the reply channel (result arrives
// after the loop handles it — handlers wait with a timeout).
func (a *Agent) Submit(kind, sesno string) <-chan CmdResult {
	cmd := Command{Kind: kind, Sesno: sesno, reply: make(chan CmdResult, 1)}
	a.cmds <- cmd
	return cmd.reply
}

// Snapshot copies the current state.
func (a *Agent) Snapshot() Snapshot {
	a.mu.Lock()
	defer a.mu.Unlock()
	s := a.snap
	if len(a.snap.Sessions) > 0 {
		s.Sessions = make([]portal.Session, len(a.snap.Sessions))
		copy(s.Sessions, a.snap.Sessions)
	}
	sortSessions(s.Sessions)
	return s
}

func sortSessions(list []portal.Session) {
	sort.Slice(list, func(i, j int) bool { return list[i].SesNo < list[j].SesNo })
}

func (a *Agent) push() {
	a.mu.Lock()
	a.snap.Version++
	a.mu.Unlock()
}

func (a *Agent) setState(state, detail string) {
	a.mu.Lock()
	if a.snap.State == state && a.snap.Detail == detail {
		a.mu.Unlock()
		return
	}
	a.snap.State = state
	a.snap.Detail = detail
	a.snap.StateSince = time.Now().Format(time.RFC3339)
	a.mu.Unlock()
	a.push()
}

func (a *Agent) setDetail(detail string) {
	a.mu.Lock()
	a.snap.Detail = detail
	a.mu.Unlock()
	a.push()
}

func (a *Agent) emit(kind, text string) {
	if a.events != nil {
		a.events(Event{Kind: kind, Text: text})
	}
}

// Run drives the loop until ctx is cancelled. 1 s tick; portal work happens
// inline (single writer); the 1 s ticker coalesces while we work.
func (a *Agent) Run(stop <-chan struct{}) {
	tick := time.NewTicker(time.Second)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return
		case cmd := <-a.cmds:
			a.handle(cmd)
		case <-tick.C:
			a.tick()
		}
	}
}
