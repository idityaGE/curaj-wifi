# Windows client 1.0 (win-v1.0) — release notes

First Windows release of the CURAJ CAMPUS CONNECT client.

## Install

```powershell
irm https://raw.githubusercontent.com/idityaGE/curaj-wifi/main/windows/install.ps1 | iex
```

(The installer self-elevates, verifies SHA-256, installs a boot-start
service, registers the tray icon, and walks you through account setup —
one username + password prompt, stored DPAPI-encrypted.)

## What it does

- Signs you in to the campus portal automatically whenever the Wi-Fi is
  blocked — at boot, before logon, after portal drops (60 s check), and
  after waking up.
- One tray icon that tells the truth: green = online (with your IP in the
  tooltip), amber = working on it, red = failing (with a retry countdown
  and a friendly explanation), gray = not on campus Wi-Fi.
- The two-session limit fixed in **one click**: `Sessions ▸ → close the
  stale device → confirm → the client signs you in immediately`.
- Explicit logout turns auto-connect off (and is verified on the network);
  `Sign in now` turns it back on.
- Credentials never leave the machine except to the pinned portal host,
  and logs are scrubbed of credentials.

## Checksums

Published in `SHA256SUMS.txt` next to the zip; the installer verifies
automatically. Verify manually with:

```powershell
Get-FileHash .\curaj-wifi.zip -Algorithm SHA256
```

## Known limits

- No tethered-phone fallback: when not on campus Wi-Fi the client stays
  gray and never sends traffic through another connection.
- The tray uses native menus, which are modal: lists (e.g. Sessions) show
  a `Refresh` entry instead of repainting live.
- Tested list: see docs/WINDOWS-TEST.md (first release was verified on a
  friend's laptop, not on CI — GitHub Actions is disabled for this repo).
- Uninstall: `irm .../windows/uninstall.ps1 | iex` (asks before deleting
  saved credentials).
