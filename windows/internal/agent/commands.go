package agent

import (
	"time"

	"curaj-wifi/internal/portal"
)

// handle executes a command. Runs on the loop goroutine — this is the ONLY
// place portal traffic other than the tick is issued.
func (a *Agent) handle(cmd Command) {
	switch cmd.Kind {
	case CmdLoginNow:
		// Locked decision: "Sign in now" turns Auto connect back ON.
		a.cfgp.SetAutoConnect(true)
		a.reply(cmd, a.signinNow())

	case CmdTryAgain:
		// Reset the backoff and attempt immediately (auto connect unchanged).
		a.nextAction = time.Time{}
		a.reply(cmd, a.signinNow())

	case CmdLogout:
		a.reply(cmd, a.logoutNow())

	case CmdRefreshSessions:
		a.refreshSessions()
		a.reply(cmd, CmdResult{OK: true})

	case CmdCloseSession:
		a.reply(cmd, a.closeSession(cmd.Sesno))

	default:
		a.reply(cmd, CmdResult{Msg: "unknown command " + cmd.Kind})
	}
}

func (a *Agent) reply(cmd Command, res CmdResult) {
	if cmd.reply != nil {
		cmd.reply <- res
	}
}

func (a *Agent) reloadCreds() portal.Creds {
	cfg, _ := a.cfgp.Get()
	return portal.Creds{Username: cfg.Username, Password: cfg.Password}
}

// signinNow performs detect + login immediately (the "≤3 s feedback" path:
// the state line flips to Signing in… before we block).
func (a *Agent) signinNow() CmdResult {
	cfg, err := a.cfgp.Get()
	if err != nil {
		a.setState(StateNotConfigured, "Set up your account")
		return CmdResult{Msg: "Set up your account first."}
	}
	a.setState(StateAuthenticating, "Signing in… (attempt 1)")
	a.failures = 1

	det := a.portal.Detect()
	switch {
	case det.Down:
		a.setState(StateNoWifi, "Not on campus Wi-Fi")
		a.setErr("campus Wi-Fi is not responding", time.Now())
		a.nextAction = time.Now().Add(downPoll)
		return CmdResult{Msg: "Not on campus Wi-Fi."}

	case det.Online:
		a.setState(StateOnline, onlineDetail(a.snapshotIP()))
		a.failures = 0
		a.nextAction = time.Now().Add(onlinePoll)
		return CmdResult{OK: true, Msg: "Signed in — you're online."}
	}

	res := a.portal.Login(portal.Creds{Username: cfg.Username, Password: cfg.Password}, false, 0)
	switch {
	case res.OK:
		a.setState(StateOnline, onlineDetail(a.snapshotIP()))
		a.failures = 0
		a.nextAction = time.Now().Add(onlinePoll)
		a.emit(EventOnline, "Signed in — you're online.")
		return CmdResult{OK: true, Msg: "Signed in — you're online."}

	case res.SessionLimit:
		a.failures++
		a.onSessionLimit(time.Now(), res)
		return CmdResult{Msg: "Both sessions are in use — close one from Sessions."}

	case res.InProgress:
		a.setState(StateAuthenticating, "Signing in… (waiting for network)")
		delay := 3 * time.Second
		if res.RetryAfter > 0 {
			delay = res.RetryAfter
		}
		a.nextAction = time.Now().Add(delay)
		return CmdResult{Msg: "The network is already signing you in — wait a moment."}

	default:
		a.onLoginFailure(time.Now(), res.Msg)
		head, advice := Friendly(res.Msg)
		return CmdResult{Msg: head + " " + advice}
	}
}

// logoutNow performs the verified logout and turns Auto connect off.
func (a *Agent) logoutNow() CmdResult {
	a.setState(StateLoggingOut, "Logging out…")
	ok, msg := a.portal.Logout(a.reloadCreds())
	a.cfgp.SetAutoConnect(false)
	a.nextAction = time.Now().Add(setupPoll)
	a.setState(StateAutoOff, "Auto connect is off")
	if ok {
		a.setSessions(nil, false)
		a.emit(EventLogout, "Logged out. Auto connect is off.")
		return CmdResult{OK: true, Msg: "Logged out."}
	}
	a.setErr("still online: "+msg, time.Now())
	return CmdResult{Msg: "Couldn't log out: " + msg}
}

// closeSession closes one session, verifies, refreshes the cache and, if we
// were session-limited, immediately retries the login (the one-click fix
// for the flow that needed a manual dance on Android).
func (a *Agent) closeSession(sesno string) CmdResult {
	wasLimited := a.snapshotState() == StateSessionLimit
	a.setBusy(true)
	ok, msg := a.portal.CloseSession(a.reloadCreds(), sesno)
	if ok {
		a.log("closed session %s", sesno)
	} else {
		a.log("closing session %s failed: %s", sesno, msg)
	}
	a.refreshSessions()
	a.setBusy(false)

	res := CmdResult{OK: ok, Msg: msg}
	if ok {
		a.emit(EventSessionClose, "Old session closed — signing in…")
		if wasLimited {
			a.nextAction = time.Time{}
			sub := a.signinNow()
			res.OK = sub.OK
			if sub.OK {
				res.Msg = "Session closed — signed in."
			} else {
				res.Msg = "Session closed, but sign-in still failed."
			}
		} else {
			res.Msg = "Session closed."
		}
	}
	return res
}
