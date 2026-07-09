@echo off
setlocal
cd /d "%~dp0"
echo MOEX Bar Replay — Web (TradingView in browser)
echo.

where python >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Python 3 not found. Install from python.org
  exit /b 1
)

cd strategy-web
pip install -q -r requirements.txt 2>nul
python replay/replay_app.py
endlocal
