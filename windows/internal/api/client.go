package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"curaj-wifi/internal/agent"
	"curaj-wifi/internal/config"
)

// Client is the tray/CLI side of the control channel.
type Client struct {
	Token string
	// Ports to try in order; the recorded port is tried first.
	Ports   []int
	HTTP    *http.Client
	DataDir string
}

// DefaultDataDir is the service's data directory.
func DefaultDataDir() string { return config.DefaultDir() }

// NewClientFromFiles builds a client from the recorded port + token file.
func NewClientFromFiles(dir string) (*Client, error) {
	token, err := LoadToken(dir)
	if err != nil {
		return nil, err
	}
	ports := []int{}
	if p := LoadPort(dir); p > 0 {
		ports = append(ports, p)
	}
	for p := PortBase; p <= PortMax; p++ {
		ports = append(ports, p)
	}
	return &Client{
		Token:   token,
		Ports:   ports,
		DataDir: dir,
		HTTP:    &http.Client{Timeout: 60 * time.Second},
	}, nil
}

func (c *Client) url(port int, path string) string {
	return fmt.Sprintf("http://127.0.0.1:%d%s", port, path)
}

// call tries each candidate port until one answers (not a connection error).
func (c *Client) call(method, path string, body any) ([]byte, int, error) {
	var payload io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, 0, err
		}
		payload = bytes.NewReader(b)
	}
	var lastErr error
	for _, port := range c.Ports {
		req, err := http.NewRequest(method, c.url(port, path), payload)
		if err != nil {
			return nil, 0, err
		}
		req.Header.Set("Authorization", "Bearer "+c.Token)
		if body != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		resp, err := c.HTTP.Do(req)
		if err != nil {
			lastErr = err
			continue
		}
		data, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		resp.Body.Close()
		if err != nil {
			return nil, resp.StatusCode, err
		}
		// Wrong port may still hold a service from another install: 401 on
		// the wrong token means keep scanning.
		if resp.StatusCode == http.StatusUnauthorized {
			lastErr = fmt.Errorf("unauthorized on port %d", port)
			continue
		}
		return data, resp.StatusCode, nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("service not reachable")
	}
	return nil, 0, fmt.Errorf("cannot reach the curaj-wifi service: %v", lastErr)
}

func (c *Client) callCmd(method, path string, body any) (agent.CmdResult, error) {
	data, status, err := c.call(method, path, body)
	if err != nil {
		return agent.CmdResult{}, err
	}
	if status != 200 {
		var e struct {
			Error string `json:"error"`
		}
		_ = json.Unmarshal(data, &e)
		return agent.CmdResult{}, fmt.Errorf(firstNonEmpty(e.Error, fmt.Sprintf("HTTP %d", status)))
	}
	var res agent.CmdResult
	if err := json.Unmarshal(data, &res); err != nil {
		return agent.CmdResult{}, err
	}
	return res, nil
}

// Status returns the current snapshot.
func (c *Client) Status() (agent.Snapshot, error) {
	data, status, err := c.call("GET", "/status", nil)
	if err != nil {
		return agent.Snapshot{}, err
	}
	if status != 200 {
		return agent.Snapshot{}, fmt.Errorf("status HTTP %d", status)
	}
	var snap agent.Snapshot
	if err := json.Unmarshal(data, &snap); err != nil {
		return agent.Snapshot{}, err
	}
	return snap, nil
}

// LoginNow = "Sign in now" (re-enables Auto connect).
func (c *Client) LoginNow() (agent.CmdResult, error) { return c.callCmd("POST", "/login", nil) }

// TryAgain resets the backoff and attempts immediately.
func (c *Client) TryAgain() (agent.CmdResult, error) { return c.callCmd("POST", "/try-again", nil) }

// Logout performs the verified logout.
func (c *Client) Logout() (agent.CmdResult, error) { return c.callCmd("POST", "/logout", nil) }

// RefreshSessions refreshes the cached session list.
func (c *Client) RefreshSessions() error {
	_, err := c.callCmd("POST", "/sessions", nil)
	return err
}

// Sessions returns the cached session list (optionally refreshing first).
func (c *Client) Sessions(refresh bool) ([]portalSession, string, error) {
	if refresh {
		if err := c.RefreshSessions(); err != nil {
			return nil, "", err
		}
	}
	data, status, err := c.call("GET", "/sessions", nil)
	if err != nil {
		return nil, "", err
	}
	if status != 200 {
		return nil, "", fmt.Errorf("sessions HTTP %d", status)
	}
	var res struct {
		Sessions  []portalSession `json:"sessions"`
		FetchedAt string          `json:"fetched_at"`
	}
	if err := json.Unmarshal(data, &res); err != nil {
		return nil, "", err
	}
	return res.Sessions, res.FetchedAt, nil
}

// CloseSession closes one session (verified by the service).
func (c *Client) CloseSession(sesno string) (agent.CmdResult, error) {
	return c.callCmd("POST", "/sessions/close", map[string]string{"sesno": sesno})
}

// CallCmdFor runs one of the named commands (CLI helper).
func (c *Client) CallCmdFor(kind string) (agent.CmdResult, error) {
	switch kind {
	case "login":
		return c.LoginNow()
	case "logout":
		return c.Logout()
	case "try-again":
		return c.TryAgain()
	default:
		return agent.CmdResult{}, fmt.Errorf("unknown command %q", kind)
	}
}

// WaitForState polls /status until pred is true or the timeout elapses.
// Used after commands to observe the truthful end state.
func (c *Client) WaitForState(timeout time.Duration, pred func(agent.Snapshot) bool) (agent.Snapshot, bool) {
	deadline := time.Now().Add(timeout)
	for {
		snap, err := c.Status()
		if err == nil && pred(snap) {
			return snap, true
		}
		if time.Now().After(deadline) {
			return snap, false
		}
		time.Sleep(500 * time.Millisecond)
	}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

// portalSession mirrors portal.Session without importing it here (the JSON
// shape is the contract).
type portalSession = struct {
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
