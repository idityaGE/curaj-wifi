package agent

import (
	"errors"
	"sync"
	"testing"
	"time"

	"curaj-wifi/internal/config"
	"curaj-wifi/internal/portal"
)

// ---- fakes ----

type fakePortal struct {
	mu sync.Mutex

	det      portal.Detect
	loginRes portal.Result
	sessions []portal.Session

	loginCalls  int
	logoutCalls int
	closeCalls  []string
	detectCalls int

	logoutOK  bool
	logoutMsg string
}

func (f *fakePortal) Detect() portal.Detect {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.detectCalls++
	return f.det
}

func (f *fakePortal) detCalls() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.detectCalls
}

func (f *fakePortal) Login(creds portal.Creds, force bool, depth int) portal.Result {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.loginCalls++
	return f.loginRes
}

func (f *fakePortal) Logout(creds portal.Creds) (bool, string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.logoutCalls++
	return f.logoutOK, f.logoutMsg
}

func (f *fakePortal) ListSessions(creds portal.Creds) ([]portal.Session, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.sessions == nil {
		return nil, errors.New("no self-care session")
	}
	return f.sessions, nil
}

func (f *fakePortal) CloseSession(creds portal.Creds, sesno string) (bool, string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.closeCalls = append(f.closeCalls, sesno)
	return true, "closed " + sesno
}

type fakeCfg struct {
	mu     sync.Mutex
	d      config.Data
	err    error
	calls  []bool
}

func (f *fakeCfg) Get() (config.Data, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.d, f.err
}

func (f *fakeCfg) SetAutoConnect(b bool) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, b)
	f.d.AutoConnect = b
	return nil
}

type recorder struct {
	mu     sync.Mutex
	events []Event
}

func (r *recorder) hook(e Event) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.events = append(r.events, e)
}

func newTestAgent(fp *fakePortal, fc *fakeCfg) (*Agent, *recorder) {
	rec := &recorder{}
	return New(fp, fc, func(string, ...any) {}, rec.hook, nil), rec
}

func testConfigured(auto bool) *fakeCfg {
	return &fakeCfg{d: config.Data{
		Username: "9000000000", Password: "pw", AutoConnect: auto,
		SSID: "CURAJ CAMPUS CONNECT",
	}}
}

// ---- command tests (white-box: handle() runs inline) ----

func runCmd(a *Agent, kind, sesno string) CmdResult {
	res := make(chan CmdResult, 1)
	a.handle(Command{Kind: kind, Sesno: sesno, reply: res})
	return <-res
}

func TestSignInNowSuccess(t *testing.T) {
	fp := &fakePortal{det: portal.Detect{Captive: true, PortalURL: "http://x/userportal/?x=1"},
		loginRes: portal.Result{OK: true, Msg: "logged in"}}
	fc := testConfigured(false)
	a, rec := newTestAgent(fp, fc)

	res := runCmd(a, CmdLoginNow, "")
	if !res.OK {
		t.Fatalf("signin failed: %s", res.Msg)
	}
	if got := a.Snapshot().State; got != StateOnline {
		t.Fatalf("state = %s, want online", got)
	}
	if !fc.d.AutoConnect {
		t.Fatal("Sign in now must re-enable Auto connect")
	}
	if len(rec.events) == 0 || rec.events[len(rec.events)-1].Kind != EventOnline {
		t.Fatalf("missing online event: %+v", rec.events)
	}
}

func TestSessionLimitCloseFlow(t *testing.T) {
	fp := &fakePortal{det: portal.Detect{Captive: true},
		loginRes: portal.Result{SessionLimit: true, Msg: "session limit reached (2 active)"}}
	fp.sessions = []portal.Session{
		{SesNo: "1", Name: "OTHER DEVICE", Duration: "4h 12m"},
		{SesNo: "2", Name: "THIS PC", Duration: "3m"},
	}
	fc := testConfigured(true)
	a, _ := newTestAgent(fp, fc)

	// tick: captive → login → session limit
	a.nextAction = time.Time{}
	a.tick()
	snap := a.Snapshot()
	if snap.State != StateSessionLimit {
		t.Fatalf("state = %s, want session_limit", snap.State)
	}
	if want := "2 sessions active — close one"; snap.Detail != want {
		t.Fatalf("detail = %q, want %q", snap.Detail, want)
	}

	// Now the fix: close the other device's session → verify → auto retry.
	fp.det = portal.Detect{Captive: true}
	fp.loginRes = portal.Result{OK: true}
	res := runCmd(a, CmdCloseSession, "1")
	if !res.OK {
		t.Fatalf("close failed: %s", res.Msg)
	}
	if res.Msg != "Session closed — signed in." {
		t.Fatalf("close result = %q", res.Msg)
	}
	if got := a.Snapshot().State; got != StateOnline {
		t.Fatalf("after close state = %s, want online", got)
	}
	if len(fp.closeCalls) != 1 || fp.closeCalls[0] != "1" {
		t.Fatalf("close calls: %v", fp.closeCalls)
	}
}

func TestSessionLimitListsSessionsForMenu(t *testing.T) {
	fp := &fakePortal{det: portal.Detect{Captive: true},
		loginRes: portal.Result{SessionLimit: true}}
	fp.sessions = []portal.Session{{SesNo: "9", Name: "X", Duration: "1m"}}
	fc := testConfigured(true)
	a, _ := newTestAgent(fp, fc)
	a.nextAction = time.Time{}
	a.tick()
	snap := a.Snapshot()
	if len(snap.Sessions) != 1 || snap.Sessions[0].SesNo != "9" {
		t.Fatalf("sessions not cached for the tray menu: %+v", snap.Sessions)
	}
}

func TestLogoutTurnsAutoConnectOff(t *testing.T) {
	fp := &fakePortal{det: portal.Detect{Online: true}, logoutOK: true}
	fc := testConfigured(true)
	a, rec := newTestAgent(fp, fc)

	// Get online first.
	a.nextAction = time.Time{}
	a.tick()
	if got := a.Snapshot().State; got != StateOnline {
		t.Fatalf("state = %s, want online", got)
	}

	res := runCmd(a, CmdLogout, "")
	if !res.OK {
		t.Fatalf("logout failed: %s", res.Msg)
	}
	snap := a.Snapshot()
	if snap.State != StateAutoOff {
		t.Fatalf("state = %s, want auto_connect_off", snap.State)
	}
	if fc.d.AutoConnect {
		t.Fatal("explicit logout must turn Auto connect off")
	}
	found := false
	for _, e := range rec.events {
		if e.Kind == EventLogout {
			found = true
		}
	}
	if !found {
		t.Fatal("missing logout event")
	}
}

func TestFailedBackoffAndDetail(t *testing.T) {
	fp := &fakePortal{det: portal.Detect{Captive: true},
		loginRes: portal.Result{Msg: "rejected: bad password"}}
	fc := testConfigured(true)
	a, _ := newTestAgent(fp, fc)

	a.nextAction = time.Time{}
	a.tick()
	snap := a.Snapshot()
	if snap.State != StateFailed {
		t.Fatalf("state = %s, want failed", snap.State)
	}
	if snap.Detail != "Couldn't sign in — retrying in 20s" {
		t.Fatalf("detail = %q", snap.Detail)
	}
	if snap.LastError != "rejected: bad password" {
		t.Fatalf("last error = %q", snap.LastError)
	}
}

func TestBackoffProgression(t *testing.T) {
	cases := []struct {
		failures int
		want     time.Duration
	}{
		{1, 20 * time.Second},
		{2, 40 * time.Second},
		{3, 80 * time.Second},
		{4, 160 * time.Second},
		{6, 10 * time.Minute}, // 640 s > cap
		{7, 10 * time.Minute}, // capped
		{12, 10 * time.Minute},
	}
	for _, c := range cases {
		if got := backoffFor(c.failures); got != c.want {
			t.Fatalf("backoffFor(%d) = %v, want %v", c.failures, got, c.want)
		}
	}
}

func TestNotConfiguredEmitsSetupOnce(t *testing.T) {
	fp := &fakePortal{det: portal.Detect{Down: true}}
	fc := &fakeCfg{err: config.ErrNotConfigured}
	a, rec := newTestAgent(fp, fc)

	a.tick()
	a.tick()
	a.tick()
	n := 0
	for _, e := range rec.events {
		if e.Kind == EventNeedsSetup {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("needs_setup events = %d, want 1 (once until configured)", n)
	}
	if got := a.Snapshot().State; got != StateNotConfigured {
		t.Fatalf("state = %s, want not_configured", got)
	}
}

func TestOnlinePollCadence(t *testing.T) {
	fp := &fakePortal{det: portal.Detect{Online: true}}
	fc := testConfigured(true)
	a, _ := newTestAgent(fp, fc)

	a.nextAction = time.Time{}
	a.tick()
	first := fp.detCalls()
	a.tick() // inside the 60 s window: no probe
	if fp.detCalls() != first {
		t.Fatal("probed again inside the online poll window")
	}
	a.nextAction = time.Time{}
	a.tick()
	if fp.detCalls() == first {
		t.Fatal("no probe after the window elapsed")
	}
}

func TestFriendlyCopy(t *testing.T) {
	cases := []struct {
		raw      string
		contains []string
	}{
		{"maximum session allowed reached", []string{"both of your sessions", "limit 2", "Close one"}},
		{"authentication already in progress", []string{"already signing you in", "by itself"}},
		{"redirect_to_nas", []string{"stale session", "automatically"}},
		{"connectivity probe failed: i/o timeout", []string{"not responding", "later"}},
		{"portal unreachable: dial tcp", []string{"login page is unreachable", "outage"}},
	}
	for _, c := range cases {
		head, advice := Friendly(c.raw)
		for _, s := range c.contains {
			if !containsFold(head+" "+advice, s) {
				t.Fatalf("friendly(%q) missing %q: got %q + %q", c.raw, s, head, advice)
			}
		}
	}
}

func containsFold(s, sub string) bool {
	s, sub = toLower(s), toLower(sub)
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func toLower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + 32
		}
	}
	return string(b)
}
