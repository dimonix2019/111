#Requires -RunAsAdministrator
$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$NssmDir = Join-Path $RepoRoot "tools\nssm"
$ServiceName = "MoexLiveWatchdog"

$nssm = Get-ChildItem -Path $NssmDir -Filter nssm.exe -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\win64\\nssm\.exe$' } |
    Select-Object -First 1 -ExpandProperty FullName

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) {
    Write-Host "Service $ServiceName not installed."
    exit 0
}

if ($nssm) {
    & $nssm stop $ServiceName confirm 2>$null
    Start-Sleep -Seconds 2
    & $nssm remove $ServiceName confirm 2>$null
} else {
    Stop-Service $ServiceName -Force -ErrorAction SilentlyContinue
    sc.exe delete $ServiceName | Out-Null
}

Write-Host "Removed $ServiceName (if present)."
