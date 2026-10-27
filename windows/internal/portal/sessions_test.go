package portal

import (
	"testing"
)

func TestListSessions(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)

	sessions, err := c.ListSessions(testCreds)
	if err != nil {
		t.Fatalf("list sessions failed: %v", err)
	}
	if len(sessions) != 2 {
		t.Fatalf("want 2 sessions, got %d", len(sessions))
	}
	s := sessions[0]
	if s.SesNo != "137700001" || s.Name != "TEST USER" || s.Duration != "00:10:19" {
		t.Fatalf("parsed session wrong: %+v", s)
	}
	if s.Display() != "TEST USER · 00:10:19" {
		t.Fatalf("display wrong: %q", s.Display())
	}
}

func TestCloseSessionVerified(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)

	ok, msg := c.CloseSession(testCreds, "137700001")
	if !ok {
		t.Fatalf("close failed: %s", msg)
	}
	sessions, err := c.ListSessions(testCreds)
	if err != nil {
		t.Fatalf("re-list failed: %v", err)
	}
	if len(sessions) != 1 || sessions[0].SesNo != "137700002" {
		t.Fatalf("session not actually closed: %+v", sessions)
	}
}

func TestCloseSessionRejectsBadID(t *testing.T) {
	m := newMockPortal(t)
	c := testClient(t, m)
	if ok, msg := c.CloseSession(testCreds, "DROP TABLE"); ok {
		t.Fatalf("non-numeric id must be refused (got ok, %s)", msg)
	}
	if ok, _ := c.CloseSession(testCreds, "999999"); ok {
		t.Fatal("closing a nonexistent id must fail")
	}
}
