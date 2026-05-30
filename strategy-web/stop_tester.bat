@echo off
setlocal EnableExtensions
cd /d "%~dp0"

echo [INFO] Stopping Z-Strategy on ports 8765 and 5174...

powershell -NoProfile -Command "foreach ($port in @(8765, 5174)) { Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue } }"

timeout /t 1 /nobreak >nul
echo [OK] Ports cleared. Now run run_tester.bat
pause
