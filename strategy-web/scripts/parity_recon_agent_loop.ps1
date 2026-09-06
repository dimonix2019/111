# Hourly chat wake: health + Test↔Prod parity (investigate / fix / RU report).
# Sentinel: AGENT_LOOP_TICK_parity_recon
# Also early-wake on ALERT lines in parity-hourly.log
# Durable digest (MoexLiveWatchdog → parity-hourly.log) stays independent.
# Do NOT run parity_digest_chat_wake.ps1 alongside this (duplicate).
param(
  [switch]$NoImmediate
)

$ErrorActionPreference = 'Continue'
$DataDir = Join-Path $PSScriptRoot '..\data' | Resolve-Path
$LogPath = Join-Path $DataDir 'parity-hourly.log'

$Prompt = 'Hourly Test<->Prod parity FULL cycle (chat report in Russian; NOT Cursor Automation): 1) Health/workability: GET http://127.0.0.1:8765/api/health and GET /api/health/live - confirm HTTP ok, monitor_alive, not stale; also GET /api/live/status?lite=true (monitor.running / open). 2) POST http://127.0.0.1:8765/api/live/parity/check and GET /api/live/parity; read strategy-web/data/parity-hourly-latest.json. 3) Compare edges/trades for last 60-120 min using prod entry_z/exit_z from live settings. DO NOT change Prod Z 1.6/1.3. 4) If NEW discrepancy (new missing edge, new hard mismatch, Prod without Test in fresh window) - find root cause in strategy-web/live vs replay-signals; fix clear bugs. Soft-restart MoexLiveWatchdog ONLY if live code changed AND position is FLAT. 5) Report to user in Russian (short but complete). If clean - one-line OK plus latest digest line. 6) Known historical hard~15 and missing #2 from 2026-07-20 - do NOT reopen unless newly actionable. After AUTO, schedule_parity_for_auto (~15 min) remains; this hourly loop is independent.'

function Emit-Tick([string]$reason) {
  $payload = '{"prompt":' + ($Prompt | ConvertTo-Json -Compress) + ',"reason":' + ($reason | ConvertTo-Json -Compress) + '}'
  Write-Output ("AGENT_LOOP_TICK_parity_recon " + $payload)
}

function Get-LogLen {
  if (Test-Path -LiteralPath $LogPath) { return (Get-Item -LiteralPath $LogPath).Length }
  return 0L
}
function Get-LastLine {
  if (-not (Test-Path -LiteralPath $LogPath)) { return '' }
  $l = Get-Content -LiteralPath $LogPath -Tail 1 -ErrorAction SilentlyContinue
  if ($null -eq $l) { return '' }
  return [string]$l
}

Write-Output ("parity_recon_loop started pid=" + $PID + " log=" + $LogPath)
$startedUtc = [DateTime]::UtcNow
$nextHourly = $startedUtc.AddHours(1)
if (-not $NoImmediate) {
  # Immediate first tick — do not wait for sleep
  Emit-Tick 'immediate_startup'
} else {
  Write-Output 'parity_recon_skip_immediate=1'
}
Write-Output ("parity_recon_next_hourly_utc=" + $nextHourly.ToString('o'))
Write-Output ("parity_recon_next_hourly_local=" + $nextHourly.ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss'))
$lastLen = Get-LogLen
$lastLine = Get-LastLine
$pollSec = 45

while ($true) {
  Start-Sleep -Seconds $pollSec
  try {
    $len = Get-LogLen
    if ($len -gt $lastLen) {
      $line = Get-LastLine
      if ($line -and ($line -ne $lastLine)) {
        $lastLine = $line
        $lastLen = $len
        # Wake chat on EVERY new digest line (OK and ALERT) — file alone is not enough for the user.
        $reason = if ($line -match '\|\s*ALERT\s*\|') { 'alert_log_append' } else { 'digest_log_append' }
        Emit-Tick $reason
        $nextHourly = [DateTime]::UtcNow.AddHours(1)
        Write-Output ("parity_recon_next_hourly_utc=" + $nextHourly.ToString('o'))
        Write-Output ("parity_recon_next_hourly_local=" + $nextHourly.ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss'))
        continue
      } else {
        $lastLen = $len
      }
    } elseif ($len -lt $lastLen) {
      $lastLen = $len
      $lastLine = Get-LastLine
    }

    if ([DateTime]::UtcNow -ge $nextHourly) {
      Emit-Tick 'hourly'
      $nextHourly = [DateTime]::UtcNow.AddHours(1)
      Write-Output ("parity_recon_next_hourly_utc=" + $nextHourly.ToString('o'))
      Write-Output ("parity_recon_next_hourly_local=" + $nextHourly.ToLocalTime().ToString('yyyy-MM-dd HH:mm:ss'))
    }
  } catch {
    Write-Output ("parity_recon_loop err: " + $_.Exception.Message)
  }
}
