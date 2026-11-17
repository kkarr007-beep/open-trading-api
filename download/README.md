# 내려받기

## 처음 설치 (감사 포털 1.5.0판)

1. `Setup-0.bin`부터 `Setup-5.bin`까지 6개와 `join-small.cmd`를 **같은 폴더**에 받습니다.
2. `join-small.cmd`를 실행하면 그 폴더에 `AuditRecord-Setup.exe`가 만들어집니다.
3. `AuditRecord-Setup.exe`를 실행해 설치합니다. 바탕화면과 시작 메뉴에 **감사 포털** 아이콘이 생깁니다.

이미 1.3.0판(감사기록)을 쓰고 있어도 이 설치 파일로 덮어 설치하면 됩니다. 기록과 자료(사용자 폴더\감사기록)는 그대로 쓰고, 예전 '감사기록' 바로가기는 치웁니다.

합친 파일이 맞는지 보려면 명령 프롬프트에서 `certutil -hashfile AuditRecord-Setup.exe SHA256`을 실행해 아래 값과 같은지 봅니다.

```text
6df3f1454dfc165a5d858967c277c3673918d2791ffd8790f65dec3f79460358
```

`join-small.cmd`가 막히면 그 폴더에서 명령 프롬프트를 열고 아래를 입력합니다.

```bash
copy /b Setup-0.bin+Setup-1.bin+Setup-2.bin+Setup-3.bin+Setup-4.bin+Setup-5.bin AuditRecord-Setup.exe
```

## 1.5.0판으로 바꾸기 (감사 포털 · 업무 일정·사규)

1.3.0·1.4.0판을 쓰는 중이면 위의 설치 파일 대신 `AuditRecord-Patch-1.5.0.exe` 하나만 받아 실행해도 됩니다.
exe가 막히면 `AuditRecord-Patch-1.5.0.zip`을 받아 풀고 `apply-patch.cmd`를 실행합니다.

패치는 프로그램 파일(audit-record.jar) 하나를 바꾸고, 바탕화면·시작 메뉴의 이름을 '감사 포털'로 바꿉니다. 감사 기록과 자료(사용자 폴더\감사기록)는 그대로 둡니다.
zip의 apply-patch.cmd는 프로그램 파일만 바꾸므로 바로가기 이름은 '감사기록'으로 남습니다.

### 예전 업무 일정 관리·사규 관리 데이터 옮기기

1. 예전 업무·일정 관리 v2(HTML)에서 **설정 · 데이터 → JSON 내보내기**로 파일을 받습니다.
2. 예전 사규 관리 시스템 v4(HTML)에서 **설정·백업 → JSON 백업**으로 파일을 받습니다.
3. 감사 포털 왼쪽 메뉴의 **백업 옮겨 오기**에 두 파일을 한꺼번에 넣습니다. 처음에는 '전부 바꾸기'를 고릅니다.
   날짜가 다른 백업이 여럿이면 '합치기'로 차례로 넣으면 됩니다.
