package portal

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

func readCapped(r io.Reader, n int64) (string, error) {
	b, err := io.ReadAll(io.LimitReader(r, n))
	return string(b), err
}

var (
	portalURLRe = regexp.MustCompile(`https?://[^\s"'<>]+/userportal/\?[^\s"'<>]+`)
	metaURLRe   = regexp.MustCompile(`(?i)URL=([^"'>\s]+)`)
	jsURLRe     = regexp.MustCompile(`(?i)(?:window\.)?location(?:\.href)?\s*=\s*["']([^"']+)["']`)
)

// Detect classifies the current network state from a probe response.
type Detect struct {
	Online    bool   // 204
	Captive   bool   // behind the campus gateway (portal page seen)
	PortalURL string // userportal URL (when Captive)
	Down      bool   // no answer from any probe endpoint
	Status    int
	Err       string
}

// findPortal pulls the userportal URL out of a redirect / meta-refresh /
// JS page, following the NAS splash (1.254.254.254) one hop.
func (c *Client) findPortal(status int, hdr http.Header, body, reqURL string) string {
	candidates := []string{}
	if loc := hdr.Get("Location"); loc != "" {
		candidates = append(candidates, joinURL(reqURL, loc))
	}
	for _, rx := range []*regexp.Regexp{metaURLRe, jsURLRe} {
		for _, m := range rx.FindAllStringSubmatch(body, -1) {
			candidates = append(candidates, joinURL(reqURL, m[1]))
		}
	}
	for _, m := range portalURLRe.FindAllString(body, -1) {
		candidates = append(candidates, m)
	}
	for _, raw := range candidates {
		u := strings.ReplaceAll(raw, " ", "%20")
		if !strings.Contains(u, "/userportal") && !strings.Contains(u, "1.254.254.254") {
			continue
		}
		pu, err := url.Parse(u)
		if err != nil || pu.Hostname() == "" {
			continue
		}
		// 1.254.254.254 is the NAS splash: it re-redirects to the real portal.
		if pu.Hostname() == "1.254.254.254" {
			st2, hdr2, body2, err := c.httpDo("GET", u, nil, nil, 6*time.Second)
			if err == nil {
				if deeper := c.findPortal(st2, hdr2, body2, u); deeper != "" &&
					!strings.Contains(deeper, "1.254.254.254") {
					return deeper
				}
			}
		}
		return u
	}
	// Fallback: portal host mentioned in the body even if the regex missed.
	if m := regexp.MustCompile(`(http://122\.252\.242\.93[^\s"'<>]+)`).FindStringSubmatch(body); m != nil {
		return strings.ReplaceAll(m[1], " ", "%20")
	}
	return ""
}

func joinURL(base, ref string) string {
	bu, err1 := url.Parse(base)
	ru, err2 := url.Parse(ref)
	if err1 != nil || err2 != nil {
		return ref
	}
	return bu.ResolveReference(ru).String()
}

// Detect probes and classifies the network.
func (c *Client) Detect() Detect {
	st, hdr, body, err := c.Probe()
	if err != nil {
		return Detect{Down: true, Err: err.Error()}
	}
	if st == 204 {
		return Detect{Online: true, Status: 204}
	}
	portal := c.findPortal(st, hdr, body, c.checkURL())
	if portal == "" {
		// The NAS splash usually answers in both auth states and hands out a
		// fresh sc token — the reliable discovery point.
		for _, splash := range []string{c.nasSplash(), c.nasRoot()} {
			if st2, hdr2, body2, err := c.httpDo("GET", splash, nil, nil, 6*time.Second); err == nil {
				if p := c.findPortal(st2, hdr2, body2, splash); p != "" && strings.Contains(p, "/userportal") {
					portal = p
					break
				}
			}
		}
	}
	if portal == "" {
		return Detect{Status: st, Err: fmt.Sprintf("probe HTTP %d, no portal page", st)}
	}
	return Detect{Captive: true, PortalURL: portal, Status: st}
}

// Result is the outcome of one login attempt.
type Result struct {
	OK           bool
	Msg          string
	SessionLimit bool          // "maximum session allowed reached" — user must close one
	InProgress   bool          // portal is already authenticating this IP — wait
	Stale        bool          // stale session on the NAS
	RetryAfter   time.Duration // small delay before the next attempt
}

// Creds is one portal account.
type Creds struct {
	Username string
	Password string
}

func isSessionLimit(msg string) bool {
	t := strings.ToLower(msg)
	return strings.Contains(t, "maximum session") ||
		strings.Contains(t, "session allowed reached") ||
		strings.Contains(t, "session limit") ||
		strings.Contains(t, "maximum of 2") ||
		strings.Contains(t, "subscription allow")
}

func isInProgress(msg string) bool {
	t := strings.ToLower(msg)
	return strings.Contains(t, "already in progress") ||
		strings.Contains(t, "authentication is in progress") ||
		strings.Contains(t, "authentication already")
}

func isStale(msg string) bool {
	t := strings.ToLower(msg)
	return strings.Contains(t, "redirect_to_nas") ||
		strings.Contains(t, "already running")
}

// Login runs the whole pre-auth → portal → newlogin.do flow and verifies
// by probing (never trust the portal's word alone).
func (c *Client) Login(creds Creds, force bool, depth int) Result {
	if creds.Username == "" || creds.Password == "" {
		return Result{Msg: "no credentials configured"}
	}
	st, hdr, body, err := c.Probe()
	if err != nil {
		return Result{Msg: fmt.Sprintf("connectivity probe failed: %v", err)}
	}
	if st == 204 && !force {
		return Result{OK: true, Msg: "already online"}
	}

	portalURL := c.findPortal(st, hdr, body, c.checkURL())
	if portalURL == "" {
		if st == 204 {
			return Result{OK: true, Msg: "already online (no portal page to post to)"}
		}
		// Fallback: NAS splash often still works when the captive check breaks.
		if p := c.portalURLAny(); p != "" {
			portalURL = p
		}
	}
	if portalURL == "" {
		return Result{Msg: fmt.Sprintf("no portal page found (probe HTTP %d)", st)}
	}
	c.Log("portal: %s", portalURL)
	if !c.trusted(portalURL) {
		u, _ := url.Parse(portalURL)
		return Result{Msg: fmt.Sprintf("refusing to send credentials to untrusted portal host %q", u.Hostname())}
	}

	pu, _ := url.Parse(portalURL)
	netloc := pu.Host
	requesturi := pu.Query().Get("requesturi")
	if requesturi == "" {
		requesturi = "http://" + c.CheckHost + "/generate_204"
	}

	// 1. establish the portal session (JSESSIONID carries ip/mac/sc context)
	st1, hdr1, _, err := c.httpDo("GET", portalURL, nil, nil, 8*time.Second)
	if err != nil {
		return Result{Msg: fmt.Sprintf("portal unreachable: %v", err)}
	}
	_ = st1
	cookie := cookieFrom(hdr1)
	if cookie == "" {
		return Result{Msg: "portal did not issue a JSESSIONID"}
	}

	common := map[string]string{
		"Cookie":  cookie,
		"Referer": "http://" + netloc + LoginHTMLReferer,
		"Origin":  "http://" + netloc,
	}

	// 2. same hop the browser makes: login.do → SPA (best-effort)
	c.httpDo("POST", "http://"+netloc+"/userportal/login.do?requesturi="+
		url.QueryEscape(requesturi), common, nil, 8*time.Second)

	// 3. the credential POST
	form := url.Values{
		"username":     {creds.Username},
		"password":     {creds.Password},
		"phone":        {"0"},
		"type":         {"2"},
		"jsonresponse": {"1"},
	}
	hdrs := map[string]string{
		"Cookie":       cookie,
		"Content-Type": "application/x-www-form-urlencoded",
		"Referer":      common["Referer"],
		"Origin":       common["Origin"],
	}
	c.Log("POST http://%s/userportal/newlogin.do (user %s)", netloc, creds.Username)
	st3, _, body3, err := c.httpDo("POST", "http://"+netloc+SelfCareLoginPath,
		hdrs, []byte(form.Encode()), 10*time.Second)
	if err != nil {
		return Result{Msg: fmt.Sprintf("login POST failed: %v", err)}
	}

	var data map[string]string
	_ = json.Unmarshal([]byte(body3), &data)
	ok := data["errorKey"] == "success" || data["errorMessage"] == "success_net"

	if !ok {
		reason := orChain(data["errorMessage"], data["errorKey"], clip(body3, 160), fmt.Sprintf("HTTP %d", st3))
		combined := data["errorMessage"] + " " + data["errorKey"] + " " + clip(body3, 300)

		// Session limit: never auto-kick — the user picks a session to close.
		if isSessionLimit(combined) {
			c.Log("portal session limit reached")
			return Result{SessionLimit: true, Msg: "session limit reached (2 active)"}
		}
		// Another authenticator (e.g. the OS captive-portal helper) is mid-
		// flow. Do not double-post: wait for the gate to open.
		if isInProgress(combined) {
			c.Log("portal is already authenticating this IP; waiting")
			if c.waitOnline(6, time.Second) {
				return Result{OK: true, Msg: "logged in"}
			}
			return Result{InProgress: true, Msg: "portal is still authenticating; retrying",
				RetryAfter: 3 * time.Second}
		}
		// Stale NAS session: clear it and retry exactly once.
		if isStale(combined) && depth < 1 {
			c.Log("portal reports an existing/stale session; clearing and retrying")
			c.clearStale(cookie, netloc)
			if online, _ := c.ProbeOnline(); online {
				return Result{OK: true, Msg: "logged in (stale session cleared)"}
			}
			return c.Login(creds, force, depth+1)
		}
		return Result{Msg: "rejected: " + reason}
	}

	// 4. verify the NAS actually opened the gate; it can blackhole briefly.
	if c.waitOnline(c.verifyProbesN(), c.verifyGapD()) {
		return Result{OK: true, Msg: "logged in"}
	}
	// Portal said success but internet is still blocked: clean logout + one retry.
	if depth < 1 {
		c.Log("portal said success but internet still blocked; cleaning up and retrying")
		c.LogoutQuiet(creds)
		time.Sleep(3 * time.Second)
		return c.Login(creds, force, depth+1)
	}
	return Result{Msg: "portal said success but internet is still blocked"}
}

// portalURLAny finds a userportal URL even while already authenticated.
func (c *Client) portalURLAny() string {
	if st, hdr, body, err := c.Probe(); err == nil && st != 204 {
		if p := c.findPortal(st, hdr, body, c.checkURL()); p != "" {
			return p
		}
	}
	for _, splash := range []string{c.nasSplash(), c.nasRoot()} {
		st, hdr, body, err := c.httpDo("GET", splash, nil, nil, 6*time.Second)
		if err != nil {
			continue
		}
		if p := c.findPortal(st, hdr, body, splash); p != "" && strings.Contains(p, "/userportal") {
			return p
		}
	}
	return ""
}

// clearStale kills the portal session with our cookie, then the NAS one.
func (c *Client) clearStale(cookie, netloc string) {
	_, _, _, _ = c.httpDo("POST", "http://"+netloc+"/userportal/logout.do",
		map[string]string{"Cookie": cookie}, []byte{}, 5*time.Second)
	_, _, _, _ = c.httpDo("GET", c.nasLogout(), nil, nil, 5*time.Second)
	time.Sleep(c.staleWaitD())
}

// waitOnline polls the probe until it answers 204 or the budget is used up.
// Tolerated blackholes (right after a session change) are not failures.
func (c *Client) waitOnline(n int, gap time.Duration) bool {
	for i := 0; i < n; i++ {
		time.Sleep(gap)
		if online, _ := c.ProbeOnline(); online {
			return true
		}
	}
	return false
}

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) > n {
		return s[:n]
	}
	return s
}

func orChain(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
