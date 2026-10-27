// Package app wires the portable core: config store, portal client, agent
// and API server. The Windows service handler and (for tests/dev) any
// standalone runner both call Start.
package app

import (
	"fmt"

	"curaj-wifi/internal/agent"
	"curaj-wifi/internal/api"
	"curaj-wifi/internal/config"
	"curaj-wifi/internal/portal"
	"curaj-wifi/internal/scrub"
)

// Deps carries the platform bits that differ (logging, IP detection).
type Deps struct {
	Log    *scrub.Logger
	IPFunc func() string // local Wi-Fi IPv4 (nil → no IP display)
}

// App is the running core.
type App struct {
	agent *agent.Agent
	api   *api.Server
	log   *scrub.Logger
}

// cfgStore implements agent.ConfigProvider over config.Store with DPAPI.
type cfgStore struct {
	st *config.Store
	p  config.Protector
	mu chan struct{} // serialise Save (SetAutoConnect) vs Load
}

func newCfgStore(st *config.Store, p config.Protector) *cfgStore {
	return &cfgStore{st: st, p: p, mu: make(chan struct{}, 1)}
}

func (c *cfgStore) Get() (config.Data, error) {
	return c.st.Load(c.p)
}

func (c *cfgStore) SetAutoConnect(enabled bool) error {
	c.mu <- struct{}{}
	defer func() { <-c.mu }()
	d, err := c.st.Load(c.p)
	if err != nil {
		return err
	}
	if d.AutoConnect == enabled {
		return nil
	}
	d.AutoConnect = enabled
	return c.st.Save(d, c.p)
}

// Start builds and starts agent + API server. Returns the running App.
func Start(dataDir string, deps Deps) (*App, error) {
	if deps.Log == nil {
		return nil, fmt.Errorf("app: logger required")
	}
	store := &config.Store{Dir: dataDir}
	protector := newProtector()

	cs := newCfgStore(store, protector)
	if d, err := cs.Get(); err == nil {
		deps.Log.WithSecret(d.Username)
	}

	port := portal.New("", scrubWriter(deps.Log))
	// Portal client binds to the Wi-Fi IP when known; recompute lazily is
	// not supported in v1 — BindIP is fixed at start (risk documented).
	if deps.IPFunc != nil {
		if ip := deps.IPFunc(); ip != "" {
			port.BindIP = ip
		}
	}

	ag := agent.New(port, cs, scrubWriter(deps.Log),
		func(e agent.Event) { deps.Log.Writef("event %s: %s", e.Kind, e.Text) },
		deps.IPFunc)

	// API token: create when missing so the tray can always attach.
	token, err := api.LoadToken(dataDir)
	if err != nil {
		token, err = api.GenerateToken()
		if err != nil {
			return nil, fmt.Errorf("generate token: %w", err)
		}
		if err := api.WriteToken(dataDir, token); err != nil {
			return nil, fmt.Errorf("write token: %w", err)
		}
	}

	srv := api.NewServer(ag, token)
	p, err := srv.Start()
	if err != nil {
		return nil, fmt.Errorf("api server: %w", err)
	}
	if err := api.WritePort(dataDir, p); err != nil {
		deps.Log.Writef("cannot record API port: %v", err)
	}
	if PostPrepare != nil {
		if err := PostPrepare(dataDir); err != nil {
			deps.Log.Writef("cannot set user-read ACLs on token files: %v", err)
		}
	}
	deps.Log.Writef("service core started (api on 127.0.0.1:%d)", p)

	return &App{agent: ag, api: srv, log: deps.Log}, nil
}

// Agent exposes the agent (for the service handler to stop, etc.).
func (a *App) Agent() *agent.Agent { return a.agent }

// logWriter adapts *scrub.Logger to the agent's small logging interface
// (avoids an import cycle direction the other way).
type logIface = interface{ Writef(format string, args ...any) }

// scrubWriter converts a scrubbed logger to portal.Logger.
func scrubWriter(l *scrub.Logger) portal.Logger {
	return func(format string, args ...any) { l.Writef(format, args...) }
}

// Run blocks until stop is closed, driving the agent loop.
func (a *App) Run(stop <-chan struct{}) {
	a.agent.Run(stop)
}

// Stop stops the API server (call after the agent loop returns).
func (a *App) Stop() { a.api.Stop() }

// newProtector is replaced per-platform: DPAPI on Windows.
var newProtector = defaultProtector

// PostPrepare, when set, is called after the token/port files are written
// (Windows uses it to grant Users read on them).
var PostPrepare func(dataDir string) error

func defaultProtector() config.Protector {
	return plainProtector{}
}

type plainProtector struct{}

func (plainProtector) Protect(b []byte) ([]byte, error)  { return append([]byte(nil), b...), nil }
func (plainProtector) Unprotect(b []byte) ([]byte, error) { return append([]byte(nil), b...), nil }
