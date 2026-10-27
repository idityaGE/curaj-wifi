package scrub

import "testing"

func TestRedactFormPasswords(t *testing.T) {
	in := "POST newlogin.do username=9000000000&password=hunter2&phone=0&type=2"
	out := Redact(in)
	if want := "POST newlogin.do username=***&password=***&phone=0&type=2"; out != want {
		t.Fatalf("got %q want %q", out, want)
	}
}

func TestRedactJSONPassword(t *testing.T) {
	in := `{"errorKey":"error","password":"s3cret","x":1}`
	out := Redact(in)
	if want := `{"errorKey":"error","password":"***","x":1}`; out != want {
		t.Fatalf("got %q want %q", out, want)
	}
}

func TestRedactCookieAndSCToken(t *testing.T) {
	in := "GET /u/?sc=abcdef123456 Cookie: JSESSIONID=ABCD1234EF56"
	out := Redact(in)
	if got := contains(out, "Cookie: ***"); !got {
		t.Fatalf("cookie header not redacted: %q", out)
	}
	if contains(out, "abcdef123456") {
		t.Fatalf("sc token not redacted: %q", out)
	}
	if contains(out, "ABCD1234EF56") {
		t.Fatalf("session id leaked: %q", out)
	}
}

func TestRedactExtraSecrets(t *testing.T) {
	out := Redact("login attempt for user 9000000000 failed", "9000000000")
	if contains(out, "9000000000") {
		t.Fatalf("username leaked: %q", out)
	}
}

func TestShortSecretsNotRedacted(t *testing.T) {
	out := Redact("a b c", "ab")
	if out != "a b c" {
		t.Fatalf("over-eager redaction: %q", out)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
