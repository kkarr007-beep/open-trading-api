@echo off
setlocal
chcp 65001 >nul
set "ROOT=%~dp0.."
set "EXE=%ROOT%\app\AuditRecord.App.exe"
if not exist "%EXE%" (
  echo Missing app\AuditRecord.App.exe. Unpack the zip so the app folder sits next to scripts.
  exit /b 1
)
set "DOTNET_CLI_TELEMETRY_OPTOUT=1"
if not defined DOTNET_ENVIRONMENT set "DOTNET_ENVIRONMENT=Production"
if not defined ASPNETCORE_ENVIRONMENT set "ASPNETCORE_ENVIRONMENT=%DOTNET_ENVIRONMENT%"
set "ASPNETCORE_DETAILEDERRORS=false"
rem Working directory does not matter. Settings and data come from the exe location.
rem AUDIT_URLS, when set, is passed as --urls. Otherwise app\appsettings.Production.json is used.
if defined AUDIT_URLS (
  "%EXE%" --urls "%AUDIT_URLS%"
) else (
  "%EXE%"
)
set "CODE=%ERRORLEVEL%"
if not "%CODE%"=="0" echo Startup failed with exit %CODE%. See the line above and data\startup-error.log.
exit /b %CODE%
