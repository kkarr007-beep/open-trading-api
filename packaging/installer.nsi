; 경영진단 Portal 설치 파일. Linux나 Windows의 makensis로 만든다 (packaging/build-installer.sh).
; 관리자 권한 없이 현재 사용자에게만 설치한다. Java를 따로 설치하지 않아도 되도록 실행 환경을 함께 넣는다.
Unicode true
SetCompressor /SOLID lzma
SetCompressorDictSize 64
RequestExecutionLevel user

!define APP_NAME "경영진단 Portal"
; 기록과 자료 폴더. 1.3.0판부터 쓰던 이름을 그대로 둔다(이름을 바꾸면 기존 기록을 못 찾는다).
!define DATA_DIR "감사기록"
!define APP_ID "AuditRecordLocal"
!ifndef APP_VERSION
  !define APP_VERSION "3.5.0"
!endif
!ifndef STAGE
  !define STAGE "stage"
!endif
!ifndef OUTFILE
  !define OUTFILE "AuditRecord-Setup.exe"
!endif
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"
!define RUN_ARGS '--enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -cp "$INSTDIR\audit-record.jar;$INSTDIR\fx\*" local.audit.App "--audit.home=$PROFILE\${DATA_DIR}"'

Name "${APP_NAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${APP_ID}"
InstallDirRegKey HKCU "${UNINST_KEY}" "InstallLocation"
BrandingText "${APP_NAME} ${APP_VERSION}"
VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "FileDescription" "${APP_NAME} 설치"
VIAddVersionKey "FileVersion" "${APP_VERSION}"
VIAddVersionKey "LegalCopyright" ""

!include "MUI2.nsh"
!define MUI_ICON "audit-record.ico"
!define MUI_UNICON "audit-record.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TEXT "이 컴퓨터에서 혼자 쓰는 경영진단 Portal(업무 일정·감사기록·사규·자료·경영 현황판)을 설치합니다.$\r$\n$\r$\n· 관리자 권한이 필요 없습니다.$\r$\n· Java를 따로 설치하지 않아도 됩니다.$\r$\n· 기록과 자료는 '$PROFILE\${DATA_DIR}' 폴더에 저장되며, 프로그램을 지워도 남습니다.$\r$\n$\r$\n경영진단 Portal이 켜져 있으면 먼저 닫아 주세요."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_FUNCTION LaunchApp
!define MUI_FINISHPAGE_RUN_TEXT "${APP_NAME} 실행"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Korean"

Function LaunchApp
  SetOutPath "$INSTDIR"
  Exec '"$INSTDIR\jre\bin\javaw.exe" ${RUN_ARGS}'
FunctionEnd

Section "Install"
  SetOutPath "$INSTDIR"
  ; 이전 판의 실행 환경이 남아 섞이지 않게 한다.
  RMDir /r "$INSTDIR\jre"
  RMDir /r "$INSTDIR\fx"
  RMDir /r "$INSTDIR\lib"
  File "${STAGE}\audit-record.jar"
  File "audit-record.ico"
  File "${STAGE}\읽어보세요.txt"
  SetOutPath "$INSTDIR\jre"
  File /r "${STAGE}\jre\*.*"
  SetOutPath "$INSTDIR\fx"
  File "${STAGE}\fx\*.jar"
  SetOutPath "$INSTDIR\lib"
  File "${STAGE}\lib\*.jar"

  CreateDirectory "$PROFILE\${DATA_DIR}\inbox"
  ; 1.3.0판 이전 이름의 바로가기를 치운다.
  Delete "$DESKTOP\감사기록.lnk"
  RMDir /r "$SMPROGRAMS\감사기록"
  Delete "$DESKTOP\감사 포털.lnk"
  RMDir /r "$SMPROGRAMS\감사 포털"
  SetOutPath "$INSTDIR"
  CreateShortCut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\jre\bin\javaw.exe" '${RUN_ARGS}' "$INSTDIR\audit-record.ico" 0 SW_SHOWNORMAL "" "${APP_NAME}"
  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\jre\bin\javaw.exe" '${RUN_ARGS}' "$INSTDIR\audit-record.ico" 0 SW_SHOWNORMAL "" "${APP_NAME}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\수집함 폴더.lnk" "$PROFILE\${DATA_DIR}\inbox"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME} 제거.lnk" "$INSTDIR\uninstall.exe"

  WriteUninstaller "$INSTDIR\uninstall.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\audit-record.ico"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\${APP_NAME}.lnk"
  Delete "$DESKTOP\감사기록.lnk"
  RMDir /r "$SMPROGRAMS\감사기록"
  Delete "$DESKTOP\감사 포털.lnk"
  RMDir /r "$SMPROGRAMS\감사 포털"
  RMDir /r "$SMPROGRAMS\${APP_NAME}"
  RMDir /r "$INSTDIR\jre"
  RMDir /r "$INSTDIR\fx"
  RMDir /r "$INSTDIR\lib"
  Delete "$INSTDIR\audit-record.jar"
  Delete "$INSTDIR\audit-record.ico"
  Delete "$INSTDIR\읽어보세요.txt"
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
  MessageBox MB_OK "프로그램을 지웠습니다.$\r$\n기록과 자료는 '$PROFILE\${DATA_DIR}' 폴더에 그대로 있습니다. 필요 없으면 그 폴더를 직접 지우세요."
SectionEnd
