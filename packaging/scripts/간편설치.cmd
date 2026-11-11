@echo off
setlocal
chcp 65001 >nul
title 감사기록 간편 설치

rem Needs administrator rights to register the Windows service.
net session >nul 2>&1
if not "%ERRORLEVEL%"=="0" (
  echo.
  echo   [!] Administrator rights are required. Asking for elevation...
  echo.
  powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b 0
)

powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File "%~dp0간편설치.ps1" %*
set "CODE=%ERRORLEVEL%"
echo.
if not "%CODE%"=="0" echo   [X] Setup stopped with exit %CODE%. See the lines above and data\startup-error.log.
echo   Press any key to close this window.
pause >nul
exit /b %CODE%
