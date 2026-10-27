// Package scrub redacts secrets from log lines. Every log write in the
// service goes through NewLogger, which is the single choke point.
package scrub

import (
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
)

var patterns = []*regexp.Regexp{
	regexp.MustCompile(`(?i)(password=)[^&\s"']*`),      // form bodies
	regexp.MustCompile(`(?i)(username=)[^&\s"']*`),     // form bodies (phone/username)
	regexp.MustCompile(`(?i)(password["']?\s*[:=]\s*["']?)[^&\s"',}]*`),
	regexp.MustCompile(`(?i)(JSESSIONID=)[^;\s"']+`),   // session cookies
	regexp.MustCompile(`(?i)(&?sc=)[a-f0-9]{8,}`),      // portal context token
	regexp.MustCompile(`(?i)(Cookie:\s?)[^\r\n]+`),
	regexp.MustCompile(`(?i)(generatePassword=)[^&\s]*`),
}

// Redact removes credentials from one log line. The username is redacted by
// the logger when known (extraSecrets), since it appears in plain prose.
func Redact(line string, extraSecrets ...string) string {
	for _, re := range patterns {
		line = re.ReplaceAllString(line, "${1}***")
	}
	for _, s := range extraSecrets {
		if len(s) >= 4 {
			line = strings.ReplaceAll(line, s, "***")
		}
	}
	return line
}

// Logger writes scrubbed lines to a rotating file (and mirrors to stderr in
// console mode). Secrets passed via WithSecret are redacted everywhere.
type Logger struct {
	mu       sync.Mutex
	w        io.Writer
	path     string
	secrets  []string
	maxBytes int64
	console  bool
}

// NewFile creates a scrubbed file logger with simple size rotation
// (curaj-wifi.log -> curaj-wifi.old at maxBytes).
func NewFile(path string, maxBytes int64, console bool) (*Logger, error) {
	if maxBytes <= 0 {
		maxBytes = 5 << 20
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return nil, err
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640)
	if err != nil {
		return nil, err
	}
	return &Logger{w: f, path: path, maxBytes: maxBytes, console: console}, nil
}

func (l *Logger) WithSecret(s string) {
	if len(s) >= 4 {
		l.mu.Lock()
		l.secrets = append(l.secrets, s)
		l.mu.Unlock()
	}
}

func (l *Logger) Writef(format string, args ...any) {
	l.mu.Lock()
	defer l.mu.Unlock()
	msg := Redact(format, l.secrets...)
	if !strings.HasSuffix(msg, "\n") {
		msg += "\n"
	}
	l.w.Write([]byte(msg))
	l.checkRotate()
	if l.console {
		os.Stderr.WriteString(msg)
	}
}

func (l *Logger) checkRotate() {
	f, ok := l.w.(*os.File)
	if !ok {
		return
	}
	if st, err := f.Stat(); err != nil || st.Size() < l.maxBytes {
		return
	}
	f.Close()
	os.Rename(l.path, strings.TrimSuffix(l.path, ".log")+".old")
	nf, err := os.OpenFile(l.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o640)
	if err != nil {
		return
	}
	l.w = nf
}

// Path returns the log file path (for "View log").
func (l *Logger) Path() string { return l.path }
