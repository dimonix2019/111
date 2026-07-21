@echo off
setlocal
cd /d "%~dp0"
echo MOEX Bar Replay — Web + watchdog
echo   Local:     http://127.0.0.1:8765
echo   Tailscale: http://^<100.x.x.x^>:8765  (host=0.0.0.0)
echo   Log: strategy-web\data\watchdog.log
echo   Close this window / Ctrl+C to stop server + watchdog.
echo.

where python >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Python 3 not found. Install from python.org
  pause
  exit /b 1
)

REM Освободить порт 8765, если предыдущий сервер ещё работает
powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Write-Host ('Stopping previous replay server PID ' + $_.OwningProcess + ' ...'); Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"

timeout /t 1 /nobreak >nul

cd strategy-web
if errorlevel 1 (
  echo [ERROR] Folder strategy-web not found
  pause
  exit /b 1
)

pip install -q -r requirements.txt 2>nul

REM Один процесс-супервизор: поднимает сервер, открывает браузер один раз, чинит монитор/процесс
REM 0.0.0.0 — доступ с телефона через Tailscale (http://<100.x.x.x>:8765)
set MOEX_REPLAY_HOST=0.0.0.0
set MOEX_REPLAY_PORT=8765
set MOEX_REPLAY_OPEN_BROWSER=1
set MOEX_WATCHDOG_MANAGE_SERVER=1
set MOEX_WATCHDOG_URL=http://127.0.0.1:8765
set MOEX_WATCHDOG_PORT=8765
set MOEX_WATCHDOG_INTERVAL_SEC=60

echo Starting supervised server...
echo.
python scripts\live_watchdog.py
set EXITCODE=%ERRORLEVEL%

powershell -NoProfile -Command "Get-NetTCPConnection -LocalPort 8765 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"

if not "%EXITCODE%"=="0" (
  echo.
  echo [ERROR] Watchdog exited with code %EXITCODE%
  pause
)
endlocal & exit /b %EXITCODE%
