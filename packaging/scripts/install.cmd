@echo off
setlocal
chcp 65001 >nul
rem ExecutionPolicy applies only to this powershell process.
rem If Windows marked the script as downloaded, run Unblock-File first. See INSTALL_KO.md.
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0install.ps1"
exit /b %ERRORLEVEL%
