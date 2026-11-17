@echo off
rem 경영진단 Portal 빌드 (Windows). JDK 21 이상과 Maven이 필요합니다.
cd /d "%~dp0"
call mvn -q -DskipTests package
if errorlevel 1 ( echo 빌드에 실패했습니다. & pause & exit /b 1 )
echo 빌드를 마쳤습니다. run.cmd 로 실행하세요.
pause
