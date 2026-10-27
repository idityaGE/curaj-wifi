// Package api is the loopback control channel between the service (agent)
// and the tray/CLI clients: localhost HTTP with a bearer token.
//
// HTTP was chosen over named pipes (plan B.2): debuggable with curl,
// trivial in Go, no firewall prompt on loopback, ACL via token file.
package api

import (
	"encoding/hex"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"curaj-wifi/internal/agent"
)

// Ports: fixed base with an automatic fallback range. The chosen port is
// recorded in a user-readable file the tray reads.
const (
	PortBase = 47821
	PortMax  = 47830
)

// TokenFileName / PortFileName live in the data dir.
const (
	TokenFileName = "api-token.txt"
	PortFileName  = "port.txt"
)

// GenerateToken returns 32 random bytes as hex.
func GenerateToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return hex.EncodeToString(b), nil
}

// WriteToken atomically writes the token file (mode 0600; the installer
// additionally grants Users read so the unelevated tray can use it).
func WriteToken(dir, token string) error {
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return err
	}
	tmp := filepath.Join(dir, TokenFileName+".tmp")
	if err := os.WriteFile(tmp, []byte(token), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(dir, TokenFileName))
}

// LoadToken reads the token file.
func LoadToken(dir string) (string, error) {
	b, err := os.ReadFile(filepath.Join(dir, TokenFileName))
	if err != nil {
		return "", fmt.Errorf("no API token: %w", err)
	}
	t := strings.TrimSpace(string(b))
	if len(t) < 32 {
		return "", fmt.Errorf("API token file is too short")
	}
	return t, nil
}

// WritePort records the port the service bound.
func WritePort(dir string, port int) error {
	if err := os.MkdirAll(dir, 0o750); err != nil {
		return err
	}
	tmp := filepath.Join(dir, PortFileName+".tmp")
	if err := os.WriteFile(tmp, []byte(strconv.Itoa(port)), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, filepath.Join(dir, PortFileName))
}

// LoadPort reads the recorded port (0 = unknown).
func LoadPort(dir string) int {
	b, err := os.ReadFile(filepath.Join(dir, PortFileName))
	if err != nil {
		return 0
	}
	p, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil || p < 1 || p > 65535 {
		return 0
	}
	return p
}

// checkAuth validates the bearer token (constant-time).
func checkAuth(token string) func(http.Handler) http.Handler {
	want := "Bearer " + token
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			got := r.Header.Get("Authorization")
			if len(got) != len(want) ||
				subtle.ConstantTimeCompare([]byte(got), []byte(want)) != 1 {
					w.WriteHeader(http.StatusUnauthorized)
					return
				}
			next.ServeHTTP(w, r)
		})
	}
}

// listen binds 127.0.0.1 on the first free port in [PortBase, PortMax].
func listen() (net.Listener, int, error) {
	var lastErr error
	for p := PortBase; p <= PortMax; p++ {
		ln, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(p)))
		if err == nil {
			return ln, p, nil
		}
		lastErr = err
	}
	return nil, 0, fmt.Errorf("no free API port in [%d,%d]: %v", PortBase, PortMax, lastErr)
}

// Submitter is the agent surface the server drives.
type Submitter interface {
	Submit(kind, sesno string) <-chan agent.CmdResult
	Snapshot() agent.Snapshot
}

// cmdTimeout is how long handlers wait for the single-writer loop to
// execute a command before answering 504.
const cmdTimeout = 45 * time.Second

// Server serves the control API on loopback only.
type Server struct {
	sub    Submitter
	authN  func(http.Handler) http.Handler
	srv    *http.Server
	mux    *http.ServeMux
	ln     net.Listener
	port   int
}

// NewServer builds (not yet started) the API server.
func NewServer(sub Submitter, token string) *Server {
	mux := http.NewServeMux()
	s := &Server{
		sub:   sub,
		authN: checkAuth(token),
		mux:   mux,
		srv:   &http.Server{Handler: mux, ReadTimeout: 60 * time.Second},
	}
	mux.Handle("/status", s.authN(http.HandlerFunc(s.handleStatus)))
	mux.Handle("/login", s.authN(http.HandlerFunc(s.handleCmd(agent.CmdLoginNow))))
	mux.Handle("/logout", s.authN(http.HandlerFunc(s.handleCmd(agent.CmdLogout))))
	mux.Handle("/try-again", s.authN(http.HandlerFunc(s.handleCmd(agent.CmdTryAgain))))
	mux.Handle("/sessions", s.authN(http.HandlerFunc(s.handleSessions)))
	mux.Handle("/sessions/close", s.authN(http.HandlerFunc(s.handleClose)))
	return s
}

// handler exposes the routed mux (used by tests via httptest).
func (s *Server) handler() http.Handler { return s.mux }

// Start binds loopback and serves in the background.
func (s *Server) Start() (port int, err error) {
	ln, port, err := listen()
	if err != nil {
		return 0, err
	}
	s.ln = ln
	s.port = port
	go s.srv.Serve(ln)
	return port, nil
}

// Stop shuts the server down.
func (s *Server) Stop() {
	if s.srv != nil {
		s.srv.Close()
	}
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, s.sub.Snapshot())
}

// handleCmd runs one command and returns its (awaited) result, so clients
// get truthful feedback rather than fire-and-forget.
func (s *Server) handleCmd(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		res, err := await(s.sub.Submit(kind, ""), cmdTimeout)
		if err != nil {
			writeJSON(w, 504, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, res)
	}
}

func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		res, err := await(s.sub.Submit(agent.CmdRefreshSessions, ""), cmdTimeout)
		if err != nil {
			writeJSON(w, 504, map[string]string{"error": err.Error()})
			return
		}
		writeJSON(w, 200, res)
	default: // GET returns the cached snapshot section
		snap := s.sub.Snapshot()
		writeJSON(w, 200, map[string]any{
			"sessions":     snap.Sessions,
			"fetched_at":   snap.SessionsAt,
			"busy":         snap.SessionsBusy,
			"state":        snap.State,
		})
	}
}

func (s *Server) handleClose(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Sesno string `json:"sesno"`
	}
	if err := decodeJSON(r, &body); err != nil || body.Sesno == "" {
		writeJSON(w, 400, map[string]string{"error": "need {\"sesno\":\"<id>\"}"})
		return
	}
	res, err := await(s.sub.Submit(agent.CmdCloseSession, body.Sesno), cmdTimeout)
	if err != nil {
		writeJSON(w, 504, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, res)
}

func await(ch <-chan agent.CmdResult, timeout time.Duration) (agent.CmdResult, error) {
	select {
	case res := <-ch:
		return res, nil
	case <-time.After(timeout):
		return agent.CmdResult{}, fmt.Errorf("service busy — command timed out")
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func decodeJSON(r *http.Request, v any) error {
	defer r.Body.Close()
	return json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(v)
}
