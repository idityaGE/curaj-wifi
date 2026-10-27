# Windows client — test checklist (friend's laptop)

No Windows machine is available during development; this checklist is what
the laptop test must cover before publishing `win-v1.0`. Everything in the
"expected" column maps to plan Part A mockups / the UX contract.

## Setup

1. Copy `install.ps1` from the release (or serve it locally) and run
   `irm <url> | iex`.
   - expected: UAC prompt → download → `Checksum OK` → service installed
     and started → elevated console asks username/password.
   - after setup: tray icon appears (white), tooltip
     `CURAJ Wi-Fi — Set up your account`, one balloon points at it.
2. `curaj-wifi status` in a terminal shows `state: not_configured` before
   setup and a real state after.

## Core flows

3. Connect to `CURAJ CAMPUS CONNECT` (portal blocks you).
   - expected: icon turns amber, status `● Signing in… (attempt 1)`; within
     ~10 s icon green, `● Online · 10.50.x.x`, balloon "Signed in — you're
     online.", internet works.
4. Wait a minute or two online.
   - expected: no balloons, no menu changes (the 60 s poll is silent).
5. Portal TTL drop (ask the tester to log the laptop in via the web
   portal then log it out from self-care, or wait for a natural drop).
   - expected: silent re-login; **no "still blocked" after portal success**
     (the 1.6.1 Android trap — the icon must go green).
6. `Log out…` → confirm dialog (default = Cancel!) → Log out.
   - expected: balloon "Logged out. Auto connect is off.", icon white,
     menu shows `Sign in now`; internet is actually gone (open a site).
7. `Sign in now`.
   - expected: signs in AND turns Auto connect back on (menu `✓ On`).

## The session-limit flow (most important)

8. Start a session on another device with the same account (a phone).
9. Disconnect/reconnect Wi-Fi so the laptop must re-login while 2 sessions
   are active.
   - expected: icon amber, status `● 2 sessions active — close one`,
     `Sessions (2) ▸` lists both with durations, balloon (throttled).
10. Click the phone's session → confirm dialog shows name + started time +
    duration → `OK`.
    - expected: balloon "Old session closed — signing in…"; the phone
      loses internet; the laptop goes online automatically (green).
11. Do it "backwards": close the laptop's own session from the phone web
    portal, same result on the laptop (auto re-login).

## Resilience

12. Reboot the laptop (without touching anything).
    - expected: service auto-starts at boot, tray appears after login,
      auto sign-in happens without user action.
13. Disconnect from campus Wi-Fi (airplane mode).
    - expected: icon gray, `· Not on campus Wi-Fi`; tether a phone → same
      gray state, **no login attempts through the tether**.
14. Try again while failed: turn the AP off briefly so login fails, watch
    `● Couldn't sign in — retrying in Xm` (countdown in tooltip), then
    `Try again` → immediate attempt.
15. Wrong password: re-run setup with a bad password.
    - expected: friendly dialog "The portal rejected your saved password"
      with the raw phrase in `Detail:`; log line is scrubbed (no password,
      no username).

## Hygiene

16. `C:\ProgramData\curaj-wifi\config.json` is unreadable in Notepad as a
    normal user; `api-token.txt` is readable; neither contains plaintext
    credentials.
17. `curaj-wifi log` shows scrubbed lines only (search for the password).
18. Re-install (`-Update`) over an existing install keeps the saved
    account and works.
19. `irm .../uninstall.ps1 | iex`: service + tray gone, Run key gone; a
    re-install afterwards works cleanly.
