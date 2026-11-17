@echo off
cd /d "%~dp0"
copy /b Setup-0.bin+Setup-1.bin+Setup-2.bin+Setup-3.bin+Setup-4.bin+Setup-5.bin AuditRecord-Setup.exe
echo Done. Run AuditRecord-Setup.exe
pause
