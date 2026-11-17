# 내려받기

## 처음 설치 (1.3.0판)

1. `Setup-0.bin`부터 `Setup-5.bin`까지 6개와 `join-small.cmd`를 **같은 폴더**에 받습니다.
2. `join-small.cmd`를 실행하면 그 폴더에 `AuditRecord-Setup.exe`가 만들어집니다.
3. `AuditRecord-Setup.exe`를 실행해 설치합니다.

`join-small.cmd`가 막히면 그 폴더에서 명령 프롬프트를 열고 아래를 입력합니다.

```bash
copy /b Setup-0.bin+Setup-1.bin+Setup-2.bin+Setup-3.bin+Setup-4.bin+Setup-5.bin AuditRecord-Setup.exe
```

## 고친 판으로 바꾸기

이미 1.3.0판 이상을 설치했다면 `AuditRecord-Patch-<판>.exe` 하나만 받아 실행합니다.
exe가 막히면 같은 이름의 zip을 받아 풀고 `apply-patch.cmd`를 실행합니다.
