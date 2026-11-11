@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
title 감사기록 간편 설치

set "ROOT=%~dp0.."
set "EXE=%ROOT%\app\AuditRecord.App.exe"
set "URL=http://127.0.0.1:47321"

echo.
echo   Audit Record - simple install
echo   ---------------------------------------------
echo.

if not exist "%EXE%" (
  echo   [X] app\AuditRecord.App.exe not found.
  echo       Unzip the whole package so that app, data and scripts sit together.
  echo.
  pause
  exit /b 1
)

for %%D in (data files backup app-keys) do (
  if not exist "%ROOT%\%%D" (
    mkdir "%ROOT%\%%D" >nul 2>&1
    echo   [+] created %%D
  ) else (
    echo   [=] %%D already there
  )
)

echo.
echo   [i] starting the server, then opening the browser.
echo   [i] keep this window open while you use the program.
echo   [i] close this window to stop the server.
echo.

set "DOTNET_CLI_TELEMETRY_OPTOUT=1"
if not defined DOTNET_ENVIRONMENT set "DOTNET_ENVIRONMENT=Production"
if not defined ASPNETCORE_ENVIRONMENT set "ASPNETCORE_ENVIRONMENT=%DOTNET_ENVIRONMENT%"
set "ASPNETCORE_DETAILEDERRORS=false"

start "" /b "%EXE%"
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" goto failed

rem Give Kestrel a moment, then open the setup page on this machine only.
timeout /t 3 /nobreak >nul
start "" "%URL%"

echo   [i] if the browser did not open, type this address yourself:
echo       %URL%
echo.
echo   Press Ctrl+C or close this window to stop.
echo.
rem Hold the window so closing it also stops the server.
"%EXE%" >nul 2>&1
exit /b 0

:failed
echo.
echo   [X] start failed with exit %CODE%. See data\startup-error.log.
echo.
pause
exit /b %CODE%
