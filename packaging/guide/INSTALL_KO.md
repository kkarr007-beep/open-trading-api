# 감사기록 설치 안내

Windows 감사 서버 한 대에 압축을 풀고, 그 PC의 브라우저로 첫 관리자를 만든 다음, 필요할 때만 내부 HTTPS를 엽니다. 다른 컴퓨터는 브라우저만 씁니다. 인터넷에서 프로그램을 추가로 받지 않습니다. .NET을 따로 설치하지 않습니다.

아래 Windows 명령은 복사해서 값을 바꾼 예시입니다. 이 파일을 만든 환경은 Linux이며, 서비스 등록, `icacls`, 방화벽, 내부 CA, PFX는 여기서 실행하지 않았습니다.

## 1. 받은 파일 확인

PowerShell을 압축을 푼 폴더가 아니라, zip이 있는 폴더에서 엽니다.

```powershell
Get-FileHash -Algorithm SHA256 .\audit-record-win-x64.zip
Get-Content .\SHA256SUMS.txt
```

`Get-FileHash`의 Hash 값이 `SHA256SUMS.txt`에서 `audit-record-win-x64.zip` 줄의 앞 64자와 같아야 합니다. 같으면 압축을 풉니다. Windows에는 `sha256sum -c`가 없습니다. 패키지 안의 `SHA256SUMS.txt`는 `감사기록` 폴더 기준이고 구분자가 `/`입니다. 파일마다 `Get-FileHash -Algorithm SHA256`로 그 줄과 비교합니다.

`run.sh`는 Linux용이며 LF입니다. `.cmd`와 `.ps1`만 CRLF입니다. `.ps1`은 UTF-8 BOM입니다. `run.sh`를 메모장으로 저장하지 마세요.

## 2. 폴더

`C:\감사기록`처럼 전용 폴더에 풉니다. 개인 업무 PC와 겸하지 않습니다.

```text
감사기록\
  app\        프로그램. IIS에 올리지 않습니다. web.config 는 넣지 않습니다
  data\       audit.db. 시작 실패 한 줄은 startup-error.log
  files\      첨부 바이트. 나스카로 여는 사람은 여기만 읽기 전용으로 볼 수 있습니다
  backup\     이 프로그램이 만든 백업
  app-keys\   로그인 키, PFX, 암호 파일
  config\     예시. 이 폴더 자체는 읽히지 않습니다
  scripts\    install.cmd, start.cmd, service.ps1
  guide\      이 문서
```

## 3. 스크립트가 차단된 경우

`install.cmd`는 PowerShell을 이 프로세스에만 `-ExecutionPolicy RemoteSigned`로 엽니다. 컴퓨터 전체 정책을 바꾸지 않고, `Bypass`는 쓰지 않습니다. 인터넷에서 받은 파일이 차단되어 있으면 RemoteSigned가 스크립트를 거부합니다. 관리자 PowerShell에서 한 번만 차단을 풉니다.

```powershell
Unblock-File -LiteralPath C:\감사기록\scripts\install.ps1
Unblock-File -LiteralPath C:\감사기록\scripts\service.ps1
Unblock-File -LiteralPath C:\감사기록\scripts\uninstall.ps1
```

그 다음 탐색기에서 `C:\감사기록\scripts\install.cmd`를 실행합니다. 없는 `data`, `files`, `backup`, `app-keys`만 만듭니다.

`install.cmd`와 `start.cmd`가 화면에 적는 문장은 ASCII입니다. 콘솔 코드 페이지가 달라도 한글이 깨진 문장으로 나오지 않습니다. 스크립트 첫머리의 `chcp 65001`은 이후 출력을 UTF-8로 맞춥니다. 한글 설명은 이 문서와 UTF-8 BOM인 `.ps1`에 있습니다.

## 4. 간편 설치는 이 서버에서만

내부망 주소를 열기 전에 합니다. `scripts\간편설치.cmd`를 실행하면 1번부터 3번까지를 대신합니다.

1. `scripts\간편설치.cmd`를 실행합니다. 없는 폴더(`data`, `files`, `backup`, `app-keys`)를 만들고, 프로그램을 켜고, 이 PC의 브라우저를 엽니다. 하나씩 하려면 `scripts\install.cmd` 다음에 `scripts\start.cmd`를 씁니다.
2. 그 PC의 브라우저로만 `http://127.0.0.1:47321/account/setup`이 열립니다. 다른 컴퓨터에서 이 주소를 열면 거부합니다.
3. 파트 이름과 **접속 비밀번호**(12자 이상)를 정합니다. 관리자 계정은 이때 같이 만들어집니다. 로그인 이름은 `admin`입니다. 설치를 마치면 이 화면은 사라집니다.
4. 서버의 내부망 주소를 파트원에게 알려 줍니다. 파트원은 그 주소를 열고 **비밀번호만 눌러** 들어옵니다.
5. 관리 화면에서 보존 기준을 확인하기 전에는 자동 삭제가 돌지 않습니다.

접속 비밀번호는 설정 화면에서 관리자가 바꿉니다. 다섯 번 틀리면 15분 쉬고, 그 셈은 계정이 아니라 이 서버 단위입니다. 역할을 나눠 쓰려면 관리 화면에서 계정을 더 만들고, 로그인 화면의 `계정 이름으로 들어가기`를 씁니다.

`간편설치.cmd` 창을 닫으면 프로그램도 멈춥니다. 서비스로 늘 켜 두려면 `scripts\service.ps1 -Action install`을 씁니다.

`start.cmd` 창을 닫으면 프로그램도 멈춥니다. 창에 한글 한 줄이 나오고 끝나면 `data\startup-error.log`에도 같은 한 줄이 있습니다. 알려진 시작 오류(없는 PFX, 틀린 PFX 암호, Production과 Demo를 같은 데이터에 섞음, 루프백이 아닌 HTTP)는 스택을 콘솔에 찍지 않고 종료 코드 2로 끝납니다.

## 5. 내부 HTTPS 설정 파일

프로그램이 읽는 파일은 `app\appsettings.json`과, 환경이 Production일 때 `app\appsettings.Production.json`입니다. `config\`의 예시를 그 이름으로 복사한 뒤에만 고칩니다.

```powershell
Copy-Item C:\감사기록\config\appsettings.Production.template.json C:\감사기록\app\appsettings.Production.json
notepad C:\감사기록\app\appsettings.Production.json
```

`Audit:BindAddress`를 비우면 `127.0.0.1`입니다. 다른 PC는 접속하지 못합니다. 서버의 내부 IP로 들으려면 그 IP를 적습니다. `0.0.0.0`은 그 서버의 모든 인터페이스입니다. 특정 IP를 우선합니다. `Audit:Port`를 비우면 인증서가 있을 때 8443입니다.

인증서 경로가 비어 있으면 HTTP이고, 주소는 `--urls`, 그다음 `AUDIT_URLS`, 그다음 `Audit:Urls`, 없으면 `http://127.0.0.1:47321`입니다. 인증서 경로가 있으면 HTTP 주소는 쓰지 않고 아래 BindAddress와 Port로 HTTPS를 듣습니다.

`Audit:BackupAllowRoot`는 백업을 쓸 로컬 폴더입니다. 비우면 패키지의 `backup`입니다. 서비스 계정이 그 폴더를 수정할 수 있어야 합니다. `data`나 `files` 안은 쓰지 마세요.

예시입니다. IP, 공유 이름, PFX 경로는 실제 값으로 바꿉니다.

```json
{
  "Logging": {
    "LogLevel": {
      "Default": "Information",
      "Microsoft.AspNetCore": "Warning"
    }
  },
  "Audit": {
    "Mode": "Production",
    "Root": "..",
    "Urls": "http://127.0.0.1:47321",
    "AllowInsecureHttp": false,
    "FilesShareUnc": "\\\\audit-server\\audit-files",
    "BackupAllowRoot": "D:\\audit-backup",
    "WorkerSeconds": 60,
    "CertificatePath": "C:\\감사기록\\app-keys\\audit.pfx",
    "BindAddress": "10.1.2.10",
    "Port": 8443
  }
}
```

`AllowInsecureHttp`는 false로 둡니다. true는 인증서 없이 루프백이 아닌 HTTP를 허용한다는 뜻이고, 시작은 되지만 쓰지 않습니다.

암호는 json에 적지 않습니다.

## 6. PFX와 암호 파일

내부 CA가 서버 이름 또는 브라우저에 칠 IP가 주체 대체 이름(SAN)에 들어간 PFX를 발급합니다. 이름과 다르면 브라우저가 경고합니다. PFX를 `C:\감사기록\app-keys\audit.pfx`에 둡니다.

암호 파일은 한 줄입니다. 화면에 암호가 보이지 않게 만듭니다.

```powershell
$secure = Read-Host -AsSecureString 'PFX password'
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
  $bytes = [Text.Encoding]::UTF8.GetBytes($plain)
  [IO.File]::WriteAllBytes('C:\감사기록\app-keys\cert-password.txt', $bytes)
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
```

클라이언트 PC에는 PFX가 아니라 내부 CA 루트 인증서를 “신뢰할 수 있는 루트 인증 기관”에 가져옵니다. 서버 이름을 외부에서 조회하지 않도록, 그 PC가 이미 내부 CA를 믿어야 합니다.

## 7. 서비스 계정

LocalSystem은 쓰지 않습니다. 로컬 계정 예시입니다. 암호는 화면과 이 문서에 남기지 않습니다.

```powershell
$pw = Read-Host -AsSecureString 'audit-svc password'
New-LocalUser -Name 'audit-svc' -Password $pw -PasswordNeverExpires -UserMayNotChangePassword -AccountNeverExpires -Description '감사기록 서비스'
```

이 계정에 “서비스로 로그온”이 필요합니다. `secpol.msc` → 로컬 정책 → 사용자 권한 할당 → 서비스로 로그온 → `.\audit-svc` 추가. 도메인 계정이면 `DOMAIN\audit-svc`를 추가합니다.

그룹 관리 서비스 계정(gMSA)은 암호를 넘기지 않습니다. 도메인에서 계정을 만든 뒤 이 서버에 설치하고, 계정 이름을 `DOMAIN\audit-svc$`로 서비스에 연결합니다. 포함된 `service.ps1 -Action install`은 암호를 묻는 일반 계정용입니다. gMSA에는 그 스크립트의 install을 쓰지 마세요. 아래는 실행하지 않은 예시입니다.

```powershell
Install-ADServiceAccount -Identity 'audit-svc'
New-Service -Name 'AuditRecord' -BinaryPathName '"C:\감사기록\app\AuditRecord.App.exe"' -StartupType Automatic -DisplayName '감사기록'
sc.exe config AuditRecord obj= 'DOMAIN\audit-svc$' password= ''
```

## 8. 폴더 권한과 files 공유

상속을 끊으므로 계정 이름을 확인한 뒤에 실행합니다. `(OI)(CI)`는 앞으로 생기는 파일과 폴더에 상속되고, `/T`는 이미 있는 파일에도 적용합니다. 이 명령은 실행하지 않았습니다.

```text
icacls C:\감사기록\app /inheritance:r /grant:r ".\audit-svc:(OI)(CI)RX" "BUILTIN\Administrators:(OI)(CI)F" /T
icacls C:\감사기록\app-keys /inheritance:r /grant:r ".\audit-svc:(OI)(CI)M" "BUILTIN\Administrators:(OI)(CI)F" /T
icacls C:\감사기록\data /inheritance:r /grant:r ".\audit-svc:(OI)(CI)M" "BUILTIN\Administrators:(OI)(CI)F" /T
icacls C:\감사기록\backup /inheritance:r /grant:r ".\audit-svc:(OI)(CI)M" "BUILTIN\Administrators:(OI)(CI)F" /T
icacls C:\감사기록\files /inheritance:r /grant:r ".\audit-svc:(OI)(CI)M" "BUILTIN\Administrators:(OI)(CI)F" /T
icacls C:\감사기록\app-keys\audit.pfx /inheritance:r /grant:r ".\audit-svc:R" "BUILTIN\Administrators:F"
icacls C:\감사기록\app-keys\cert-password.txt /inheritance:r /grant:r ".\audit-svc:R" "BUILTIN\Administrators:F"
```

`data`와 `backup`은 공유하지 않습니다. `files`만, 나스카로 파일을 여는 그룹에 읽기 전용 SMB를 줄 수 있습니다. 서버 프로그램은 그 공유를 열지 않습니다. 사용자 PC가 `\\서버\audit-files`를 엽니다.

```powershell
icacls C:\감사기록\files /grant:r "DOMAIN\nasca-readers:(OI)(CI)R" /T
New-SmbShare -Name 'audit-files' -Path 'C:\감사기록\files' -ReadAccess 'DOMAIN\nasca-readers' -FullAccess '.\audit-svc'
```

`Audit:FilesShareUnc`에는 사용자에게 보여 줄 `\\audit-server\audit-files`를 적습니다.

## 9. 서비스 등록

일반 계정은 관리자 PowerShell에서 합니다. 암호 프롬프트가 뜨고, 값은 출력되지 않습니다. `sc.exe`는 암호를 물어보지 않으므로 그 방식으로 암호를 넘기지 않습니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File C:\감사기록\scripts\service.ps1 -Action install -Account '.\audit-svc' -CertPasswordFile 'C:\감사기록\app-keys\cert-password.txt'
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File C:\감사기록\scripts\service.ps1 -Action start
```

멈추고 등록만 지울 때입니다. `data`, `files`, `backup`은 남습니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File C:\감사기록\scripts\service.ps1 -Action stop
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File C:\감사기록\scripts\service.ps1 -Action uninstall
```

## 10. 방화벽

127.0.0.1만 들을 때는 인바운드 규칙이 필요 없습니다. 내부 HTTPS를 연 뒤에만, Domain과 Private 프로필로 제한합니다. 이 프로그램은 밖으로 나가지 않으므로 아웃바운드 규칙은 만들지 않습니다. 47321을 인터넷에 열지 않습니다. 대역은 실제 감사망으로 바꿉니다. 실행하지 않은 예시입니다.

```powershell
New-NetFirewallRule -DisplayName '감사기록 내부 HTTPS' -Direction Inbound -Action Allow -Profile Domain,Private -Protocol TCP -LocalPort 8443 -RemoteAddress 10.1.2.0/24 -Program 'C:\감사기록\app\AuditRecord.App.exe'
```

## 11. 서비스가 바로 죽을 때

1. `data\startup-error.log` 한 줄을 봅니다. 없는 PFX, 틀린 암호, 모드가 섞임, 루프백이 아닌 HTTP는 여기에 한 줄만 남고 스택은 없습니다.
2. `start.cmd`로 같은 설정을 켜 보면 그 창에 같은 한 줄이 나옵니다.
3. 이벤트 뷰어 → Windows 로그 → 응용 프로그램에서 소스 `.NET Runtime` 또는 `AuditRecord`를 봅니다.
4. 포트가 이미 쓰이는지 확인합니다.

```powershell
Get-NetTCPConnection -LocalPort 8443 -ErrorAction SilentlyContinue
netstat -ano | findstr :8443
```

다른 프로그램이 8443을 쓰고 있으면 `Audit:Port`를 바꾸거나 그 프로그램을 멈춥니다. 방화벽은 수신을 막을 뿐, 포트 충돌의 원인은 아닙니다.

예시 모드에서만 `Audit:DemoClockOffsetDays`(환경 변수 `Audit__DemoClockOffsetDays`)로 시계를 며칠 뒤로 보낼 수 있습니다. 운영 모드에서 0이 아닌 값을 주면 시작하지 않고 한글 한 줄로 끝납니다. 관리 화면의 “만료 삭제 지금 실행”은 이미 연한이 지난, 보류가 아닌 건만 지우고 이력에 남깁니다.

## 12. 첫 Windows 확인 목록

이 환경에서는 아직 체크하지 못했습니다. 서버에서 처음 켤 때 순서대로 봅니다.

- [ ] zip 해시가 `Get-FileHash`와 `SHA256SUMS.txt`에서 같다
- [ ] `Unblock-File` 뒤 `install.cmd`가 폴더만 만든다
- [ ] `start.cmd` 후 `http://127.0.0.1:47321/account/setup`에서 첫 관리자를 만든다
- [ ] 다른 PC에서 그 설정 주소는 열리지 않는다
- [ ] PFX의 SAN이 브라우저에 칠 이름 또는 IP와 같다
- [ ] 암호 파일과 PFX는 서비스 계정만 읽는다
- [ ] `BindAddress`에 서버 IP를 적기 전에는 127.0.0.1이다
- [ ] 클라이언트에 내부 CA 루트가 신뢰되어 있다
- [ ] 서비스 계정에 “서비스로 로그온”이 있다
- [ ] `icacls`에 `(OI)(CI)`와 `/T`를 썼고 `data`와 `backup`은 공유하지 않았다
- [ ] `files` 공유는 읽기 전용이고, 담당자 PC의 나스카로만 연다
- [ ] 방화벽은 Domain,Private 인바운드만 있고 아웃바운드는 없다
- [ ] 서비스가 죽으면 `startup-error.log`, 이벤트 뷰어, `Get-NetTCPConnection`을 본다
- [ ] 없는 PFX나 틀린 암호는 종료 코드 2와 한글 한 줄이고 스택이 아니다

화면 테마(라이트, 다크, 시스템)는 그 브라우저에만 저장됩니다. 설치 폴더나 서버 설정이 바뀌지 않습니다. PDF·이미지·텍스트 미리보기는 서버가 파일을 실행하지 않고, 나스카 암호문은 풀지 않습니다.

Windows 서비스, 다른 PC 브라우저, 내부 CA, 나스카로 파일을 여는 시험은 이 패키지를 만든 곳에서 하지 않았습니다.
