@echo off
REM Env for Moex live watchdog (console or Windows service). No browser.
setlocal
cd /d "%~dp0..\.."
cd strategy-web
if errorlevel 1 exit /b 1

set MOEX_REPLAY_HOST=0.0.0.0
set MOEX_REPLAY_PORT=8765
set MOEX_REPLAY_OPEN_BROWSER=0
set MOEX_WATCHDOG_MANAGE_SERVER=1
set MOEX_WATCHDOG_URL=http://127.0.0.1:8765
set MOEX_WATCHDOG_PORT=8765
set MOEX_WATCHDOG_INTERVAL_SEC=60

python scripts\live_watchdog.py
exit /b %ERRORLEVEL%
