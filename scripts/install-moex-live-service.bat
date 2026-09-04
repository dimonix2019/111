@echo off
REM Install MoexLiveWatchdog Windows service (admin). Watchdog probe = 60s.
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-moex-live-service.ps1"
if errorlevel 1 pause
