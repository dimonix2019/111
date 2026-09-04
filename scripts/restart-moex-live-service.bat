@echo off
REM Restart MoexLiveWatchdog so new Python code is loaded.
net session >nul 2>&1
if errorlevel 1 (
  echo Requesting admin...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
sc.exe query MoexLiveWatchdog >nul 2>&1
if errorlevel 1 (
  echo Service MoexLiveWatchdog not installed. Run scripts\install-moex-live-service.bat
  pause
  exit /b 1
)
echo Restarting MoexLiveWatchdog...
net stop MoexLiveWatchdog
timeout /t 2 /nobreak >nul
net start MoexLiveWatchdog
sc.exe query MoexLiveWatchdog
echo Done.
