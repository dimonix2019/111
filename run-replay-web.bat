@echo off
setlocal
cd /d "%~dp0"
echo MOEX Bar Replay — Web (TradingView in browser)
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

set MOEX_REPLAY_OPEN_BROWSER=1
cd strategy-web
if errorlevel 1 (
  echo [ERROR] Folder strategy-web not found
  pause
  exit /b 1
)

pip install -q -r requirements.txt 2>nul
echo Starting server... Browser opens automatically.
echo Close this window to stop the server.
echo.
python replay/replay_app.py
set EXITCODE=%ERRORLEVEL%
if not "%EXITCODE%"=="0" (
  echo.
  echo [ERROR] Server exited with code %EXITCODE%
  echo Check the message above. Close other Bar Replay windows and try again.
  pause
)
endlocal & exit /b %EXITCODE%
