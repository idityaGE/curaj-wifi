# uninstall.ps1 — remove the CURAJ Wi-Fi client completely.
#
#   irm https://raw.githubusercontent.com/idityaGE/curaj-wifi/main/windows/uninstall.ps1 | iex

$ErrorActionPreference = "Stop"

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
  Start-Process powershell -Verb RunAs -ArgumentList "/k powershell -ExecutionPolicy Bypass -File `"$PSCommandPath`""
  exit
}

$dest = "C:\Program Files\curaj-wifi"
$exe  = Join-Path $dest "curaj-wifi.exe"

Write-Host "Removing tray autostart..."
Remove-ItemProperty "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run" -Name "curaj-wifi" -ErrorAction SilentlyContinue
Get-Process curaj-wifi -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

if (Test-Path $exe) {
  Write-Host "Removing service..."
  & $exe uninstall
} else {
  Write-Host "Service exe not found; trying sc.exe cleanup..."
  sc.exe stop curaj-wifi 2>$null | Out-Null
  sc.exe delete curaj-wifi 2>$null | Out-Null
}

Write-Host "Removing program files..."
Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue

# Saved credentials live in C:\ProgramData\curaj-wifi — delete too?
$answer = Read-Host "Delete saved credentials and logs (C:\ProgramData\curaj-wifi)? [y/N]"
if ($answer -match "^[yY]") {
  Remove-Item "C:\ProgramData\curaj-wifi" -Recurse -Force -ErrorAction SilentlyContinue
  Write-Host "Credentials deleted."
} else {
  Write-Host "Credentials kept at C:\ProgramData\curaj-wifi (delete manually if desired)."
}

Write-Host "Uninstalled."
