@echo off
cd /d "%~dp0"
title 외부조사 유착 분석

if "%~1"=="" (
  echo ==================================================
  echo   사용법
  echo ==================================================
  echo.
  echo   분석할 CSV 파일을 이 파일 위에 끌어다 놓으세요.
  echo.
  echo   연도를 골라 보거나 다른 조건을 주려면 명령창에서 실행하세요.
  echo     py -m insurance_audit.main --input 파일.csv --year 2024 2025
  echo.
  pause
  exit /b
)

set PY=
where py >nul 2>&1 && set PY=py
if not defined PY set PY=python

%PY% -m insurance_audit.main --input "%~1" --outdir 결과
echo.
echo  결과 폴더를 확인하세요.
pause
