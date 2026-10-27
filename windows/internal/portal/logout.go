package portal

import (
	"fmt"
	"net/url"
	"time"
)

// Logout performs a real logout and verifies it on the network.
//
// A bare GET /logout or /logout.do is a no-op: the portal can only tear the
// session down when the request carries the JSESSIONID that was bound to the
// NAS session by newlogin.do. So: discover the portal (works while online
// too), open a session, bind it with the credentials, then logout.do with
// that cookie, then the NAS logout as a fallback. Finally verify with the
// probe — a 400 from the Squid is NOT logged-out.
func (c *Client) Logout(creds Creds) (bool, string) {
	if creds.Username == "" || creds.Password == "" {
		return false, "no credentials configured"
	}
	var msgs []string
	portal := c.portalURLAny()
	if portal == "" {
		return false, "campus portal not reachable"
	}
	if !c.trusted(portal) {
		u, _ := url.Parse(portal)
		return false, fmt.Sprintf("untrusted portal host %q", u.Hostname())
	}
	netloc := u_host(portal)

	// Fresh JSESSIONID.
	_, hdr, _, err := c.httpDo("GET", portal, nil, nil, 8*time.Second)
	if err != nil {
		msgs = append(msgs, fmt.Sprintf("session: %v", err))
	} else if ck := cookieFrom(hdr); ck != "" {
		msgs = append(msgs, "session: JSESSIONID")
		// "Session already running" is the normal answer here; the useful
		// side effect is that this JSESSIONID becomes bound to the live NAS
		// session, which is what makes logout.do work.
		form := url.Values{
			"username":     {creds.Username},
			"password":     {creds.Password},
			"phone":        {"0"},
			"type":         {"2"},
			"jsonresponse": {"1"},
		}
		_, _, _, _ = c.httpDo("POST", "http://"+netloc+SelfCareLoginPath,
			map[string]string{
				"Cookie":       ck,
				"Content-Type": "application/x-www-form-urlencoded",
				"Referer":      "http://" + netloc + LoginHTMLReferer,
				"Origin":       "http://" + netloc,
			}, []byte(form.Encode()), 8*time.Second)

		_, _, _, err = c.httpDo("GET", "http://"+netloc+"/userportal/logout.do",
			map[string]string{"Cookie": ck}, nil, 6*time.Second)
		if err != nil {
			msgs = append(msgs, fmt.Sprintf("logout.do -> %v", err))
		} else {
			msgs = append(msgs, "logout.do -> ok")
		}
	} else {
		msgs = append(msgs, "session: none")
	}

	_, _, _, _ = c.httpDo("GET", c.nasLogout(), nil, nil, 6*time.Second)
	msgs = append(msgs, "nas/logout -> sent")

	time.Sleep(c.logoutWaitD())
	online, err := c.ProbeOnline()
	if err != nil {
		// A blackhole right after teardown is not "online"; treat as logged out
		// only if a second attempt also fails to show 204.
		online, _ = c.ProbeOnline()
	}
	return !online, joinMsgs(msgs)
}

// LogoutQuiet is Logout without caring about the message (cleanup path).
func (c *Client) LogoutQuiet(creds Creds) {
	_, _ = c.Logout(creds)
}

func u_host(rawURL string) string {
	u, err := url.Parse(rawURL)
	if err != nil || u.Host == "" {
		return c_defaultHost()
	}
	return u.Host
}

func c_defaultHost() string { return DefaultPortalHost }

func joinMsgs(msgs []string) string {
	out := ""
	for i, m := range msgs {
		if i > 0 {
			out += "; "
		}
		out += m
	}
	return out
}
