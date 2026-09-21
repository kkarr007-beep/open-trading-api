@echo off
cd /d "%~dp0"
title 외부조사 유착 분석 - 설치

echo ==================================================
echo   외부조사 유착 분석 프로그램 설치
echo ==================================================
echo.

set PY=
where py >nul 2>&1 && set PY=py
if not defined PY (
  where python >nul 2>&1 && set PY=python
)
if not defined PY (
  echo [X] 파이썬을 찾을 수 없습니다.
  echo.
  echo     전산 담당자에게 아래 내용으로 요청하세요.
  echo       - 파이썬 3.9 이상 ^(64비트^) 설치
  echo       - 설치 시 "Add Python to PATH" 항목 선택
  echo.
  pause
  exit /b 1
)

for /f "delims=" %%v in ('%PY% -c "import sys;print('cp%%d%%d'%%sys.version_info[:2])"') do set TAG=%%v
for /f "delims=" %%v in ('%PY% -c "import sys;print('.'.join(map(str,sys.version_info[:3])))"') do set VER=%%v
for /f "delims=" %%v in ('%PY% -c "import platform;print(platform.machine())"') do set ARCH=%%v

echo  파이썬 %VER% ^(%ARCH%^) 을(를) 찾았습니다.
echo.

%PY% -m pip --version >nul 2>&1
if errorlevel 1 (
  echo [X] pip 이 없습니다. 전산 담당자에게 pip 설치를 요청하세요.
  pause
  exit /b 1
)

if not exist "wheels\%TAG%" (
  echo [!] 파이썬 %VER% 용 설치 파일이 이 묶음에 없습니다.
  echo.
  echo     방법 1: 사내 패키지 저장소가 열려 있으면 아래 명령으로 설치
  echo       %PY% -m pip install pandas numpy openpyxl
  echo.
  echo     방법 2: 담당자에게 "파이썬 %VER% 용 설치 파일 필요" 라고 전달
  echo.
  pause
  exit /b 1
)

echo  설치를 시작합니다. 잠시 기다려 주세요.
echo.
%PY% -m pip install --no-index --find-links="wheels\%TAG%" --find-links="wheels\공통" pandas numpy openpyxl
if errorlevel 1 (
  echo.
  echo  공용 폴더 쓰기 권한이 없는 것 같습니다. 사용자 폴더에 다시 설치합니다.
  echo.
  %PY% -m pip install --user --no-index --find-links="wheels\%TAG%" --find-links="wheels\공통" pandas numpy openpyxl
  if errorlevel 1 (
    echo.
    echo [X] 설치에 실패했습니다. 위 메시지를 담당자에게 전달해 주세요.
    pause
    exit /b 1
  )
)

echo.
echo ==================================================
%PY% -m insurance_audit.check_env
echo ==================================================
echo.
echo  동작 확인을 진행합니다. 가짜 데이터로 시험합니다.
echo.
%PY% -m insurance_audit.make_sample --rows 5000 --out 샘플.csv
echo.
%PY% -m insurance_audit.main --input 샘플.csv --outdir 검증
echo.
echo ==================================================
echo   설치가 끝났습니다.
echo.
echo   검증 폴더의 HTML 파일을 열어 결과가 나오는지 확인하세요.
echo   실제 데이터는 CSV 파일을 "분석실행.bat" 위에 끌어다 놓으면 됩니다.
echo ==================================================
pause
