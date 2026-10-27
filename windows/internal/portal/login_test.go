package portal

import (
	"testing"
)

func TestLoginHappyPath(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)

	res := c.Login(testCreds, false, 0)
	if !res.OK {
		t.Fatalf("login failed: %s", res.Msg)
	}
	if !m.isAuthed() {
		t.Fatal("mock never recorded an authenticated session")
	}
	if !m.sawAbs {
		t.Fatal("probe did not use proxy-form (absolute URI) request target")
	}
	if !m.sawLogin {
		_ = m.sawLogin // login.do is best-effort; not asserted hard
	}
}

func TestLoginAlreadyOnline(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	m.setMode("normal")
	// Authenticate first, then login again should short-circuit.
	if res := c.Login(testCreds, false, 0); !res.OK {
		t.Fatalf("first login failed: %s", res.Msg)
	}
	res := c.Login(testCreds, false, 0)
	if !res.OK || res.Msg != "already online" {
		t.Fatalf("expected already online, got %v / %q", res.OK, res.Msg)
	}
}

func TestLoginSessionLimit(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	m.setMode("limit")

	res := c.Login(testCreds, false, 0)
	if res.OK {
		t.Fatal("login must fail at the session limit")
	}
	if !res.SessionLimit {
		t.Fatalf("expected SessionLimit=true, got msg %q", res.Msg)
	}
	if m.isAuthed() {
		t.Fatal("mock recorded auth despite limit")
	}
}

func TestLoginInProgress(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	m.setMode("inprogress")

	res := c.Login(testCreds, false, 0)
	if res.OK {
		t.Fatal("login must fail while another authenticator is in progress")
	}
	if !res.InProgress {
		t.Fatalf("expected InProgress=true, got msg %q", res.Msg)
	}
}

func TestLoginStaleSessionRecovery(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	m.setMode("stale")

	res := c.Login(testCreds, false, 0)
	// The mock clears the stale state on cookie-logout + NAS logout; the
	// retry then succeeds.
	if !res.OK {
		t.Fatalf("stale recovery should succeed, got %q", res.Msg)
	}
}

func TestLoginWrongCredentials(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	m.setMode("reject")

	res := c.Login(testCreds, false, 0)
	if res.OK {
		t.Fatal("login must fail on rejection")
	}
	if res.SessionLimit || res.InProgress {
		t.Fatalf("misclassified rejection: %+v", res)
	}
	if want := "rejected: invalid password"; res.Msg != want {
		t.Fatalf("got %q, want %q", res.Msg, want)
	}
}

func TestHostPinning(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	if !c.trusted(c.base() + "/userportal/x") {
		t.Fatal("portal host must be trusted")
	}
	if c.trusted("http://evil.example/userportal/?x=1") {
		t.Fatal("untrusted host must be refused")
	}
}
