@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "API_PORT=8765"
set "UI_PORT=5174"
set "UI_URL=http://127.0.0.1:%UI_PORT%/"
set "API_HEALTH=http://127.0.0.1:%API_PORT%/api/health"

if not exist .venv\Scripts\python.exe (
  echo [ERROR] .venv not found. Create venv and: pip install -r requirements.txt
  pause
  exit /b 1
)

where node >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js not found in PATH. Install Node.js LTS and restart terminal.
  pause
  exit /b 1
)

echo [INFO] Installing Python deps...
call .venv\Scripts\pip install -q -r requirements.txt
if errorlevel 1 exit /b 1

if not exist frontend\node_modules (
  echo [INFO] npm install frontend...
  call npm install --prefix frontend
  if errorlevel 1 exit /b 1
)

echo [INFO] Stopping old API/UI on ports %API_PORT% / %UI_PORT% (if any)...
powershell -NoProfile -Command "foreach ($port in @( %API_PORT%, %UI_PORT% )) { Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } }"
timeout /t 1 /nobreak >nul

echo [INFO] Starting API :%API_PORT% and Vite :%UI_PORT% ...
start "Z-Strategy API" cmd /k "cd /d "%~dp0" && call .venv\Scripts\python -m uvicorn api.server:app --host 127.0.0.1 --port %API_PORT% --reload"
timeout /t 2 /nobreak >nul
start "Z-Strategy UI" cmd /k "cd /d "%~dp0" && npm run dev --prefix frontend -- --port %UI_PORT% --host 127.0.0.1"

echo [INFO] Waiting for API (up to 30 sec)...
set /a API_RETRIES=0
:wait_api
timeout /t 1 /nobreak >nul
powershell -NoProfile -Command "try { $r=Invoke-WebRequest -Uri '%API_HEALTH%' -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { exit 0 } else { exit 1 } } catch { exit 1 }"
if not errorlevel 1 goto api_ready
set /a API_RETRIES+=1
if %API_RETRIES% GEQ 30 goto api_timeout
goto wait_api

:api_timeout
echo [ERROR] API did not start on port %API_PORT%.
echo [ERROR] Check window "Z-Strategy API" — WinError 10013 = port still busy.
echo [ERROR] Close all old Z-Strategy windows, then run this script again.
goto wait_ui

:api_ready
echo [OK] API is up: %API_HEALTH%

echo [INFO] Waiting for UI (up to 45 sec)...
set /a RETRIES=0
:wait_ui
timeout /t 1 /nobreak >nul
powershell -NoProfile -Command "try { $r=Invoke-WebRequest -Uri '%UI_URL%' -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { exit 0 } else { exit 1 } } catch { exit 1 }"
if not errorlevel 1 goto ui_ready
set /a RETRIES+=1
if %RETRIES% GEQ 45 goto ui_timeout
goto wait_ui

:ui_timeout
echo [WARN] UI did not respond. Check window "Z-Strategy UI" for errors.
echo [WARN] Open manually: %UI_URL%
goto done

:ui_ready
echo [OK] Opening browser: %UI_URL%
start "" "%UI_URL%"

:done
echo [OK] UI:  %UI_URL%
echo [OK] API: %API_HEALTH%
echo.
echo If live-mode hangs: API window must have NO red WinError 10013.
echo Legacy Streamlit: streamlit run app_streamlit.py
pause
