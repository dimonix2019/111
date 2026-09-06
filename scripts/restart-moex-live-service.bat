@echo off
REM Restart MoexLiveWatchdog so new Python code is loaded.
REM After scripts\grant-restart-acl.bat (once), this works without UAC.

sc.exe query MoexLiveWatchdog >nul 2>&1
if errorlevel 1 (
  echo Service MoexLiveWatchdog not installed. Run scripts\install-moex-live-service.bat
  pause
  exit /b 1
)

echo Restarting MoexLiveWatchdog...
powershell -NoProfile -Command "try { Restart-Service -Name 'MoexLiveWatchdog' -Force -ErrorAction Stop } catch { Write-Host $_.Exception.Message; exit 1 }"
if errorlevel 1 (
  net session >nul 2>&1
  if not errorlevel 1 (
    echo Restart failed even as admin.
    pause
    exit /b 1
  )
  echo No start/stop right yet. Allow UAC now, or run scripts\grant-restart-acl.bat once.
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
sc.exe query MoexLiveWatchdog
echo Done.
