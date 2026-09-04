#Requires -RunAsAdministrator
# Install MOEX live desk + watchdog as a Windows service (NSSM).
# Probe interval: 60s. Host 0.0.0.0:8765 for Tailscale.
# Stop run-replay-web.bat before installing (port 8765 conflict).

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$WatchdogRel = Join-Path $RepoRoot "strategy-web\scripts\live_watchdog.py"
if (-not (Test-Path $WatchdogRel)) {
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}
$StrategyWeb = Join-Path $RepoRoot "strategy-web"
$WatchdogPy = Join-Path $StrategyWeb "scripts\live_watchdog.py"
$ToolsDir = Join-Path $RepoRoot "tools"
$NssmDir = Join-Path $ToolsDir "nssm"
$ServiceName = "MoexLiveWatchdog"
$DisplayName = "MOEX Live Watchdog (strategy-web)"

function Find-Python {
    $cmd = Get-Command python -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $cmd = Get-Command py -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "Python not found in PATH. Install Python 3 and re-open admin PowerShell."
}

function Ensure-Nssm {
    $local = Get-ChildItem -Path $NssmDir -Filter nssm.exe -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\win64\\nssm\.exe$' } |
        Select-Object -First 1
    if ($local) { return $local.FullName }

    New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null
    $zip = Join-Path $ToolsDir "nssm.zip"
    $url = "https://nssm.cc/release/nssm-2.24.zip"
    Write-Host "Downloading NSSM from $url ..."
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $NssmDir -Force
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    $local = Get-ChildItem -Path $NssmDir -Filter nssm.exe -Recurse |
        Where-Object { $_.FullName -match '\\win64\\nssm\.exe$' } |
        Select-Object -First 1
    if (-not $local) { throw "nssm.exe (win64) not found after download" }
    return $local.FullName
}

$python = Find-Python
$nssm = Ensure-Nssm
Write-Host "Python: $python"
Write-Host "NSSM:   $nssm"
Write-Host "App:    $WatchdogPy"

if (-not (Test-Path $WatchdogPy)) {
    throw "Watchdog script not found: $WatchdogPy"
}

# Free port if bat left something running
Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object {
        Write-Host ("Stopping PID {0} on :8765" -f $_.OwningProcess)
        Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
    }

$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Service exists - removing old instance..."
    & $nssm stop $ServiceName confirm 2>$null | Out-Null
    Start-Sleep -Seconds 2
    & $nssm remove $ServiceName confirm 2>$null | Out-Null
    Start-Sleep -Seconds 1
}

$dataDir = Join-Path $StrategyWeb "data"
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$stdout = Join-Path $dataDir "watchdog-service.out.log"
$stderr = Join-Path $dataDir "watchdog-service.err.log"

& $nssm install $ServiceName $python $WatchdogPy
& $nssm set $ServiceName AppDirectory $StrategyWeb
& $nssm set $ServiceName DisplayName $DisplayName
& $nssm set $ServiceName Description "MOEX strategy-web live desk + external watchdog (Tailscale :8765)"
& $nssm set $ServiceName Start SERVICE_AUTO_START
& $nssm set $ServiceName AppStdout $stdout
& $nssm set $ServiceName AppStderr $stderr
& $nssm set $ServiceName AppRotateFiles 1
& $nssm set $ServiceName AppRotateBytes 2000000
& $nssm set $ServiceName AppRestartDelay 5000
& $nssm set $ServiceName AppExit Default Restart

# NSSM AppEnvironmentExtra: one KEY=VALUE per line
$envBlock = @(
    "MOEX_REPLAY_HOST=0.0.0.0"
    "MOEX_REPLAY_PORT=8765"
    "MOEX_REPLAY_OPEN_BROWSER=0"
    "MOEX_WATCHDOG_MANAGE_SERVER=1"
    "MOEX_WATCHDOG_URL=http://127.0.0.1:8765"
    "MOEX_WATCHDOG_PORT=8765"
    "MOEX_WATCHDOG_INTERVAL_SEC=60"
    "PYTHONUNBUFFERED=1"
) -join "`n"
& $nssm set $ServiceName AppEnvironmentExtra $envBlock

Start-Service $ServiceName
Start-Sleep -Seconds 3
Get-Service $ServiceName | Format-List Name, Status, StartType

Write-Host ""
Write-Host "OK: $ServiceName installed and started."
Write-Host "  Desk:      http://127.0.0.1:8765"
Write-Host "  Tailscale: http://<100.x.x.x>:8765"
Write-Host "  Log:       $StrategyWeb\data\watchdog.log"
Write-Host "  Service:   $stdout"
$uninstall = Join-Path $PSScriptRoot "uninstall-moex-live-service.ps1"
Write-Host "  Uninstall: powershell -ExecutionPolicy Bypass -File `"$uninstall`""
Write-Host ""
Write-Host "Do NOT run run-replay-web.bat while the service is running (same port)."
Write-Host "PC power: set sleep to Never while trading."
