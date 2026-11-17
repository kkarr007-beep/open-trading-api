; 경영진단 Portal 패치. 이미 설치된 프로그램의 실행 파일(audit-record.jar) 하나만 바꾼다. 기록과 자료는 건드리지 않는다.
; 1.3.0판(이름: 감사기록)에 적용하면 바로가기와 제거 프로그램을 새 이름으로 바꾼다. 기록 폴더 이름은 그대로 둔다.
Unicode true
SetCompressor /SOLID lzma
RequestExecutionLevel user
!define APP_NAME "경영진단 Portal"
!define OLD_NAME "감사기록"
!define OLD_NAME2 "감사 포털"
!define DATA_DIR "감사기록"
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
    MessageBox MB_ICONSTOP "설치된 ${APP_NAME}(이전 이름: ${OLD_NAME})을(를) 찾지 못했습니다.$\r$\n이 패치는 1.3.0판 이상에만 적용됩니다. 먼저 설치 파일(AuditRecord-Setup)로 설치해 주세요."
    Abort
  found:
  MessageBox MB_OKCANCEL "${APP_NAME}을(를) ${APP_VERSION}판으로 고칩니다.$\r$\n프로그램 창이 열려 있으면 먼저 닫아 주세요.$\r$\n$\r$\n기록과 자료는 그대로 둡니다." IDOK go
    Abort
  go:
  SetOutPath "$0"
  File "${STAGE}\audit-record.jar"

  ; 바로가기를 새 이름으로 바꾼다. 실행 인자(기록 폴더)는 그대로다.
  Delete "$DESKTOP\${OLD_NAME}.lnk"
  RMDir /r "$SMPROGRAMS\${OLD_NAME}"
  Delete "$DESKTOP\${OLD_NAME2}.lnk"
  RMDir /r "$SMPROGRAMS\${OLD_NAME2}"
  CreateShortCut "$DESKTOP\${APP_NAME}.lnk" "$0\jre\bin\javaw.exe" '--enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -cp "$0\audit-record.jar;$0\fx\*" local.audit.App "--audit.home=$PROFILE\${DATA_DIR}"' "$0\audit-record.ico" 0 SW_SHOWNORMAL "" "${APP_NAME}"
  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$0\jre\bin\javaw.exe" '--enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -cp "$0\audit-record.jar;$0\fx\*" local.audit.App "--audit.home=$PROFILE\${DATA_DIR}"' "$0\audit-record.ico" 0 SW_SHOWNORMAL "" "${APP_NAME}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\수집함 폴더.lnk" "$PROFILE\${DATA_DIR}\inbox"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME} 제거.lnk" "$0\uninstall.exe"

  ; 새 이름의 바로가기도 지우는 제거 프로그램으로 바꾼다.
  StrCpy $INSTDIR "$0"
  WriteUninstaller "$0\uninstall.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${APP_VERSION}"
  DetailPrint "${APP_VERSION}판으로 고쳤습니다."
  MessageBox MB_OK "${APP_VERSION}판으로 고쳤습니다. 바탕화면의 '${APP_NAME}' 아이콘으로 다시 여세요."
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\${APP_NAME}.lnk"
  Delete "$DESKTOP\${OLD_NAME}.lnk"
  Delete "$DESKTOP\${OLD_NAME2}.lnk"
  RMDir /r "$SMPROGRAMS\${OLD_NAME2}"
  RMDir /r "$SMPROGRAMS\${APP_NAME}"
  RMDir /r "$SMPROGRAMS\${OLD_NAME}"
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
