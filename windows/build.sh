#!/usr/bin/env bash
# build.sh — cross-compile the Windows client from Linux (plan D.1).
# Produces dist/windows/curaj-wifi.zip + SHA256SUMS.txt.
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(git -C .. describe --tags --match 'win-v*' 2>/dev/null | sed 's/^win-v//') "
  VERSION="${VERSION:-1.0}"
fi
VERSION="${VERSION// /}"

echo "==> generating icons"
go run ./tools/genicons -out internal/icons

echo "==> vet (linux + windows)"
go vet ./...
GOOS=windows GOARCH=amd64 go vet ./...

echo "==> tests"
go test ./...

echo "==> building curaj-wifi.exe $VERSION (windows/amd64)"
mkdir -p dist
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build \
  -trimpath -ldflags "-s -w -X main.version=$VERSION" \
  -o dist/curaj-wifi.exe .
ls -la dist/curaj-wifi.exe

echo "==> zip + sums"
(
  cd dist
  rm -f curaj-wifi.zip SHA256SUMS.txt
  zip -q curaj-wifi.zip curaj-wifi.exe
  sha256sum curaj-wifi.exe curaj-wifi.zip > SHA256SUMS.txt
  cat SHA256SUMS.txt
)
echo "done: windows/dist/curaj-wifi.zip"
