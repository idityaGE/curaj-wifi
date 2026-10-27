// Package portal implements the "CURAJ CAMPUS CONNECT" (Inventum/RailWire)
// captive-portal protocol, ported from linux/curaj-login and the Android
// PortalClient. It is pure Go with no Windows dependencies so the whole
// package builds and unit-tests on Linux against an httptest mock.
//
// Protocol facts (verified live 2026-10-27, see docs/ENDPOINTS.md):
//
//   - The gateway Squid intercepts plain HTTP.  Pre-auth it answers with a
//     "Please wait..." page whose meta-refresh points at the userportal URL
//     carrying ip/mac/nas/sc context.
//   - Once authenticated, the Squid rejects browser-style origin-form
//     requests ("GET /x HTTP/1.1") with 400 ERR_INVALID_URL but proxies
//     proxy-form (absolute-URI) requests normally — so the online check must
//     send "GET http://host/path HTTP/1.1".
//   - Windows adaptation: the probe dials fixed IP literals (no DNS before
//     auth) with the source address bound to the Wi-Fi adapter.
package portal

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Defaults for the live campus network. Overridable on Client for tests.
const (
	DefaultPortalHost = "122.252.242.93"
	DefaultSSID       = "CURAJ CAMPUS CONNECT"
	NASLogoutURL      = "http://1.254.254.254/logout"
	NASSplashURL      = "http://1.254.254.254/login"
	CheckHost         = "connectivitycheck.gstatic.com"

	// CheckProbeIPs are IP literals used as the connection target for the
	// proxy-form probe.  Windows has no per-interface DNS binding, so we
	// avoid the resolver entirely; the gateway intercepts port 80 traffic
	// regardless of destination, and Squid proxies absolute-URI requests
	// after auth.
	CheckProbeIPs = "142.251.150.120,142.250.183.36,142.250.72.196,142.251.32.14"

	// Self-care (account management) portal paths.
	SelfCareLoginPage    = "/userportal/pages/usermedia/curaj/app/campus/ui/login.jsp"
	SelfCareLoginPath    = "/userportal/newlogin.do"
	CurrentSessionsPath  = "/userportal/currentSessions.do?from=Home"
	SelfCareSessionsPage = "/userportal/home.do?from=Home"
	LoginHTMLReferer     = "/userportal/pages/usermedia/curaj/app/campus/ui/login.html"

	MaxBody = 1 << 20

	// ProbeBudget is the total wall-clock budget for one full probe
	// (all IP literals, incl. retries).
	ProbeBudget = 6 * time.Second
)

const UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

// Logger receives pre-scrubbed log lines. Never nil after New.
type Logger func(format string, args ...any)

// Client talks to the portal. All network I/O is bound (via source address)
// to the Wi-Fi adapter so a tethered phone cannot win the route lookup.
type Client struct {
	// PortalHost is the pinned host[:port] we may send credentials to.
	PortalHost string
	// CheckHost/CheckPort form the probe URL (host header + absolute URI).
	CheckHost string
	CheckPort string
	// NAS endpoints (overridable for tests).
	NASLogout  string
	NASSplash  string
	NASRoot    string
	// ProbeIPs are the literal dial targets for the probe.
	ProbeIPs []string
	// ProbePort is the port dialed for probe targets (80 in production;
	// overridden in tests so the httptest mock receives the probe).
	ProbePort string
	// BindIP is the Wi-Fi adapter's IPv4 ("" = no binding, tests only).
	BindIP string

	// Tunable timings (zero = production default; shortened in tests).
	VerifyProbes int           // probes after login (default 6)
	VerifyGap    time.Duration // gap between verify probes (default 1.5 s)
	StaleWait    time.Duration // pause after stale-session cleanup (default 2 s)
	CloseWait    time.Duration // gap between close-verification lists (default 0.8 s)
	LogoutWait   time.Duration // pause before logout verification (default 1.5 s)
	ProbeGap     time.Duration // pause between probe rounds (default 0.5 s)

	Log Logger

	probeIdx int
}

// New returns a client pinned to the real portal host.
func New(bindIP string, log Logger) *Client {
	if log == nil {
		log = func(string, ...any) {}
	}
	ips := strings.Split(CheckProbeIPs, ",")
	return &Client{
		PortalHost: DefaultPortalHost,
		CheckHost:  CheckHost,
		CheckPort:  "80",
		ProbePort:  "80",
		ProbeIPs:   ips,
		NASLogout:  NASLogoutURL,
		NASSplash:  NASSplashURL,
		NASRoot:    "http://1.254.254.254/",
		BindIP:     bindIP,
		Log:        log,
	}
}

// nasLogout / nasSplash / nasRoot accessors fall back to the real NAS.
func (c *Client) nasLogout() string {
	if c.NASLogout != "" {
		return c.NASLogout
	}
	return NASLogoutURL
}

func (c *Client) nasSplash() string {
	if c.NASSplash != "" {
		return c.NASSplash
	}
	return NASSplashURL
}

func (c *Client) nasRoot() string {
	if c.NASRoot != "" {
		return c.NASRoot
	}
	return "http://1.254.254.254/"
}

func (c *Client) checkURL() string {
	host := c.CheckHost
	if host == "" {
		host = CheckHost
	}
	if c.CheckPort != "" && c.CheckPort != "80" {
		return fmt.Sprintf("http://%s:%s/generate_204", host, c.CheckPort)
	}
	return fmt.Sprintf("http://%s/generate_204", host)
}

// dialer returns a DialContext bound to the Wi-Fi source address.
func (c *Client) dialer() *net.Dialer {
	d := &net.Dialer{Timeout: 2 * time.Second}
	if c.BindIP != "" {
		if ip := net.ParseIP(c.BindIP); ip != nil {
			d.LocalAddr = &net.TCPAddr{IP: ip}
		}
	}
	return d
}

// httpDo performs one plain-HTTP request (origin-form) against the portal.
func (c *Client) httpDo(method, rawURL string, hdrs map[string]string,
	body []byte, timeout time.Duration) (int, http.Header, string, error) {

	u, err := url.Parse(rawURL)
	if err != nil {
		return 0, nil, "", fmt.Errorf("bad url %q: %w", rawURL, err)
	}
	if u.Scheme != "http" {
		return 0, nil, "", fmt.Errorf("only http:// is supported (portal is plain HTTP): %s", rawURL)
	}
	tr := &http.Transport{
		DialContext:           c.dialer().DialContext,
		DisableKeepAlives:     true,
		ForceAttemptHTTP2:     false,
		MaxIdleConns:          0,
		ResponseHeaderTimeout: timeout,
	}
	client := &http.Client{Transport: tr, Timeout: timeout}
	req, err := http.NewRequest(method, rawURL, strings.NewReader(string(body)))
	if err != nil {
		return 0, nil, "", err
	}
	req.Header.Set("User-Agent", UserAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("Connection", "close")
	for k, v := range hdrs {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, "", err
	}
	defer resp.Body.Close()
	text, err := readCapped(resp.Body, MaxBody)
	if err != nil {
		return resp.StatusCode, resp.Header, text, err
	}
	return resp.StatusCode, resp.Header, text, nil
}

// probeOnce sends the proxy-form (absolute-URI) probe to one literal IP.
// Uses Transport.Proxy so net/http emits an absolute request target while we
// still dial a fixed address — the exact wire shape the campus Squid needs.
func (c *Client) probeOnce(ctx context.Context, ip string) (int, http.Header, string, error) {
	proxyURL := &url.URL{Scheme: "http", Host: net.JoinHostPort(ip, orDefault(c.ProbePort, "80"))}
	tr := &http.Transport{
		DialContext:       c.dialer().DialContext,
		Proxy:             http.ProxyURL(proxyURL),
		DisableKeepAlives: true,
		ForceAttemptHTTP2: false,
	}
	client := &http.Client{Transport: tr, Timeout: 4 * time.Second}
	req, err := http.NewRequestWithContext(ctx, "GET", c.checkURL(), nil)
	if err != nil {
		return 0, nil, "", err
	}
	req.Header.Set("User-Agent", UserAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
	req.Header.Set("Connection", "close")
	resp, err := client.Do(req)
	if err != nil {
		return 0, nil, "", err
	}
	defer resp.Body.Close()
	text, _ := readCapped(resp.Body, MaxBody)
	return resp.StatusCode, resp.Header, text, nil
}

// Probe runs the absolute-URI probe over every IP literal until one answers.
// Returns (status, body, err). 204 == authenticated online. Total budget:
// ProbeBudget (one dial ≤ ~2 s), so Detect stays responsive.
func (c *Client) Probe() (int, http.Header, string, error) {
	if len(c.ProbeIPs) == 0 {
		c.ProbeIPs = strings.Split(CheckProbeIPs, ",")
	}
	deadline := time.Now().Add(ProbeBudget)
	var lastErr error
	for round := 0; round < 2; round++ {
		for _, ip := range c.ProbeIPs {
			if time.Now().After(deadline) {
				break
			}
			ctx, cancel := context.WithTimeout(context.Background(), ProbeBudget)
			st, hdr, body, err := c.probeOnce(ctx, ip)
			cancel()
			if err != nil {
				lastErr = err
				continue
			}
			return st, hdr, body, nil
		}
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no probe endpoint answered")
	}
	return 0, nil, "", lastErr
}

// ProbeOnline reports whether the probe says we are authenticated (204).
func (c *Client) ProbeOnline() (bool, error) {
	st, _, _, err := c.Probe()
	if err != nil {
		return false, err
	}
	return st == 204, nil
}

// trusted reports whether credentials may be sent to this URL's host.
// A hostile Wi-Fi can serve a fake /userportal page; pinning the host means
// the password can only go to the real campus portal.
func (c *Client) trusted(rawURL string) bool {
	u, err := url.Parse(rawURL)
	if err != nil {
		return false
	}
	wantHost, wantPort := hostPort(c.PortalHost)
	return u.Hostname() == wantHost && (u.Port() == "" || u.Port() == wantPort)
}

func hostPort(hostport string) (string, string) {
	if i := strings.LastIndex(hostport, ":"); i >= 0 && !strings.Contains(hostport, "]") {
		return hostport[:i], hostport[i+1:]
	}
	return hostport, "80"
}

func (c *Client) base() string { return "http://" + c.PortalHost }

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

func cookieFrom(hdr http.Header) string {
	for _, v := range hdr.Values("Set-Cookie") {
		if strings.HasPrefix(strings.ToUpper(v), "JSESSIONID=") {
			return strings.SplitN(v, ";", 2)[0]
		}
	}
	return ""
}

// Timing accessors (zero value = production default; shortened in tests).
func (c *Client) verifyProbesN() int {
	if c.VerifyProbes > 0 {
		return c.VerifyProbes
	}
	return 6
}

func (c *Client) verifyGapD() time.Duration {
	if c.VerifyGap > 0 {
		return c.VerifyGap
	}
	return 1500 * time.Millisecond
}

func (c *Client) staleWaitD() time.Duration {
	if c.StaleWait > 0 {
		return c.StaleWait
	}
	return 2 * time.Second
}

func (c *Client) closeWaitD() time.Duration {
	if c.CloseWait > 0 {
		return c.CloseWait
	}
	return 800 * time.Millisecond
}

func (c *Client) logoutWaitD() time.Duration {
	if c.LogoutWait > 0 {
		return c.LogoutWait
	}
	return 1500 * time.Millisecond
}
