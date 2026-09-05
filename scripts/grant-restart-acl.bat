@echo off
REM One-time admin: grant current user start/stop on MoexLiveWatchdog, then restart the service.
cd /d "%~dp0"
net session >nul 2>&1
if not errorlevel 1 goto :run
powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
exit /b

:run
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0grant-restart-acl.ps1"
if errorlevel 1 pause
pause
