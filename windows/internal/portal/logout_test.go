package portal

import "testing"

func TestLogoutVerified(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)

	if res := c.Login(testCreds, false, 0); !res.OK {
		t.Fatalf("login failed: %s", res.Msg)
	}
	ok, msg := c.Logout(testCreds)
	if !ok {
		t.Fatalf("logout failed: %s", msg)
	}
	if m.isAuthed() {
		t.Fatal("mock still authed after logout")
	}
}

func TestLogoutWhenNotOnline(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	// Not logged in at all: logout should still not report a false success
	// of "online" state — mock reports not-authed so verify passes.
	ok, msg := c.Logout(testCreds)
	if !ok {
		t.Logf("logout when offline reported: %s", msg)
	}
}

func TestDetectStates(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)

	// Captive: splash page with a portal URL.
	det := c.Detect()
	if !det.Captive || det.PortalURL == "" {
		t.Fatalf("want captive, got %+v", det)
	}

	// Online after auth.
	if res := c.Login(testCreds, false, 0); !res.OK {
		t.Fatalf("login failed: %s", res.Msg)
	}
	det = c.Detect()
	if !det.Online {
		t.Fatalf("want online, got %+v", det)
	}
}

func TestParseSessionsTable(t *testing.T) {
	page := `<html><body>Current Session` +
		`<span class="pagebanner">1 items found, displaying all items.</span>` +
		`<table><tbody><tr class="odd">` +
		`<td>TAUQUEER &nbsp;AKHTAR</td><td>9000000000</td><td>CURAJ users Rate plan</td><td>9000000000</td>` +
		`<td>2026-10-21 15:09:00</td><td>04:14:04</td><td>4.84MB</td><td>28.32MB</td><td>33.16MB</td>` +
		`<td><input type="button" value="Close" onclick="callClose(137700002);"></td></tr>` +
		`</tbody></table></body></html>`
	sessions := parseSessions(page)
	if len(sessions) != 1 {
		t.Fatalf("want 1 row, got %d", len(sessions))
	}
	s := sessions[0]
	if s.SesNo != "137700002" || s.Name != "TAUQUEER AKHTAR" || s.Total != "33.16MB" {
		t.Fatalf("parse wrong: %+v", s)
	}
}
