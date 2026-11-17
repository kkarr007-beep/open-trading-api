@echo off
rem 경영진단 Portal 실행 (Windows, 설치하지 않고). Java 21 이상이 필요합니다.
rem 이 방식은 화면 엔진이 없어 Edge 창이나 브라우저로 열립니다. 자체 창은 설치 파일로 설치하면 됩니다.
chcp 65001 >nul
cd /d "%~dp0"
if not exist target\audit-record.jar (
  echo 실행 파일이 없습니다. 먼저 build.cmd 를 실행하세요.
  pause
  exit /b 1
)
java -Dfile.encoding=UTF-8 -jar target\audit-record.jar
pause
