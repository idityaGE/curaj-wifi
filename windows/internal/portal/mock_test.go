package portal

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"
)

// mockPortal emulates the Inventum portal on localhost (mirrors
// linux/test_flow.py) so the whole flow is testable on Linux.

type mockSession struct {
	id       string
	start    string
	duration string
}

type mockPortal struct {
	mu sync.Mutex

	authed     bool
	mode       string // "normal" | "limit" | "inprogress" | "stale"
	staleClear bool

	ctxCookieIssued  bool
	selfCareLoggedIn bool

	sessions []mockSession

	// probes counts absolute-form probes; sawAbs asserts proxy-form shape.
	probes   int
	sawAbs   bool
	sawLogin bool

	srv  *httptest.Server
	host string
}

func newMockPortal(t *testing.T) *mockPortal {
	t.Helper()
	m := &mockPortal{
		sessions: []mockSession{
			{id: "137700001", start: "2026-10-21 19:13:15", duration: "00:10:19"},
			{id: "137700002", start: "2026-10-21 15:09:00", duration: "04:14:04"},
		},
	}
	m.srv = httptest.NewServer(http.HandlerFunc(m.handler))
	t.Cleanup(m.srv.Close)
	u, _ := url.Parse(m.srv.URL)
	m.host = u.Host
	return m
}

func (m *mockPortal) portalURL() string {
	return fmt.Sprintf("http://%s/userportal/?requesturi=http%%3A%%2F%%2Fconnect%%2Fgenerate_204&ip=10.50.0.247&mac=AA%%3ABB%%3ACC&nas=curaj&sc=abcdef123456", m.host)
}

func (m *mockPortal) setMode(mode string) {
	m.mu.Lock()
	m.mode = mode
	m.mu.Unlock()
}

func (m *mockPortal) isAuthed() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.authed
}

func (m *mockPortal) handler(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	defer m.mu.Unlock()

	// The probe MUST use the proxy-form (absolute-URI) request target.
	if strings.HasSuffix(r.URL.Path, "/generate_204") {
		m.probes++
		if r.URL.IsAbs() {
			m.sawAbs = true
		}
		if m.authed {
			w.WriteHeader(204)
			return
		}
		fmt.Fprintf(w, `<html><head><meta http-equiv="refresh" content="1; URL=%s"></head><body>Please wait...</body></html>`, m.portalURL())
		return
	}

	if strings.Contains(r.URL.Path, "/nas") {
		// NAS splash: re-redirects to the real portal.
		if r.URL.Path == "/logout" {
			m.staleClear = true
			m.mode = "normal"
			w.WriteHeader(200)
			fmt.Fprint(w, "logged out")
			return
		}
		fmt.Fprintf(w, `<html><head><meta http-equiv="refresh" content="1; URL=%s"></head></html>`, m.portalURL())
		return
	}

	if !strings.Contains(r.URL.Path, "/userportal/") {
		http.NotFound(w, r)
		return
	}

	cookie := cookieValue(r, "JSESSIONID")

	// Portal splash: issues the context JSESSIONID.
	if strings.HasPrefix(r.URL.Path, "/userportal/") && strings.Contains(r.URL.RawQuery, "requesturi") && r.Method == "GET" {
		m.ctxCookieIssued = true
		w.Header().Add("Set-Cookie", "JSESSIONID=ctx1; Path=/")
		fmt.Fprint(w, `<html><body>login page</body></html>`)
		return
	}

	switch {
	case strings.Contains(r.URL.Path, "/login.do"):
		w.WriteHeader(200)
		fmt.Fprint(w, "ok")
		return

	case strings.Contains(r.URL.Path, "/newlogin.do"):
		if err := r.ParseForm(); err != nil {
			w.WriteHeader(400)
			return
		}
		kind := r.Form.Get("type")
		switch kind {
		case "1": // self-care: requires a context cookie first
			if !m.ctxCookieIssued || cookie == "" {
				w.WriteHeader(302)
				return
			}
			m.selfCareLoggedIn = true
			w.Header().Add("Set-Cookie", "JSESSIONID=self1; Path=/")
			fmt.Fprint(w, `{"errorKey":"success","errorMessage":"success"}`)
			return
		default: // type=2 internet login
			if cookie == "" {
				w.WriteHeader(302)
				return
			}
			switch m.mode {
			case "limit":
				fmt.Fprint(w, `{"errorKey":"error","errorMessage":"maximum session allowed reached"}`)
			case "inprogress":
				fmt.Fprint(w, `{"errorKey":"error","errorMessage":"authentication already in progress"}`)
			case "stale":
				fmt.Fprint(w, `{"errorKey":"redirect_to_nas","errorMessage":"Session already running"}`)
			case "reject":
				fmt.Fprint(w, `{"errorKey":"error","errorMessage":"invalid password"}`)
			default:
				m.authed = true
				fmt.Fprint(w, `{"errorKey":"success","errorMessage":"success_net"}`)
			}
			return
		}

	case strings.Contains(r.URL.Path, "/logout.do"):
		if sesno := r.URL.Query().Get("sesno"); sesno != "" {
			if !m.ctxCookieIssued || !m.selfCareLoggedIn {
				w.WriteHeader(302) // unauthorised: session stays alive
				return
			}
			var kept []mockSession
			for _, s := range m.sessions {
				if s.id != sesno {
					kept = append(kept, s)
				}
			}
			m.sessions = kept
			w.WriteHeader(200)
			fmt.Fprint(w, "closed")
			return
		}
		// Plain logout.do: tears the NAS session down (also un-sticks the
		// stale mode — the same recovery the real portal needs).
		m.authed = false
		m.mode = "normal"
		m.staleClear = true
		w.WriteHeader(200)
		return
	}

	if strings.Contains(r.URL.Path, "currentSessions.do") {
		if !m.selfCareLoggedIn || cookie != "self1" {
			fmt.Fprint(w, "session has been expired")
			return
		}
		fmt.Fprint(w, m.sessionTable())
		return
	}

	http.NotFound(w, r)
}

func (m *mockPortal) sessionTable() string {
	var rows []string
	for i, s := range m.sessions {
		css := "odd"
		if i%2 == 1 {
			css = "even"
		}
		rows = append(rows, fmt.Sprintf(
			`<tr class="%s"><td>TEST USER</td><td>9000000000</td><td>CURAJ users Rate plan</td><td>9000000000</td><td>%s</td><td>%s</td><td>1.00KB</td><td>2.00KB</td><td>3.00KB</td><td><input type="button" value="Close" onclick="callClose(%s);"></td></tr>`,
			css, s.start, s.duration, s.id))
	}
	return "<html><body><div>Current Session</div>" +
		fmt.Sprintf(`<span class="pagebanner">%d items found, displaying all items.</span>`, len(m.sessions)) +
		"<table><tbody>" + strings.Join(rows, "") + "</tbody></table></body></html>"
}

func cookieValue(r *http.Request, name string) string {
	c, err := r.Cookie(name)
	if err != nil {
		return ""
	}
	return c.Value
}

// testClient returns a Client pointed at the mock with fast timings.
func testClient(t *testing.T, m *mockPortal) *Client {
	t.Helper()
	u, _ := url.Parse(m.srv.URL)
	port := u.Port()
	c := New("", nil)
	c.PortalHost = u.Host
	c.CheckHost = u.Hostname()
	c.CheckPort = port
	c.ProbePort = port
	c.ProbeIPs = []string{u.Hostname()}
	c.NASLogout = m.srv.URL + "/logout"
	c.NASSplash = m.srv.URL + "/nas/login"
	c.NASRoot = m.srv.URL + "/nas/"
	c.VerifyGap = 10 * time.Millisecond
	c.StaleWait = 5 * time.Millisecond
	c.CloseWait = 5 * time.Millisecond
	c.LogoutWait = 5 * time.Millisecond
	c.ProbeGap = time.Millisecond
	return c
}

var testCreds = Creds{Username: "9000000000", Password: "secret123"}
