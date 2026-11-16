@echo off
rem Patch for AuditRecord: replaces only audit-record.jar. Data is not touched.
set "TARGET=%LOCALAPPDATA%\Programs\AuditRecordLocal"
if not exist "%TARGET%\lib" (
  echo AuditRecord 1.3.0 or later is not installed. Run AuditRecord-Setup first.
  pause
  exit /b 1
)
echo Close the AuditRecord window first, then press any key.
pause >nul
copy /y "%~dp0audit-record.jar" "%TARGET%\audit-record.jar"
if errorlevel 1 ( echo Failed. Is the program still open? & pause & exit /b 1 )
echo Done. Start AuditRecord again from the desktop icon.
pause
