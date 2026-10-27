# install.ps1 — CURAJ Wi-Fi client installer (plan D.2 step 7).
#
#   irm https://raw.githubusercontent.com/idityaGE/curaj-wifi/main/windows/install.ps1 | iex
#
# Flags:
#   -NoTray        install the service only (no tray autostart)
#   -SkipAccount   don't run the account-setup step
#   -Update        update an existing install in place
#
# What it does: self-elevates, downloads the release zip, verifies SHA-256,
# extracts to C:\Program Files\curaj-wifi, installs + starts the service,
# registers the tray autostart, and (optionally) runs account setup.

param(
  [switch]$NoTray,
  [switch]$SkipAccount,
  [switch]$Update,
  [string]$Version = "v1.0"  # tag, without the win- prefix handling below
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

# --- self-elevate -----------------------------------------------------------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  $args = "/k powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`""
  if ($NoTray)      { $args += " -NoTray" }
  if ($SkipAccount) { $args += " -SkipAccount" }
  if ($Update)      { $args += " -Update" }
  Start-Process powershell -Verb RunAs -ArgumentList $args
  exit
}

$repo   = "idityaGE/curaj-wifi"
$tag    = "win-$Version"
$base   = "https://github.com/$repo/releases/download/$tag"
$dest   = "C:\Program Files\curaj-wifi"
$exe    = Join-Path $dest "curaj-wifi.exe"

function Fail($msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }

Write-Host "CURAJ Wi-Fi client — installer ($tag)"

# --- download ---------------------------------------------------------------
$tmp = Join-Path ([IO.Path]::GetTempPath()) "curaj-wifi-install"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$zip  = Join-Path $tmp "curaj-wifi.zip"
$sums = Join-Path $tmp "SHA256SUMS.txt"

if ($Update -and (Test-Path $exe)) {
  # Update path: stop what is running first so the exe can be replaced.
  Write-Host "Update: stopping service and tray..."
  Stop-Service curaj-wifi -ErrorAction SilentlyContinue
  Get-Process curaj-wifi -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
}

foreach ($f in @("curaj-wifi.zip", "SHA256SUMS.txt")) {
  Invoke-WebRequest "$base/$f" -OutFile (Join-Path $tmp $f) -UseBasicParsing
}

# --- verify -----------------------------------------------------------------
$expected = (Select-String -Path $sums -Pattern "curaj-wifi\.zip" |
  ForEach-Object { ($_ -split "\s+")[0] }).Trim().ToLower()
if (-not $expected) { Fail "SHA256SUMS.txt does not list curaj-wifi.zip" }
$actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
if ($expected -ne $actual) { Fail "checksum mismatch — download corrupted or tampered; aborting" }
Write-Host "Checksum OK ($expected)"

# --- extract ----------------------------------------------------------------
New-Item -ItemType Directory -Force -Path $dest | Out-Null
Expand-Archive -Path $zip -DestinationPath $dest -Force
Copy-Item (Join-Path $tmp "SHA256SUMS.txt") $dest -Force
Write-Host "Extracted to $dest"

# --- service ----------------------------------------------------------------
$svcOut = & $exe install
if ($LASTEXITCODE -ne 0) { Fail "service install failed: $svcOut" }
Write-Host "Service installed and started."

# --- tray autostart (HKCU Run) ----------------------------------------------
if (-not $NoTray) {
  $runKey = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
  New-Item -Path $runKey -Force | Out-Null
  Set-ItemProperty -Path $runKey -Name "curaj-wifi" -Value "`"$exe`" tray"
  Write-Host "Tray icon registered for this user."
}

# --- account setup ----------------------------------------------------------
if (-not $SkipAccount) {
  Write-Host ""
  Write-Host "Now setting up your account (administrator window)."
  Start-Process $exe -ArgumentList "config" -Verb RunAs -Wait
}

# --- status -----------------------------------------------------------------
$status = (& $exe status) -join " | "
Write-Host ""
Write-Host "Done. Service view: $status"
if (-not $NoTray -and -not (Get-Process curaj-wifi -ErrorAction SilentlyContinue)) {
  Start-Process $exe -ArgumentList "tray"
}
Write-Host "Look for the tray icon; right-click it for status and actions."
