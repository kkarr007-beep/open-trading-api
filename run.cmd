@echo off
rem 감사기록 실행 (Windows). Java 17 이상이 필요합니다.
chcp 65001 >nul
cd /d "%~dp0"
if not exist target\audit-record.jar (
  echo 실행 파일이 없습니다. 먼저 build.cmd 를 실행하세요.
  pause
  exit /b 1
)
java -Dfile.encoding=UTF-8 -jar target\audit-record.jar
pause
