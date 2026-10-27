# Windows client — architecture, UI, and troubleshooting

`windows/` holds the first Windows client for the CURAJ CAMPUS CONNECT
portal: one Go exe that runs as a **Windows service** (the network agent)
plus a **tray client** (a pure view), distributed via an `irm | iex`
PowerShell script. First release: `win-v1.0`.

```
windows/
  go.mod
  main.go                  mode dispatch: service|tray|install|uninstall|
                           config|status|login|logout|try-again|sessions|
                           close|log|version
  internal/portal/         the portal protocol (portable, unit-tested on Linux)
  internal/config/         DPAPI LocalMachine-encrypted config
  internal/agent/          the state machine (single writer of portal traffic)
  internal/api/            loopback control channel (HTTP + bearer token)
  internal/scrub/          the log scrubber (single choke point)
  internal/app/            wiring: store + portal + agent + api
  internal/svc/            service wrapper + install helpers (windows only)
  internal/tray/           tray icon, menus, dialogs (thin Win32)
  internal/icons/          5 tinted .ico files (generated) + embed
  tools/genicons/          draws the Wi-Fi glyph icons at build time
  install.ps1 uninstall.ps1 build.sh
```

## Why a service + a thin tray (not everything in the tray)

The service runs at boot, before anyone logs in, and survives logoff; a
tray app would die at logoff and miss pre-logon re-authentication. This is
the Tailscale pattern: a system daemon that owns the network, and a user
session client that only *shows* state and *posts commands*.

Critically, the service is the **single writer of all portal traffic**.
Two simultaneous portal clients (tray doing its own logins) are exactly
what produced the duplicate-auth / stale-session races on Android. Here
the tray never talks to the portal — it talks to the service over
`http://127.0.0.1:47821` (fallback ports 47822–47830, chosen port recorded
in `port.txt`) with a bearer token (`api-token.txt`).

## The state machine

```
NoWifi ──(campus probe intercepted)──► Captive ──(auto connect on)──►
Authenticating ──► Online | SessionLimit | Failed(backoff)

Online ──(60 s poll: portal TTL drop)──► Captive ──► Authenticating
Failed ──(backoff 20s → 40s → … → 10 min cap)──► Authenticating
LoggingOut ──(verified)──► AutoConnectOff
```

- The probe is the ground truth: proxy-form (absolute-URI) request to
  `connectivitycheck.gstatic.com/generate_204` over fixed IP literals —
  `204` = online, splash page = captive, no answer = not on campus Wi-Fi.
  The gateway Squid rejects origin-form requests with `400
  ERR_INVALID_URL` even when authenticated (verified live 2026-10-27), so
  the probe MUST be proxy-form or "online" looks permanently blocked.
- Backoff: 20 s base, doubling, capped at 10 min. `Try again` resets it.
- "authentication already in progress": wait 6×1 s, then retry in 3 s —
  never double-post (the OS captive-portal helper may be mid-flight).
- `redirect_to_nas` / "Session already running": clear the stale portal +
  NAS session, retry once (depth-limited).
- Config is re-read every tick, so `Set up account…` takes effect without
  restarting the service.

## The tray (UI contract)

Same Wi-Fi glyph in all states, tinted: green (online), amber (signing in
/ session limit), red (failed), white (auto connect off / not set up),
gray (not on campus Wi-Fi). One truthful status line is always the second
menu row; it reflects the service's last *verified* state.

| state | icon | status line | notable menu items |
|---|---|---|---|
| online | green | `● Online · 10.50.0.247` | `Log out…` |
| signing in | amber | `● Signing in… (attempt 2)` | — |
| session limit | amber | `● 2 sessions active — close one` | session rows → confirm → close → **auto-retry login** |
| failed | red | `● Couldn't sign in — retrying in 4 min` | `Try again`, `Last error…` |
| auto connect off | white | `○ Auto connect is off` | `Sign in now` (re-enables auto connect) |
| not configured | white | `○ Set up your account` | `Set up account…` (elevated console prompt) |
| not on campus | gray | `· Not on campus Wi-Fi` | — |

Session-limit flow (the thing Android made you dance for): `Sessions ▸`
lists both sessions by name and duration; clicking one asks for
confirmation, closes it, *verifies on the network that it is gone*, then
immediately retries the login. Win32 menus are modal and do not repaint
live, so the menu shows stale data plus an explicit `Refresh` row; the
result of every action arrives as a balloon or dialog.

Error dialogs are friendly-first (what happened → what to do → the raw
portal phrase as `Detail:`), and never contain the password. Balloon
notifications are throttled (session-limit ≥ 10 min apart, persistent
failure ≥ 30 min apart, success/failure of each login once); nothing
noisy for polls or transient flaps.

## Security posture

- Credentials are DPAPI-encrypted with LocalMachine scope into
  `C:\ProgramData\curaj-wifi\config.json` (SYSTEM+Administrators only).
- The password is sent only to the pinned portal host (`122.252.242.93`).
  A hostile Wi-Fi serving a fake `/userportal` page is refused.
- The control channel is loopback-only with a random 256-bit token; the
  token file is readable by users (the tray must read it), the config is
  not.
- The log scrubber redacts `password=`, `username=`, cookies, `sc=`
  tokens and the configured username from every log line — unit-tested.

## Troubleshooting

- **Tray says "Not on campus Wi-Fi" but you are on campus**: the probe
  could not reach any endpoint — check the Wi-Fi itself; if a phone
  tether is active, disconnect it (there is deliberately no tether
  fallback).
- **"The network's login page is unreachable"**: the portal host is not
  answering — usually a campus outage; `Try again` later.
- **Both sessions in use**: `Sessions ▸ → <the other device> → confirm`.
- **CLI for power users**: `curaj-wifi status|login|logout|sessions|close
  <id>|log` — the UI never requires it, but it is there.
- **Logs**: `C:\ProgramData\curaj-wifi\curaj-wifi.log` (scrubbed, rotates
  at 5 MB).
- **Removing everything**: `irm .../windows/uninstall.ps1 | iex` (it asks
  before deleting saved credentials).
