@echo off
title 파이썬 설치 확인
cd /d "%~dp0"

echo ==================================================
echo   파이썬 설치 확인
echo ==================================================
echo.

set FOUND=0

echo [1] py 실행기 확인
where py >nul 2>&1
if not errorlevel 1 (
  for /f "delims=" %%v in ('py --version 2^>^&1') do echo     찾음: %%v
  echo.
  echo     설치된 모든 버전:
  py --list 2>nul
  set FOUND=1
) else (
  echo     없음
)
echo.

echo [2] python 명령 확인
where python >nul 2>&1
if not errorlevel 1 (
  for /f "delims=" %%v in ('python --version 2^>^&1') do echo     찾음: %%v
  for /f "delims=" %%v in ('where python') do echo     위치: %%v
  set FOUND=1
) else (
  echo     없음
)
echo.

echo [3] 자주 쓰는 설치 위치 확인
set PATHS=0
for %%p in (
  "%LOCALAPPDATA%\Programs\Python"
  "C:\Python39" "C:\Python310" "C:\Python311" "C:\Python312" "C:\Python313"
  "C:\Program Files\Python39" "C:\Program Files\Python310"
  "C:\Program Files\Python311" "C:\Program Files\Python312"
  "C:\Program Files\Python313"
  "C:\ProgramData\Anaconda3" "%USERPROFILE%\Anaconda3"
  "C:\ProgramData\miniconda3" "%USERPROFILE%\miniconda3"
) do (
  if exist %%p (
    echo     있음: %%p
    set PATHS=1
    set FOUND=1
  )
)
if "%PATHS%"=="0" echo     해당 없음
echo.

echo ==================================================
if "%FOUND%"=="1" (
  echo   파이썬이 설치되어 있습니다.
  echo.
  echo   위에 보이는 버전 번호를 확인하세요. 예를 들어 3.12 라면
  echo   "유착분석_설치본_파이썬3.12.zip" 을 쓰시면 됩니다.
  echo.
  echo   [3] 에만 나오고 [1][2] 가 없다면 설치는 됐지만
  echo   PATH 에 등록되지 않은 상태입니다. 담당자에게
  echo   "Add Python to PATH" 설정을 요청하세요.
) else (
  echo   파이썬이 설치되어 있지 않습니다.
  echo.
  echo   전산 담당자에게 아래 내용으로 요청하세요.
  echo     - 파이썬 3.9 이상 64비트 설치
  echo     - 설치 시 "Add Python to PATH" 항목 선택
)
echo ==================================================
echo.
pause
