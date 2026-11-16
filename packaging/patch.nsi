; 감사기록 패치. 이미 설치된 프로그램의 실행 파일(audit-record.jar) 하나만 바꾼다. 기록과 자료는 건드리지 않는다.
Unicode true
SetCompressor /SOLID lzma
RequestExecutionLevel user
!define APP_NAME "감사기록"
!define APP_ID "AuditRecordLocal"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"
Name "${APP_NAME} 패치 ${APP_VERSION}"
OutFile "${OUTFILE}"
Icon "audit-record.ico"
ShowInstDetails show
Page instfiles
LoadLanguageFile "${NSISDIR}\Contrib\Language files\Korean.nlf"

Section "Patch"
  ReadRegStr $0 HKCU "${UNINST_KEY}" "InstallLocation"
  StrCmp $0 "" 0 +2
    StrCpy $0 "$LOCALAPPDATA\Programs\${APP_ID}"
  IfFileExists "$0\lib\*.jar" found 0
    MessageBox MB_ICONSTOP "설치된 ${APP_NAME}을(를) 찾지 못했습니다.$\r$\n이 패치는 1.3.0판 이상에만 적용됩니다. 먼저 설치 파일(AuditRecord-Setup)로 설치해 주세요."
    Abort
  found:
  MessageBox MB_OKCANCEL "${APP_NAME}을(를) ${APP_VERSION}판으로 고칩니다.$\r$\n${APP_NAME} 창이 열려 있으면 먼저 닫아 주세요.$\r$\n$\r$\n기록과 자료는 그대로 둡니다." IDOK go
    Abort
  go:
  SetOutPath "$0"
  File "${STAGE}\audit-record.jar"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${APP_VERSION}"
  DetailPrint "${APP_VERSION}판으로 고쳤습니다."
  MessageBox MB_OK "${APP_VERSION}판으로 고쳤습니다. 바탕화면의 ${APP_NAME} 아이콘으로 다시 여세요."
SectionEnd
