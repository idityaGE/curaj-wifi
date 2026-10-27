package config

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// ErrNotConfigured means no config file exists yet (first run).
var ErrNotConfigured = errors.New("not configured")

// Data is the service configuration.
type Data struct {
	Username    string `json:"-"`
	Password    string `json:"-"`
	AutoConnect bool   `json:"auto_connect"`
	SSID        string `json:"ssid,omitempty"`
}

// disk is the on-disk JSON. Username/password hold DPAPI(LocalMachine)
// ciphertext, base64. enc records the scheme so a future scheme change
// can be detected.
type disk struct {
	Username    string `json:"username"`
	Password    string `json:"password"`
	AutoConnect bool   `json:"auto_connect"`
	SSID        string `json:"ssid,omitempty"`
	Enc         string `json:"enc"`
}

// Protector encrypts/decrypts opaque blobs. Production (Windows) uses
// DPAPI LocalMachine scope; the !windows build only exists for tests.
type Protector interface {
	Protect(plain []byte) ([]byte, error)
	Unprotect(cipher []byte) ([]byte, error)
}

// Store reads/writes the config file in Dir.
type Store struct {
	Dir string
}

// DefaultDir is C:\ProgramData\curaj-wifi (or %CURAJ_WIFI_DATA% for tests).
func DefaultDir() string {
	if d := os.Getenv("CURAJ_WIFI_DATA"); d != "" {
		return d
	}
	pd := os.Getenv("ProgramData")
	if pd == "" {
		pd = `C:\ProgramData`
	}
	return filepath.Join(pd, "curaj-wifi")
}

func (s *Store) path() string { return filepath.Join(s.Dir, "config.json") }

func (s *Store) Exists() bool {
	_, err := os.Stat(s.path())
	return err == nil
}

// Load reads and decrypts the configuration. Returns ErrNotConfigured when
// the file does not exist.
func (s *Store) Load(p Protector) (Data, error) {
	raw, err := os.ReadFile(s.path())
	if os.IsNotExist(err) {
		return Data{}, ErrNotConfigured
	}
	if err != nil {
		return Data{}, err
	}
	var d disk
	if err := json.Unmarshal(raw, &d); err != nil {
		return Data{}, fmt.Errorf("corrupt config: %w", err)
	}
	out := Data{
		AutoConnect: d.AutoConnect,
		SSID:        d.SSID,
	}
	if d.Enc != "dpapi-lm" {
		return out, fmt.Errorf("unknown credential scheme %q", d.Enc)
	}
	if d.Username == "" || d.Password == "" {
		return out, fmt.Errorf("config missing username or password")
	}
	ub, err := base64.StdEncoding.DecodeString(d.Username)
	if err != nil {
		return out, fmt.Errorf("bad username blob: %w", err)
	}
	pb, err := base64.StdEncoding.DecodeString(d.Password)
	if err != nil {
		return out, fmt.Errorf("bad password blob: %w", err)
	}
	ub, err = p.Unprotect(ub)
	if err != nil {
		return out, fmt.Errorf("cannot decrypt username: %w", err)
	}
	pb, err = p.Unprotect(pb)
	if err != nil {
		return out, fmt.Errorf("cannot decrypt password: %w", err)
	}
	out.Username = string(ub)
	out.Password = string(pb)
	if out.SSID == "" {
		out.SSID = "CURAJ CAMPUS CONNECT"
	}
	return out, nil
}

// Save encrypts and writes the configuration. The file is written with
// restrictive content; ACL tightening (SYSTEM+Administrators only) is done
// separately by the installer/TightenACL.
func (s *Store) Save(d Data, p Protector) error {
	ub, err := p.Protect([]byte(d.Username))
	if err != nil {
		return fmt.Errorf("protect username: %w", err)
	}
	pb, err := p.Protect([]byte(d.Password))
	if err != nil {
		return fmt.Errorf("protect password: %w", err)
	}
	dd := disk{
		Username:    base64.StdEncoding.EncodeToString(ub),
		Password:    base64.StdEncoding.EncodeToString(pb),
		AutoConnect: d.AutoConnect,
		SSID:        d.SSID,
		Enc:         "dpapi-lm",
	}
	raw, err := json.MarshalIndent(dd, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(s.Dir, 0o750); err != nil {
		return err
	}
	tmp := s.path() + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o640); err != nil {
		return err
	}
	return os.Rename(tmp, s.path())
}
