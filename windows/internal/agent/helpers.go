package agent

import (
	"time"

	"curaj-wifi/internal/portal"
)

// Small guarded accessors. Each bumps the snapshot version so /status
// consumers (tray, CLI) see a monotonically versioned state.

func (a *Agent) snapshotState() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.snap.State
}

func (a *Agent) snapshotIP() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.snap.IP
}

func (a *Agent) setIP(ip string) {
	a.mu.Lock()
	if a.snap.IP == ip {
		a.mu.Unlock()
		return
	}
	a.snap.IP = ip
	a.mu.Unlock()
	a.push()
}

func (a *Agent) setErr(msg string, at time.Time) {
	a.mu.Lock()
	a.snap.LastError = msg
	a.snap.ErrAt = at.Format(time.RFC3339)
	a.mu.Unlock()
	a.push()
}

func (a *Agent) setSessions(list []portal.Session, fetched bool) {
	a.mu.Lock()
	a.snap.Sessions = list
	if fetched {
		a.snap.SessionsAt = time.Now().Format(time.RFC3339)
	} else {
		a.snap.SessionsAt = ""
	}
	a.mu.Unlock()
	a.push()
}

func (a *Agent) setBusy(b bool) {
	a.mu.Lock()
	a.snap.SessionsBusy = b
	a.mu.Unlock()
	a.push()
}

// refreshSessions lists sessions best-effort and caches them.
func (a *Agent) refreshSessions() {
	a.setBusy(true)
	defer a.setBusy(false)
	cfg, err := a.cfgp.Get()
	if err != nil {
		a.setSessions(nil, false)
		return
	}
	list, err := a.portal.ListSessions(portal.Creds{Username: cfg.Username, Password: cfg.Password})
	if err != nil {
		a.log("sessions refresh failed: %v", err)
		a.setSessions(nil, false)
		a.setErr(err.Error(), time.Now())
		return
	}
	a.setSessions(list, true)
}
