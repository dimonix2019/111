@echo off
setlocal
cd /d "%~dp0"
echo MOEX Bar Replay (Windows desktop, Swing)
echo.

where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] JDK 17 not found. Install Temurin 17 and add to PATH.
  echo https://adoptium.net/temurin/releases/?version=17
  exit /b 1
)

call gradlew.bat :desktop:run %*
endlocal
