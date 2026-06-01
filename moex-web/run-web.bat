@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "ROOT=%CD%"
set "HOST=0.0.0.0"
set "PORT=8080"
if not "%MOEX_WEB_HOST%"=="" set "HOST=%MOEX_WEB_HOST%"
if not "%MOEX_WEB_PORT%"=="" set "PORT=%MOEX_WEB_PORT%"

echo === MOEX MVP Web (Windows) ===
echo.

if not exist ".venv\Scripts\python.exe" (
    echo [1/3] Creating virtualenv .venv ...
    py -3 -m venv .venv 2>nul
    if errorlevel 1 python -m venv .venv
    if errorlevel 1 (
        echo ERROR: Need Python 3.10+ ^(py -3 or python in PATH^)
        pause
        exit /b 1
    )
)

echo [2/3] Installing dependencies ...
call ".venv\Scripts\activate.bat"
python -m pip install -q -U pip
pip install -q -r requirements.txt
pip install -q pandas numpy requests 2>nul

set "PYTHONPATH=%ROOT%;%ROOT%\..\strategy-web;%PYTHONPATH%"

echo.
echo [3/3] Starting server ...
echo   On this PC:     http://127.0.0.1:%PORT%/
echo   Health check:   http://127.0.0.1:%PORT%/api/health

where tailscale >nul 2>&1
if %errorlevel%==0 (
    for /f "usebackq delims=" %%I in (`tailscale ip -4 2^>nul`) do (
        echo   Phone ^(Tailscale ON^): http://%%I:%PORT%/
        goto :ts_done
    )
    echo   Tailscale: no IP ^(run: tailscale up^)
)
:ts_done

echo.
echo Press Ctrl+C to stop. Close window = server stops.
echo.

python -m uvicorn server.main:app --host %HOST% --port %PORT%
if errorlevel 1 (
    echo.
    echo Server exited with error. See messages above.
    pause
    exit /b 1
)
pause
