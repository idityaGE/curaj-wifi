package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"curaj-wifi/internal/agent"
	"curaj-wifi/internal/portal"
)

type fakeSub struct{ snap agent.Snapshot }

func (f *fakeSub) Submit(kind, sesno string) <-chan agent.CmdResult {
	ch := make(chan agent.CmdResult, 1)
	ch <- agent.CmdResult{OK: true, Msg: "done:" + kind + ":" + sesno}
	return ch
}

func (f *fakeSub) Snapshot() agent.Snapshot {
	s := f.snap
	s.Version++
	s.Sessions = []portal.Session{
		{SesNo: "1", Name: "TEST USER", Duration: "00:10:19"},
	}
	return s
}

func testServer(t *testing.T) (*httptest.Server, string) {
	t.Helper()
	token := "0123456789abcdef0123456789abcdef"
	s := NewServer(&fakeSub{}, token)
	hs := httptest.NewServer(s.handler())
	t.Cleanup(hs.Close)
	return hs, token
}

func req(t *testing.T, url, token string, body any) (*http.Response, []byte) {
	t.Helper()
	var rd *bytes.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	} else {
		rd = bytes.NewReader(nil)
	}
	r, err := http.NewRequest("GET", url, rd)
	if err != nil {
		t.Fatal(err)
	}
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(r)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	buf := new(bytes.Buffer)
	buf.ReadFrom(resp.Body)
	return resp, buf.Bytes()
}

func TestAuthRequired(t *testing.T) {
	hs, token := testServer(t)
	resp, _ := req(t, hs.URL+"/status", "", nil)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", resp.StatusCode)
	}
	resp, _ = req(t, hs.URL+"/status", "wrong-token", nil)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("want 401 for wrong token, got %d", resp.StatusCode)
	}
	resp, _ = req(t, hs.URL+"/status", token, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
}

func TestLoginEndpoint(t *testing.T) {
	hs, token := testServer(t)
	resp, body := req(t, hs.URL+"/login", token, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("want 200, got %d: %s", resp.StatusCode, body)
	}
	if !bytes.Contains(body, []byte("done:login_now")) {
		t.Fatalf("body: %s", body)
	}
}

func TestCloseEndpoint(t *testing.T) {
	hs, token := testServer(t)
	resp, body := req(t, hs.URL+"/sessions/close", token, map[string]string{"sesno": "42"})
	if resp.StatusCode != 200 {
		t.Fatalf("want 200, got %d: %s", resp.StatusCode, body)
	}
	if !bytes.Contains(body, []byte("done:close_session:42")) {
		t.Fatalf("body: %s", body)
	}
	resp, _ = req(t, hs.URL+"/sessions/close", token, nil)
	if resp.StatusCode != 400 {
		t.Fatalf("want 400 without sesno, got %d", resp.StatusCode)
	}
}

func TestSessionsGet(t *testing.T) {
	hs, token := testServer(t)
	resp, body := req(t, hs.URL+"/sessions", token, nil)
	if resp.StatusCode != 200 {
		t.Fatalf("want 200, got %d", resp.StatusCode)
	}
	var res struct {
		Sessions []struct {
			SesNo    string `json:"sesno"`
			Duration string `json:"duration"`
		} `json:"sessions"`
	}
	if err := json.Unmarshal(body, &res); err != nil {
		t.Fatal(err)
	}
	if len(res.Sessions) != 1 || res.Sessions[0].SesNo != "1" {
		t.Fatalf("sessions: %s", body)
	}
}

func TestTokenAndPortFiles(t *testing.T) {
	dir := t.TempDir()
	tok, err := GenerateToken()
	if err != nil {
		t.Fatal(err)
	}
	if len(tok) != 64 {
		t.Fatalf("token len = %d", len(tok))
	}
	if err := WriteToken(dir, tok); err != nil {
		t.Fatal(err)
	}
	got, err := LoadToken(dir)
	if err != nil || got != tok {
		t.Fatalf("token round trip: %v %q", err, got)
	}
	if err := WritePort(dir, PortBase); err != nil {
		t.Fatal(err)
	}
	if p := LoadPort(dir); p != PortBase {
		t.Fatalf("port round trip: %d", p)
	}
	// Temp files must not linger.
	ents, _ := os.ReadDir(dir)
	for _, e := range ents {
		if filepath.Ext(e.Name()) == ".tmp" {
			t.Fatalf("leftover temp file: %s", e.Name())
		}
	}
}

func TestLoadTokenRejectsShort(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, TokenFileName), []byte("short"), 0o600)
	if _, err := LoadToken(dir); err == nil {
		t.Fatal("short token must be rejected")
	}
}
