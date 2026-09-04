# Trade open/close chat wake (Test↔Prod param reconcile).
# Sentinel: AGENT_LOOP_WAKE_parity_trade
# Polls /api/live/trades every ~75s; debounces on last position + last closed id.
# Pair with parity_recon_agent_loop.ps1 (hourly). Do NOT use Cursor Automation for this.

$ErrorActionPreference = 'Continue'
$Base = 'http://127.0.0.1:8765'
$PollSec = 75

$OpenPrompt = 'Trade OPEN detected - full entry-param Prod<->Test reconcile (report in Russian): 1) GET http://127.0.0.1:8765/api/live/status?lite=true and GET /api/live/trades - capture open trade (id, direction/side, entry_time/bar, entry_z, entry_spread, source). 2) Compare open/entry params vs Test/sim for same bar: bar_ts, Z, spread, side (LONG/SHORT), source (AUTO/MANUAL/...). Use GET /api/live/parity and open_pnl if present. 3) DO NOT change Prod Z 1.6/1.3. Soft-restart MoexLiveWatchdog ONLY if you change live code AND position is FLAT (unlikely on open - usually skip restart while open). 4) If clear bug in strategy-web/live vs replay-signals - fix it. Historical hard~15 / missing #2 from 20.07 - do not re-litigate. 5) RU report in chat: what opened, Prod vs Test fields, OK or mismatch + action.'

$ClosePrompt = 'Trade CLOSE detected - full closed-trade field Prod<->Test reconcile (report in Russian): 1) GET http://127.0.0.1:8765/api/live/trades - identify new closed trade id/fields. 2) POST http://127.0.0.1:8765/api/live/parity/trades?fix=true - soft-safe field fixes only; then GET /api/live/parity/trades and GET /api/live/parity. 3) Full field compare Prod<->Test for the closed trade (entry/exit time, Z, spread, side, source, pnl soft fields). Flag NEW hard mismatches only. 4) DO NOT change Prod Z 1.6/1.3. Soft-restart MoexLiveWatchdog ONLY if live code changed AND position FLAT. 5) Historical hard~15 / missing #2 from 20.07 - do not reopen unless newly actionable. 6) RU report in chat: closed id, reconcile result (hard/soft/fixes), OK or what was fixed.'

function Emit-Wake([string]$reason, [string]$prompt, [hashtable]$extra) {
  $obj = @{
    prompt = $prompt
    reason = $reason
  }
  foreach ($k in $extra.Keys) { $obj[$k] = $extra[$k] }
  $payload = $obj | ConvertTo-Json -Compress -Depth 6
  Write-Output ("AGENT_LOOP_WAKE_parity_trade " + $payload)
}

function Get-TradeSnap {
  try {
    $r = Invoke-RestMethod -Uri ($Base + '/api/live/trades') -Method GET -TimeoutSec 15
    $pos = 'FLAT'
    $openId = $null
    if ($null -ne $r.open) {
      $dir = [string]$r.open.direction
      if (-not $dir) { $dir = [string]$r.open.side }
      if ($dir) { $pos = $dir.ToUpperInvariant() }
      if ($r.open.id) { $openId = [int]$r.open.id }
      elseif ($r.open.open_id) { $openId = [int]$r.open.open_id }
    }
    $closedId = $null
    $closed = @($r.closed)
    if ($closed.Count -gt 0 -and $closed[0].id) {
      $closedId = [int]$closed[0].id
    }
    return @{
      ok       = $true
      position = $pos
      openId   = $openId
      closedId = $closedId
    }
  } catch {
    return @{ ok = $false; error = $_.Exception.Message }
  }
}

Write-Output ("parity_trade_wake started pid=" + $PID + " pollSec=" + $PollSec)
$snap = Get-TradeSnap
$lastPos = 'FLAT'
$lastOpenId = $null
$lastClosedId = $null
if ($snap.ok) {
  $lastPos = $snap.position
  $lastOpenId = $snap.openId
  $lastClosedId = $snap.closedId
  Write-Output ("parity_trade_wake baseline pos=" + $lastPos + " openId=" + $lastOpenId + " closedId=" + $lastClosedId)
} else {
  Write-Output ("parity_trade_wake baseline_err: " + $snap.error)
}

while ($true) {
  Start-Sleep -Seconds $PollSec
  try {
    $cur = Get-TradeSnap
    if (-not $cur.ok) {
      Write-Output ("parity_trade_wake poll_err: " + $cur.error)
      continue
    }

    $pos = $cur.position
    $openId = $cur.openId
    $closedId = $cur.closedId

    # OPEN: FLAT → Long/Short (or new open id while previously flat/different)
    if ($lastPos -eq 'FLAT' -and $pos -ne 'FLAT') {
      Emit-Wake 'trade_open' $OpenPrompt @{
        event       = 'open'
        position    = $pos
        open_id     = $openId
        prev_position = $lastPos
        closed_id   = $closedId
      }
      Write-Output ("parity_trade_wake emitted open pos=" + $pos + " openId=" + $openId)
    } elseif ($pos -ne 'FLAT' -and $null -ne $openId -and $null -ne $lastOpenId -and $openId -ne $lastOpenId) {
      # rare: flipped without seeing FLAT
      Emit-Wake 'trade_open_new_id' $OpenPrompt @{
        event         = 'open'
        position      = $pos
        open_id       = $openId
        prev_open_id  = $lastOpenId
        prev_position = $lastPos
      }
      Write-Output ("parity_trade_wake emitted open_new_id openId=" + $openId)
    }

    # CLOSE: Long/Short → FLAT and/or new closed trade id
    $closedAdvanced = ($null -ne $closedId -and ($null -eq $lastClosedId -or $closedId -gt $lastClosedId))
    if ($lastPos -ne 'FLAT' -and $pos -eq 'FLAT') {
      Emit-Wake 'trade_close' $ClosePrompt @{
        event           = 'close'
        position        = $pos
        prev_position   = $lastPos
        closed_id       = $closedId
        prev_closed_id  = $lastClosedId
        prev_open_id    = $lastOpenId
      }
      Write-Output ("parity_trade_wake emitted close closedId=" + $closedId)
    } elseif ($closedAdvanced -and $pos -eq 'FLAT' -and $lastPos -eq 'FLAT') {
      # closed id advanced while already flat (missed transition) — still wake once
      Emit-Wake 'trade_close_new_id' $ClosePrompt @{
        event          = 'close'
        position       = $pos
        closed_id      = $closedId
        prev_closed_id = $lastClosedId
      }
      Write-Output ("parity_trade_wake emitted close_new_id closedId=" + $closedId)
    }

    $lastPos = $pos
    $lastOpenId = $openId
    if ($null -ne $closedId) { $lastClosedId = $closedId }
  } catch {
    Write-Output ("parity_trade_wake err: " + $_.Exception.Message)
  }
}
