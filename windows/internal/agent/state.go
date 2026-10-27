package agent

import (
	"fmt"
	"time"

	"curaj-wifi/internal/config"
	"curaj-wifi/internal/portal"
)

// Tick cadences and backoff (plan B.3).
const (
	onlinePoll  = 60 * time.Second // catch portal TTL drops → silent re-login
	downPoll    = 30 * time.Second // notice campus Wi-Fi appearing
	setupPoll   = 60 * time.Second
	backoffBase = 20 * time.Second
	backoffCap  = 10 * time.Minute
)

// tick runs one state-machine step.
func (a *Agent) tick() {
	now := time.Now()

	// Config re-read every tick (cheap, small file) so setup takes effect
	// without a service restart.
	cfg, err := a.cfgp.Get()
	a.mu.Lock()
	a.creds = portal.Creds{Username: cfg.Username, Password: cfg.Password}
	a.autoConnect = cfg.AutoConnect
	a.mu.Unlock()

	if err != nil {
		a.setState(StateNotConfigured, "Set up your account")
		a.nextAction = now.Add(setupPoll)
		if !a.setupNotif {
			a.setupNotif = true
			a.emit(EventNeedsSetup, "Set up your CURAJ account — right-click the tray icon.")
		}
		return
	}
	a.setupNotif = false

	if a.snapshotState() == StateLoggingOut {
		return // a logout command is executing
	}

	a.updateRetryIn(now)
	if now.Before(a.nextAction) {
		return
	}

	det := a.portal.Detect()
	if a.ipFunc != nil {
		if ip := a.ipFunc(); ip != "" {
			a.setIP(ip)
		}
	}

	switch {
	case det.Down:
		a.setState(StateNoWifi, "Not on campus Wi-Fi")
		a.nextAction = now.Add(downPoll)

	case det.Online:
		a.enterOnline()

	default: // captive: a portal page was served
		if !a.autoConnect {
			a.setState(StateAutoOff, "Auto connect is off")
			a.nextAction = now.Add(setupPoll)
			return
		}
		a.attemptLogin(now, cfg)
	}
}

// updateRetryIn publishes the countdown shown by Failed/SessionLimit
// status lines ("Couldn't sign in — retrying in 4 min").
func (a *Agent) updateRetryIn(now time.Time) {
	want := 0
	switch a.snapshotState() {
	case StateFailed, StateSessionLimit:
		if !a.nextAction.IsZero() {
			if d := a.nextAction.Sub(now); d > 0 {
				want = int(d.Seconds()) + 1
			}
		}
	}
	a.mu.Lock()
	if a.snap.RetryIn == want {
		a.mu.Unlock()
		return
	}
	a.snap.RetryIn = want
	a.mu.Unlock()
	a.push()
}

func (a *Agent) enterOnline() {	wasOnline := a.snapshotState() == StateOnline
	a.setState(StateOnline, onlineDetail(a.snapshotIP()))
	a.failures = 0
	a.nextAction = time.Now().Add(onlinePoll)
	if !wasOnline {
		a.emit(EventOnline, "Signed in — you're online.")
	}
}

func onlineDetail(ip string) string {
	if ip == "" {
		return "Online"
	}
	return "Online · " + ip
}

// attemptLogin runs one full login attempt synchronously in the loop
// goroutine (single writer of ALL portal traffic).
func (a *Agent) attemptLogin(now time.Time, cfg config.Data) {
	a.failures++
	attempt := a.failures
	a.setState(StateAuthenticating, fmt.Sprintf("Signing in… (attempt %d)", attempt))

	res := a.portal.Login(portal.Creds{Username: cfg.Username, Password: cfg.Password}, false, 0)

	switch {
	case res.OK:
		a.setState(StateOnline, onlineDetail(a.snapshotIP()))
		a.failures = 0
		a.nextAction = time.Now().Add(onlinePoll)
		a.emit(EventOnline, "Signed in — you're online.")

	case res.SessionLimit:
		a.onSessionLimit(now, res)

	case res.InProgress:
		// The Login call itself already waited 6×1 s for the in-flight
		// authenticator; retry soon without escalating backoff.
		delay := 3 * time.Second
		if res.RetryAfter > 0 {
			delay = res.RetryAfter
		}
		a.nextAction = now.Add(delay)
		a.setState(StateAuthenticating, "Signing in… (waiting for network)")

	default:
		a.onLoginFailure(now, res.Msg)
	}
}

func (a *Agent) onSessionLimit(now time.Time, res portal.Result) {
	cfg, _ := a.cfgp.Get()
	n := 2 // protocol maximum
	if sessions, err := a.portal.ListSessions(portal.Creds{Username: cfg.Username, Password: cfg.Password}); err == nil && len(sessions) > 0 {
		n = len(sessions)
		a.setSessions(sessions, true)
	} else {
		a.setSessions(nil, false)
	}
	a.setState(StateSessionLimit, fmt.Sprintf("%d sessions active — close one", n))
	a.setErr(res.Msg, now)
	// Keep retrying quietly in case the other device logs out by itself;
	// backoff doubles like ordinary failures.
	a.nextAction = now.Add(backoffFor(a.failures))
	if time.Since(a.limitEvents) >= 10*time.Minute {
		a.limitEvents = now
		a.emit(EventSessionLimit, "Both sessions are in use — close one from the tray.")
	}
}

func (a *Agent) onLoginFailure(now time.Time, msg string) {
	a.setErr(msg, now)
	d := backoffFor(a.failures)
	a.setState(StateFailed, fmt.Sprintf("Couldn't sign in — retrying in %s", human(d)))
	a.nextAction = now.Add(d)
	if a.failures >= 3 && time.Since(a.failEvents) >= 30*time.Minute {
		a.failEvents = now
		head, advice := Friendly(msg)
		a.emit(EventFailed, head+" "+advice)
	}
}

func backoffFor(failures int) time.Duration {
	d := backoffBase
	for i := 1; i < failures && d < backoffCap; i++ {
		d *= 2
	}
	if d > backoffCap {
		return backoffCap
	}
	return d
}

func human(d time.Duration) string {
	m := int(d.Minutes())
	if m < 1 {
		return fmt.Sprintf("%ds", int(d.Seconds()))
	}
	if m == 1 {
		return "1 min"
	}
	return fmt.Sprintf("%d min", m)
}
