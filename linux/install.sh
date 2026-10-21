#!/usr/bin/env bash
# Install the Linux client + systemd user service.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)

install -Dm755 "$here/curaj-login" "$HOME/.local/bin/curaj-login"
install -Dm644 "$here/curaj-login.service" \
    "$HOME/.config/systemd/user/curaj-login.service"

mkdir -p "$HOME/.config/curaj-wifi"
if [ ! -f "$HOME/.config/curaj-wifi/config.json" ]; then
    install -m600 "$here/config.example.json" \
        "$HOME/.config/curaj-wifi/config.json"
    echo "Created ~/.config/curaj-wifi/config.json (mode 600)."
    echo "Put your mobile number and portal password in it."
fi

systemctl --user daemon-reload

cat <<'EOF'

Installed.

  1. edit ~/.config/curaj-wifi/config.json
  2. test once:   ~/.local/bin/curaj-login once
  3. enable:      systemctl --user enable --now curaj-login.service
  4. logs:        journalctl --user -u curaj-login -f

To also run while you are logged out of the desktop (boot):
  sudo loginctl enable-linger "$USER"
EOF
