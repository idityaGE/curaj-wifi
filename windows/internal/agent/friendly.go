package agent

import (
	"strings"
)

// Friendly maps a raw portal/network error to (headline, what-to-do) per
// plan A.4. Used by the error dialog, "Last error…", CLI output and
// balloon text. Raw detail is always preserved alongside in the UI.
func Friendly(rawErr string) (headline, advice string) {
	t := strings.ToLower(rawErr)

	switch {
	case strings.Contains(t, "maximum session") ||
		strings.Contains(t, "session allowed reached") ||
		strings.Contains(t, "session limit"):
		return "Couldn't sign in: both of your sessions are in use (limit 2).",
			"Close one from Sessions ▸ to free a slot."

	case strings.Contains(t, "already in progress") ||
		strings.Contains(t, "authentication is in progress") ||
		strings.Contains(t, "authentication already"):
		return "The network is already signing you in.",
			"Wait a moment — it finishes by itself."

	case strings.Contains(t, "redirect_to_nas") ||
		strings.Contains(t, "already running"):
		return "A stale session is stuck.",
			"We clear it automatically — retrying."

	case strings.Contains(t, "untrusted portal host"):
		return "This Wi-Fi is not serving the real campus login page.",
			"Nothing to do here — do not enter your password anywhere else."

	case strings.Contains(t, "probe") || strings.Contains(t, "no probe endpoint") ||
		strings.Contains(t, "not responding") || strings.Contains(t, "timed out") ||
		strings.Contains(t, "timeout") || strings.Contains(t, "unreachable network"):
		return "Campus Wi-Fi is not responding.",
			"Check the Wi-Fi is connected to “CURAJ CAMPUS CONNECT”; try again later."

	case strings.Contains(t, "portal unreachable") ||
		strings.Contains(t, "no portal page") ||
		strings.Contains(t, "login page is unreachable") ||
		strings.Contains(t, "login post failed") ||
		strings.Contains(t, "http 400") || strings.Contains(t, "http 5"):
		return "The network's login page is unreachable.",
			"This may be a campus outage — try again later."

	case strings.Contains(t, "self-care login failed"):
		return "The portal rejected your saved password.",
			"Re-run Set up account… to save the correct password."

	case strings.Contains(t, "still online"):
		return "The network still reports you online.",
			"Try again in a minute; if it persists, use Try again."

	default:
		return "Couldn't sign in.", "Try again in a minute — see the log for details."
	}
}
