#Requires -RunAsAdministrator
<#
.SYNOPSIS
  One-time: grant a Windows user start/stop on service MoexLiveWatchdog (no global UAC off, no stored password).

.DESCRIPTION
  NSSM installs MoexLiveWatchdog as LocalSystem. The default DACL lets Interactive Users
  query the service but NOT start/stop, so a Cursor agent (medium IL, even if the account
  is local admin) gets Access denied / UAC on Restart-Service.

  This adds one ACE for the current user (or -Account): query + start + stop + pause.
  It does NOT grant Change Config / Delete / Write DAC.

  After this, a non-elevated agent can:
    Restart-Service MoexLiveWatchdog -Force
  or run scripts\restart-moex-live-service.bat without a UAC prompt.

  Re-run after scripts\install-moex-live-service.bat (NSSM reinstall resets the DACL).

.PARAMETER Account
  DOMAIN\user or computer\user. Default: the user of this elevated process.

.PARAMETER ServiceName
  Windows service name. Default: MoexLiveWatchdog.

.PARAMETER NoRestart
  Only change the DACL; do not Restart-Service.

.PARAMETER DryRun
  Print current and new SDDL; do not call sc.exe sdset.
#>
param(
    [string]$Account = "",
    [string]$ServiceName = "MoexLiveWatchdog",
    [switch]$NoRestart,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# Same interact rights as LocalSystem (SY) on a typical service DACL — not BA (no DC/SD/WD/WO).
$AceRights = "CCLCSWRPWPDTLOCRRC"

function Get-AccountSid([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name)) {
        return [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    }
    $nt = New-Object System.Security.Principal.NTAccount($Name.Trim())
    return $nt.Translate([System.Security.Principal.SecurityIdentifier]).Value
}

function Get-AccountNt([string]$SidValue) {
    try {
        $sid = New-Object System.Security.Principal.SecurityIdentifier($SidValue)
        return $sid.Translate([System.Security.Principal.NTAccount]).Value
    } catch {
        return $SidValue
    }
}

function Get-ServiceSddl([string]$Name) {
    $raw = & sc.exe sdshow $Name 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "sc.exe sdshow $Name failed (exit $LASTEXITCODE): $raw"
    }
    $line = (
        $raw -split "`r?`n" |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ -match '^D:' } |
            Select-Object -First 1
    )
    if (-not $line) {
        throw "sc.exe sdshow $Name returned no DACL (D:...). Output: $raw"
    }
    return $line
}

function Add-ServiceStartStopAce([string]$Sddl, [string]$SidValue) {
    $ace = "(A;;$AceRights;;;$SidValue)"
    if ($Sddl -match [regex]::Escape($SidValue)) {
        return @{ Sddl = $Sddl; Changed = $false; Ace = $ace }
    }
    if ($Sddl -notmatch '^D:') {
        throw "Unexpected SDDL (expected to start with D:): $Sddl"
    }
    # Insert allow-ACE at the front of the DACL (keep SY/BA/IU/SU unchanged).
    $newSddl = $Sddl -replace '^D:', "D:$ace"
    return @{ Sddl = $newSddl; Changed = $true; Ace = $ace }
}

$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) {
    throw "Service $ServiceName is not installed. Run scripts\install-moex-live-service.bat first."
}

$sid = Get-AccountSid $Account
$ntName = Get-AccountNt $sid
$oldSddl = Get-ServiceSddl $ServiceName
$result = Add-ServiceStartStopAce $oldSddl $sid

Write-Host "Service:  $ServiceName  ($($svc.Status))"
Write-Host "Account:  $ntName"
Write-Host "SID:      $sid"
Write-Host "ACE:      $($result.Ace)"
Write-Host "Old SDDL: $oldSddl"
Write-Host "New SDDL: $($result.Sddl)"

if ($DryRun) {
    Write-Host ""
    Write-Host "DryRun: no sc.exe sdset, no restart."
    exit 0
}

if ($result.Changed) {
    $setOut = & sc.exe sdset $ServiceName $result.Sddl 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "sc.exe sdset failed (exit $LASTEXITCODE): $setOut"
    }
    Write-Host ($setOut.Trim())
    $verify = Get-ServiceSddl $ServiceName
    if ($verify -notmatch [regex]::Escape($sid)) {
        throw "sdset reported OK but SID $sid is not in DACL: $verify"
    }
    Write-Host "DACL updated."
} else {
    Write-Host "ACE already present — DACL unchanged."
}

if (-not $NoRestart) {
    Write-Host "Restarting $ServiceName (reload Python on :8765)..."
    Restart-Service -Name $ServiceName -Force
    Start-Sleep -Seconds 2
    $after = Get-Service -Name $ServiceName
    Write-Host "Status: $($after.Status)"
    if ($after.Status -ne "Running") {
        throw "Service $ServiceName is $($after.Status) after restart."
    }
}

Write-Host ""
Write-Host "OK: non-elevated Restart-Service $ServiceName should work for $ntName."
Write-Host "    Agent: Restart-Service $ServiceName -Force"
Write-Host "    Or:    scripts\restart-moex-live-service.bat"
