@echo off
REM Launcher in deploy\ — same as ..\run-web.bat
cd /d "%~dp0\.."
call "%~dp0..\run-web.bat" %*
