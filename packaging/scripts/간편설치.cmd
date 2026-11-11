@echo off
rem ===========================================================
rem  Audit Record - simple install launcher
rem  This file is ASCII only on purpose. cmd.exe reads batch
rem  files with the OEM code page, so Korean text inside a
rem  batch file breaks on a Korean Windows. All Korean text
rem  lives in setup.ps1, which PowerShell reads as UTF-8.
rem ===========================================================
setlocal
title Audit Record setup

set "HERE=%~dp0"
set "PS1=%HERE%setup.ps1"
set "PSEXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

echo.
echo   Audit Record - simple install
echo   ------------------------------------------------
echo.

if not exist "%PSEXE%" (
  echo   [X] Windows PowerShell was not found at
  echo       %PSEXE%
  goto :hold
)

if not exist "%PS1%" (
  echo   [X] setup.ps1 was not found next to this file.
  echo       Looked in: %HERE%
  echo       Unzip the whole package again, keeping app, data and scripts together.
  goto :hold
)

rem Files copied from a USB or unzipped from a download are marked as
rem blocked by Windows. RemoteSigned then refuses to run them.
"%PSEXE%" -NoProfile -ExecutionPolicy RemoteSigned -Command "Get-ChildItem -LiteralPath '%HERE%' -Filter *.ps1 | Unblock-File" >nul 2>&1

echo   Starting setup. A User Account Control prompt may appear.
echo.
"%PSEXE%" -NoProfile -ExecutionPolicy RemoteSigned -File "%PS1%" %*
set "CODE=%ERRORLEVEL%"

echo.
if "%CODE%"=="0" (
  echo   Setup finished. You can close this window.
) else (
  echo   [X] Setup stopped with exit code %CODE%.
  echo       Open scripts\install-log.txt for the full message.
)

:hold
echo.
echo   Press any key to close this window.
pause >nul
exit /b %CODE%
