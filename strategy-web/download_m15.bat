@echo off
cd /d "%~dp0"
if exist "..\.venv\Scripts\python.exe" (
  "..\.venv\Scripts\python.exe" download_m15.py
) else (
  python download_m15.py
)
pause
