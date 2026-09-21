@echo off
setlocal enabledelayedexpansion
title 파이썬 설치 확인
cd /d "%~dp0"

echo ==================================================
echo   파이썬 설치 확인
echo ==================================================
echo.

set REAL=0

echo [1] py 실행기 확인
where py >nul 2>&1
if errorlevel 1 (
  echo     없음
) else (
  set VER=
  for /f "tokens=2" %%v in ('py --version 2^>^&1') do set VER=%%v
  if defined VER (
    echo     찾음: 파이썬 !VER!
    py --list 2>nul
    set REAL=1
  ) else (
    echo     명령은 있으나 버전이 나오지 않음
  )
)
echo.

echo [2] python 명령 확인
where python >nul 2>&1
if errorlevel 1 (
  echo     없음
) else (
  set LOC=
  for /f "delims=" %%p in ('where python 2^>nul') do if not defined LOC set LOC=%%p
  set VER2=
  for /f "tokens=2" %%v in ('python --version 2^>^&1') do set VER2=%%v

  echo !LOC! | find /i "\WindowsApps\" >nul
  if not errorlevel 1 (
    echo     [X] 윈도우 기본 껍데기 파일입니다. 실제 파이썬이 아닙니다.
    echo         위치: !LOC!
    echo         이 파일을 실행하면 Microsoft Store 가 열립니다.
  ) else (
    if defined VER2 (
      echo     찾음: 파이썬 !VER2!
      echo     위치: !LOC!
      set REAL=1
    ) else (
      echo     [X] 명령은 있으나 버전이 나오지 않습니다. 정상 설치가 아닙니다.
      echo         위치: !LOC!
    )
  )
)
echo.

echo [3] 실제 설치 위치 확인
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
  )
)
if "!PATHS!"=="0" echo     해당 없음
echo.

echo ==================================================
if "!REAL!"=="1" (
  echo   결과: 파이썬이 설치되어 있습니다.
  echo.
  echo   위 버전 번호를 확인하세요. 3.12 라면
  echo   "유착분석_설치본_파이썬3.12.zip" 을 쓰시면 됩니다.
) else (
  if "!PATHS!"=="1" (
    echo   결과: 설치는 되어 있으나 PATH 에 등록되지 않았습니다.
    echo.
    echo   담당자에게 PATH 등록을 요청하세요.
    echo   또는 위 [3] 경로 안의 python.exe 를 직접 지정해 실행할 수 있습니다.
  ) else (
    echo   결과: 파이썬이 설치되어 있지 않습니다.
    echo.
    echo   전산 담당자에게 아래 내용으로 요청하세요.
    echo.
    echo     - 파이썬 3.12 버전, 윈도우 64비트
    echo     - python.org 오프라인 설치 파일 사용
    echo       ^(Microsoft Store 경로는 사내에서 막혀 있는 경우가 많습니다^)
    echo     - 설치 시 "Add Python to PATH" 항목 반드시 선택
    echo     - 업무 목적: 심사 데이터 분석
  )
)
echo ==================================================
echo.
pause
