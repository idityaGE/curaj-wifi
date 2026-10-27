package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// testProtector is the in-memory protector used by tests on any OS (the
// Windows build always uses ProtectorLM in production code paths).
type testProtector struct{}

func (testProtector) Protect(plain []byte) ([]byte, error) {
	out := make([]byte, len(plain))
	copy(out, plain)
	return out, nil
}

func (testProtector) Unprotect(cipher []byte) ([]byte, error) {
	out := make([]byte, len(cipher))
	copy(out, cipher)
	return out, nil
}

func TestRoundTrip(t *testing.T) {
	dir := t.TempDir()
	s := &Store{Dir: dir}
	p := testProtector{}

	in := Data{Username: "9000000000", Password: "hunter2", AutoConnect: true, SSID: "CURAJ CAMPUS CONNECT"}
	if err := s.Save(in, p); err != nil {
		t.Fatal(err)
	}
	out, err := s.Load(p)
	if err != nil {
		t.Fatal(err)
	}
	if out.Username != in.Username || out.Password != in.Password || !out.AutoConnect {
		t.Fatalf("round trip mismatch: %+v", out)
	}
}

func TestNotConfigured(t *testing.T) {
	s := &Store{Dir: t.TempDir()}
	if _, err := s.Load(testProtector{}); err != ErrNotConfigured {
		t.Fatalf("want ErrNotConfigured, got %v", err)
	}
}

func TestOnDiskShape(t *testing.T) {
	dir := t.TempDir()
	s := &Store{Dir: dir}
	if err := s.Save(Data{Username: "u", Password: "p", AutoConnect: true}, testProtector{}); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(dir, "config.json"))
	if err != nil {
		t.Fatal(err)
	}
	var d disk
	if err := json.Unmarshal(raw, &d); err != nil {
		t.Fatal(err)
	}
	if d.Enc != "dpapi-lm" {
		t.Fatalf("enc scheme missing: %q", d.Enc)
	}
	if strings.Contains(string(raw), "hunter2") || strings.Contains(string(raw), `"u"`) {
		t.Fatalf("plaintext credential on disk: %s", raw)
	}
}
