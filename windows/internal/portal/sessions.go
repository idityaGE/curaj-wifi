package portal

import (
	"fmt"
	"html"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// Session is one row of /userportal/currentSessions.do?from=Home.
type Session struct {
	SesNo     string `json:"sesno"`
	Name      string `json:"name"`
	AccountID string `json:"account_id"`
	RatePlan  string `json:"rate_plan"`
	NetID     string `json:"net_id"`
	StartTime string `json:"start_time"`
	Duration  string `json:"duration"`
	Upload    string `json:"upload"`
	Download  string `json:"download"`
	Total     string `json:"total"`
}

// Display is the human anchor used in the tray: name · duration.
func (s Session) Display() string {
	return fmt.Sprintf("%s · %s", s.Name, s.Duration)
}

var (
	sessionRowRe = regexp.MustCompile(`(?is)<tr[^>]*>(.*?)</tr>`)
	callCloseRe  = regexp.MustCompile(`callClose\((\d+)\)`)
	cellRe       = regexp.MustCompile(`(?is)<td[^>]*>(.*?)</td>`)
	tagRe        = regexp.MustCompile(`(?is)<[^>]+>`)
	spaceRe      = regexp.MustCompile(`\s+`)
)

func cleanCell(raw string) string {
	t := tagRe.ReplaceAllString(raw, " ")
	t = html.UnescapeString(t)
	t = strings.ReplaceAll(t, "\u00a0", " ")
	return spaceRe.ReplaceAllString(t, " ")
}

// parseSessions parses the current-session table without an HTML parser.
func parseSessions(page string) []Session {
	var out []Session
	for _, row := range sessionRowRe.FindAllStringSubmatch(page, -1) {
		id := callCloseRe.FindStringSubmatch(row[1])
		if id == nil {
			continue
		}
		cells := cleanCellAll(cellRe.FindAllStringSubmatch(row[1], -1))
		if len(cells) < 9 {
			continue
		}
		out = append(out, Session{
			SesNo:     id[1],
			Name:      cells[0],
			AccountID: cells[1],
			RatePlan:  cells[2],
			NetID:     cells[3],
			StartTime: cells[4],
			Duration:  cells[5],
			Upload:    cells[6],
			Download:  cells[7],
			Total:     cells[8],
		})
	}
	return out
}

func cleanCellAll(cells [][]string) []string {
	out := make([]string, 0, len(cells))
	for _, c := range cells {
		out = append(out, cleanCell(c[1]))
	}
	return out
}

// portalContextCookie returns a JSESSIONID bound to this client's portal
// context. The self-care page on its own issues a cookie that can *list*
// sessions but cannot *close* them: logout.do answers 302 to the NAS splash
// and the session stays alive. The cookie obtained from the portal splash
// URL (the same first hop as a normal login) carries ip/mac/nas/sc context,
// and that is what the portal checks before allowing a session close.
func (c *Client) portalContextCookie() string {
	portal := c.portalURLAny()
	if portal == "" || !c.trusted(portal) {
		return ""
	}
	_, hdr, _, err := c.httpDo("GET", portal, nil, nil, 8*time.Second)
	if err != nil {
		return ""
	}
	return cookieFrom(hdr)
}

// selfCareCookie opens a self-care session. `type=1` is the account-
// management login: it lists sessions even at the two-session limit and
// does not create another internet session.
func (c *Client) selfCareCookie(creds Creds) string {
	loginPage := c.base() + SelfCareLoginPage
	cookie := c.portalContextCookie()
	if cookie == "" {
		// Fallback for listing when the NAS splash is unavailable. Closing a
		// session will fail verification with this cookie, which is safer
		// than pretending the close worked.
		_, hdr, _, err := c.httpDo("GET", loginPage, nil, nil, 8*time.Second)
		if err != nil {
			return ""
		}
		cookie = cookieFrom(hdr)
		if cookie == "" {
			return ""
		}
	}
	form := url.Values{
		"generatePassword": {"true"},
		"phone":            {"0"},
		"type":             {"1"},
		"username":         {creds.Username},
		"password":         {creds.Password},
	}
	_, hdr, _, err := c.httpDo("POST", c.base()+SelfCareLoginPath, map[string]string{
		"Cookie":       cookie,
		"Content-Type": "application/x-www-form-urlencoded",
		"Referer":      loginPage,
		"Origin":       c.base(),
	}, []byte(form.Encode()), 10*time.Second)
	if err != nil {
		return ""
	}
	if ck := cookieFrom(hdr); ck != "" {
		return ck
	}
	return cookie
}

func (c *Client) fetchSessionsPage(cookie string) (string, error) {
	_, _, body, err := c.httpDo("GET", c.base()+CurrentSessionsPath, map[string]string{
		"Cookie":  cookie,
		"Referer": c.base() + SelfCareSessionsPage,
	}, nil, 8*time.Second)
	return body, err
}

// selfCareSnapshot authenticates to self-care and lists sessions.
// Returns (ok, cookie, sessions, message).
func (c *Client) selfCareSnapshot(creds Creds) (bool, string, []Session, string) {
	cookie := c.selfCareCookie(creds)
	if cookie == "" {
		return false, "", nil, "self-care login issued no JSESSIONID"
	}
	page, err := c.fetchSessionsPage(cookie)
	if err != nil {
		return false, "", nil, fmt.Sprintf("session list failed: %v", err)
	}
	if strings.Contains(strings.ToLower(page), "session has been expired") {
		cookie = c.selfCareCookie(creds)
		if cookie == "" {
			return false, "", nil, "self-care session expired"
		}
		page, err = c.fetchSessionsPage(cookie)
		if err != nil {
			return false, "", nil, fmt.Sprintf("session list failed: %v", err)
		}
	}
	low := strings.ToLower(page)
	if strings.Contains(low, "session has been expired") {
		return false, "", nil, "self-care login failed (check the saved password)"
	}
	if !regexp.MustCompile(`(?i)current session|items found`).MatchString(page) {
		snippet := spaceRe.ReplaceAllString(page, " ")
		snippet = strings.TrimSpace(snippet)
		if len(snippet) > 160 {
			snippet = snippet[:160]
		}
		return false, "", nil, "unexpected session page: " + snippet
	}
	return true, cookie, parseSessions(page), ""
}

// ListSessions lists the account's active internet sessions.
func (c *Client) ListSessions(creds Creds) ([]Session, error) {
	ok, _, sessions, msg := c.selfCareSnapshot(creds)
	if !ok {
		return nil, fmt.Errorf("%s", msg)
	}
	return sessions, nil
}

// CloseSession closes one specific session and confirms it disappeared
// from the live list. The server answers 200 when it really closed; an
// invalid/unauthorised id gets 302 to the NAS splash and the session stays.
func (c *Client) CloseSession(creds Creds, sesno string) (bool, string) {
	if !regexp.MustCompile(`^\d+$`).MatchString(sesno) {
		return false, "session id must be numeric"
	}
	ok, cookie, sessions, msg := c.selfCareSnapshot(creds)
	if !ok || cookie == "" {
		return false, firstNonEmpty(msg, "could not open self-care session")
	}
	found := false
	for _, s := range sessions {
		if s.SesNo == sesno {
			found = true
			break
		}
	}
	if !found {
		return false, fmt.Sprintf("session %s is not active", sesno)
	}

	closedURL := c.base() + "/userportal/logout.do?from=curses&sesno=" + sesno
	status, _, _, err := c.httpDo("POST", closedURL, map[string]string{
		"Cookie":       cookie,
		"Content-Type": "application/x-www-form-urlencoded",
		"Referer":      c.base() + CurrentSessionsPath,
		"Origin":       c.base(),
	}, []byte{}, 8*time.Second)
	if err != nil {
		return false, fmt.Sprintf("logout request failed: %v", err)
	}

	for i := 0; i < 4; i++ {
		time.Sleep(c.closeWaitD())
		page, err := c.fetchSessionsPage(cookie)
		if err == nil {
			low := strings.ToLower(page)
			if !strings.Contains(low, "session has been expired") &&
				regexp.MustCompile(`(?i)current session|items found`).MatchString(page) {
				after := parseSessions(page)
				if !containsSesNo(after, sesno) {
					return true, fmt.Sprintf("closed session %s", sesno)
				}
				continue
			}
		}
		// The context/self-care cookie may have expired; re-open and re-check.
		ok2, cookie2, after, _ := c.selfCareSnapshot(creds)
		if ok2 && !containsSesNo(after, sesno) {
			return true, fmt.Sprintf("closed session %s", sesno)
		}
		if ok2 && cookie2 != "" {
			cookie = cookie2
		}
	}
	return false, fmt.Sprintf("portal did not close session %s (HTTP %d)", sesno, status)
}

func containsSesNo(list []Session, sesno string) bool {
	for _, s := range list {
		if s.SesNo == sesno {
			return true
		}
	}
	return false
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}
