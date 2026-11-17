# 내려받기

## 처음 설치 (1.3.0판)

1. `Setup-0.bin`부터 `Setup-5.bin`까지 6개와 `join-small.cmd`를 **같은 폴더**에 받습니다.
2. `join-small.cmd`를 실행하면 그 폴더에 `AuditRecord-Setup.exe`가 만들어집니다.
3. `AuditRecord-Setup.exe`를 실행해 설치합니다.

`join-small.cmd`가 막히면 그 폴더에서 명령 프롬프트를 열고 아래를 입력합니다.

```bash
copy /b Setup-0.bin+Setup-1.bin+Setup-2.bin+Setup-3.bin+Setup-4.bin+Setup-5.bin AuditRecord-Setup.exe
```

## 1.4.0판으로 바꾸기 (업무 일정·사규 추가)

1.3.0판을 설치한 뒤 `AuditRecord-Patch-1.4.0.exe` 하나만 받아 실행합니다.
exe가 막히면 `AuditRecord-Patch-1.4.0.zip`을 받아 풀고 `apply-patch.cmd`를 실행합니다.
처음 설치하는 경우에도 위의 1.3.0판을 설치한 다음 이 패치를 실행하면 됩니다.

패치는 프로그램 파일(audit-record.jar) 하나만 바꿉니다. 감사 기록과 자료는 그대로 둡니다.

### 예전 업무 일정 관리·사규 관리 데이터 옮기기

1. 예전 업무·일정 관리 v2(HTML)에서 **설정 · 데이터 → JSON 내보내기**로 파일을 받습니다.
2. 예전 사규 관리 시스템 v4(HTML)에서 **설정·백업 → JSON 백업**으로 파일을 받습니다.
3. 새 프로그램의 **업무 설정 → 예전 프로그램에서 옮겨 오기**에 1번 파일을,
   **사규 설정 → 예전 프로그램에서 옮겨 오기**에 2번 파일을 넣습니다. 처음에는 '전부 바꾸기'를 고릅니다.
