# 감사기록 간편 설치
#
# 하는 일
#   1. data, files, backup, app-keys 폴더를 만듭니다.
#   2. 서비스 계정이 쓸 수 있도록 그 폴더의 권한을 줍니다.
#   3. Windows 서비스로 등록합니다. 서버가 켜지면 자동으로 실행됩니다.
#   4. 바탕화면에 바로 가기를 만듭니다.
#   5. 브라우저로 설치 화면을 엽니다.
#
# 쓰는 법 (scripts\간편설치.cmd 를 두 번 누르면 이 파일이 실행됩니다)
#   간편설치.cmd                                  이 서버에서만 씁니다(127.0.0.1)
#   간편설치.cmd -Listen 10.1.2.10                그 주소로 다른 PC도 들어옵니다(HTTP)
#   간편설치.cmd -Listen 10.1.2.10 -Pfx C:\감사기록\app-keys\audit.pfx   HTTPS로 엽니다
#   간편설치.cmd -Account DOMAIN\audit-svc        전용 계정으로 서비스를 돌립니다
#
# 무슨 일이 있었는지는 scripts\install-log.txt 에 남습니다.
# 이 파일은 Linux에서 만들었고 Windows에서 실행해 보지 않았습니다.
#Requires -Version 5.1
[CmdletBinding()]
param(
    [string]$Listen = '127.0.0.1',
    [int]$Port = 0,
    [string]$Pfx = '',
    [string]$Account = 'NT AUTHORITY\LocalService',
    [string]$FilesShare = '',
    [switch]$NoFirewall,
    [switch]$NoShortcut,
    [switch]$NoBrowser,
    # 권한을 올려 다시 실행한 것임을 알리는 표시입니다. 사람이 직접 주지 않습니다.
    [switch]$Relaunched,
    # 권한 확인을 건너뜁니다. 이미 관리자인데 확인이 막혀 진행이 안 될 때만 씁니다.
    [switch]$SkipAdminCheck
)

$script:AdminCheckNote = ''

$ServiceName = 'AuditRecord'
$DisplayName = '감사기록'
$Root = Split-Path $PSScriptRoot -Parent
$Exe = Join-Path $Root 'app\AuditRecord.App.exe'
$LogPath = Join-Path $PSScriptRoot 'install-log.txt'

function Step([string]$text) { Write-Host ''; Write-Host ('  == ' + $text) -ForegroundColor Cyan }
function Good([string]$text) { Write-Host ('   + ' + $text) -ForegroundColor Green }
function Note([string]$text) { Write-Host ('   . ' + $text) -ForegroundColor Gray }
function Warn([string]$text) { Write-Host ('   ! ' + $text) -ForegroundColor Yellow }
function Oops([string]$text) { Write-Host ('   X ' + $text) -ForegroundColor Red }

function Hold {
    # 권한을 올려 새로 뜬 창은 끝나면 사라집니다. 사람이 읽을 때까지 기다립니다.
    if ($Relaunched) {
        Write-Host ''
        Write-Host '  이 창을 닫으려면 Enter 를 누르세요.' -ForegroundColor White
        try { [void](Read-Host) } catch { Start-Sleep -Seconds 20 }
    }
}

# 관리자(권한을 올린 상태)인지 봅니다.
# 회사 PC는 PowerShell 을 제한 언어 모드로 잠가 두는 일이 있습니다. 그러면 .NET 형식을
# 직접 부를 수 없어 아래 첫 번째 방법이 막힙니다. 그래서 whoami 로도 확인합니다.
# S-1-16-12288 은 높은 무결성 수준, 곧 권한을 올린 상태를 뜻합니다. 언어와 무관합니다.
function IsAdmin {
    $dotnetSaid = $null
    try {
        $id = [Security.Principal.WindowsIdentity]::GetCurrent()
        $dotnetSaid = (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
            [Security.Principal.WindowsBuiltInRole]::Administrator)
        if ($dotnetSaid) { return $true }
    }
    catch {
        $script:AdminCheckNote = '.NET 확인이 막혔습니다: ' + $_.Exception.Message
    }

    try {
        $groups = & whoami.exe /groups 2>$null | Out-String
        if ($groups -match 'S-1-16-12288') {
            if ($null -ne $dotnetSaid) { $script:AdminCheckNote = '.NET 확인은 아니라고 했지만 whoami 는 맞다고 합니다.' }
            return $true
        }
        if ($groups -match 'S-1-16-16384') { return $true }   # 시스템 무결성
        if ($groups) { $script:AdminCheckNote = 'whoami 로도 권한을 올린 흔적을 찾지 못했습니다.' }
    }
    catch {
        $script:AdminCheckNote = 'whoami 확인도 막혔습니다: ' + $_.Exception.Message
    }

    if ($null -ne $dotnetSaid) { return [bool]$dotnetSaid }
    return $false
}

try { Start-Transcript -LiteralPath $LogPath -Force -ErrorAction SilentlyContinue | Out-Null } catch { }

Write-Host ''
Write-Host '  감사기록 간편 설치' -ForegroundColor White
Write-Host '  ------------------------------------------------'
Write-Host ("   . 설치 폴더: " + $Root) -ForegroundColor Gray
Write-Host ("   . 기록: " + $LogPath) -ForegroundColor Gray
try { Write-Host ("   . PowerShell " + $PSVersionTable.PSVersion + ", 언어 모드 " + $ExecutionContext.SessionState.LanguageMode) -ForegroundColor Gray } catch { }

# ---------------------------------------------------------------- 권한

if ($SkipAdminCheck) { Warn '권한 확인을 건너뜁니다. 관리자 창이 아니면 뒤에서 실패합니다.' }
elseif (-not (IsAdmin)) {
    if ($Relaunched) {
        # 한 번 올렸는데도 관리자가 아닙니다. 다시 올리면 창만 계속 뜨므로 여기서 멈춥니다.
        Write-Host ''
        Oops '관리자 권한을 얻지 못했습니다.'
        if ($script:AdminCheckNote) { Note $script:AdminCheckNote }
        Note '이 계정이 이 PC의 관리자인지 확인하세요.'
        Note '맞는데도 이 글이 나오면 -SkipAdminCheck 를 붙여 다시 실행하세요.'
        Write-Host ("     & '" + $PSCommandPath + "' -SkipAdminCheck") -ForegroundColor White
        Note '또는 시작 단추를 오른쪽 눌러 Windows PowerShell(관리자)을 열고 아래를 실행하세요.'
        Write-Host ''
        Write-Host ("     & '" + $PSCommandPath + "'") -ForegroundColor White
        Write-Host ''
        try { Stop-Transcript -ErrorAction SilentlyContinue | Out-Null } catch { }
        Hold
        exit 5
    }

    Write-Host ''
    Note '관리자 권한이 필요합니다. 창을 하나 더 엽니다. 사용자 계정 컨트롤이 물어보면 예를 누르세요.'

    $argv = @('-NoProfile', '-ExecutionPolicy', 'RemoteSigned', '-File', $PSCommandPath, '-Relaunched')
    if ($Listen -ne '127.0.0.1') { $argv += @('-Listen', $Listen) }
    if ($Port -gt 0)             { $argv += @('-Port', $Port) }
    if ($Pfx)                    { $argv += @('-Pfx', $Pfx) }
    if ($Account -ne 'NT AUTHORITY\LocalService') { $argv += @('-Account', $Account) }
    if ($FilesShare)             { $argv += @('-FilesShare', $FilesShare) }
    if ($NoFirewall)             { $argv += '-NoFirewall' }
    if ($NoShortcut)             { $argv += '-NoShortcut' }
    if ($NoBrowser)              { $argv += '-NoBrowser' }

    try {
        $host_exe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (-not (Test-Path -LiteralPath $host_exe)) { $host_exe = 'powershell.exe' }
        Start-Process -FilePath $host_exe -ArgumentList $argv -Verb RunAs -ErrorAction Stop
        Note '새 창에서 이어서 진행합니다. 이 창은 닫아도 됩니다.'
        try { Stop-Transcript -ErrorAction SilentlyContinue | Out-Null } catch { }
        exit 0
    }
    catch {
        Write-Host ''
        Oops '권한 올리기를 취소했거나 막혔습니다.'
        Note '시작 단추를 오른쪽 눌러 Windows PowerShell(관리자)을 열고 아래를 실행하세요.'
        Write-Host ''
        Write-Host ("     & '" + $PSCommandPath + "'") -ForegroundColor White
        Write-Host ''
        try { Stop-Transcript -ErrorAction SilentlyContinue | Out-Null } catch { }
        exit 5
    }
}

if (-not $SkipAdminCheck) { Good '관리자 권한으로 실행 중입니다' }
if ($script:AdminCheckNote) { Note $script:AdminCheckNote }
$ErrorActionPreference = 'Stop'

try {
    # ---------------------------------------------------------------- 0. 확인

    if (-not (Test-Path -LiteralPath $Exe)) {
        throw ("app\AuditRecord.App.exe 가 없습니다. 찾은 곳: " + $Exe + "`n" +
               "압축을 통째로 풀어서 app, data, scripts 가 나란히 있게 하세요.")
    }
    Good 'app\AuditRecord.App.exe 를 찾았습니다'

    # ---------------------------------------------------------------- 1. 폴더

    Step '1. 폴더 만들기'
    foreach ($name in @('data', 'files', 'backup', 'app-keys')) {
        $path = Join-Path $Root $name
        if (Test-Path -LiteralPath $path) { Note "$name 는 이미 있습니다" }
        else { New-Item -ItemType Directory -Path $path | Out-Null; Good "$name 를 만들었습니다" }
    }

    # ---------------------------------------------------------------- 2. 주소

    Step '2. 접속 주소 정하기'
    $useHttps = -not [string]::IsNullOrWhiteSpace($Pfx)
    if ($useHttps -and -not (Test-Path -LiteralPath $Pfx)) { throw "PFX 파일이 없습니다: $Pfx" }
    if ($Port -le 0) { $Port = if ($useHttps) { 8443 } else { 47321 } }
    $loopbackOnly = ($Listen -eq '127.0.0.1')
    $scheme = if ($useHttps) { 'https' } else { 'http' }
    $localUrl = "${scheme}://127.0.0.1:$Port"
    $lanUrl = if ($loopbackOnly) { $localUrl } else { "${scheme}://${Listen}:$Port" }

    # 인증서가 없으면 프로그램은 Audit:Urls 만 보고 듣습니다. BindAddress 는 HTTPS 일 때만 씁니다.
    # 그래서 HTTP 로 내부망을 열 때는 Urls 에 서버 주소를 적습니다.
    # 127.0.0.1 도 함께 적습니다. 설치 화면은 서버 자신에서만 열리기 때문입니다.
    $listenUrl = if ($useHttps -or $loopbackOnly) { $localUrl } else { "$localUrl;$lanUrl" }

    if ($loopbackOnly -and -not $useHttps) {
        Note '이 서버에서만 엽니다. 다른 PC는 아직 들어오지 못합니다.'
        Note '나중에 열려면 -Listen 에 서버 내부 IP를 주고 다시 실행하세요.'
    }
    else {
        $settings = [ordered]@{
            Logging = [ordered]@{ LogLevel = [ordered]@{
                Default = 'Information'
                'Microsoft.AspNetCore' = 'Warning'
                # 우리가 일부러 단 Cache-Control 을 두고 경고가 매 요청마다 올라옵니다. 오류만 남깁니다.
                'Microsoft.AspNetCore.Antiforgery' = 'Error'
            } }
            Audit   = [ordered]@{
                Mode              = 'Production'
                Root              = '..'
                Urls              = $listenUrl
                AllowInsecureHttp = (-not $useHttps)
                FilesShareUnc     = $FilesShare
                BackupAllowRoot   = ''
                WorkerSeconds     = 60
                CertificatePath   = $(if ($useHttps) { (Resolve-Path -LiteralPath $Pfx).Path } else { '' })
                BindAddress       = $Listen
                Port              = $Port
            }
        }
        $target = Join-Path $Root 'app\appsettings.Production.json'
        if (Test-Path -LiteralPath $target) {
            Copy-Item -LiteralPath $target -Destination "$target.bak" -Force
            Note "기존 설정을 $target.bak 으로 옮겼습니다"
        }
        $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $target -Encoding UTF8
        Good "설정을 적었습니다: $target"
        Good "접속 주소: $lanUrl"
        if (-not $useHttps) { Warn '인증서 없이 HTTP로 엽니다. 내부망 안에서만 쓰고, 가능하면 -Pfx 로 HTTPS를 쓰세요.' }
    }

    # ---------------------------------------------------------------- 3. 권한

    Step '3. 서비스 계정 권한 주기'
    Note "서비스 계정: $Account"
    & icacls.exe "$Root\app" /grant "${Account}:(OI)(CI)(RX)" /T /C /Q | Out-Null
    $aclCode = $LASTEXITCODE
    foreach ($name in @('data', 'files', 'backup', 'app-keys')) {
        & icacls.exe "$Root\$name" /grant "${Account}:(OI)(CI)(M)" /T /C /Q | Out-Null
        if ($LASTEXITCODE -ne 0) { $aclCode = $LASTEXITCODE }
    }
    if ($aclCode -eq 0) { Good 'app 은 읽기·실행, data·files·backup·app-keys 는 수정 권한을 주었습니다' }
    else { Warn "권한 설정이 깔끔하게 끝나지 않았습니다(코드 $aclCode). icacls 로 직접 확인하세요." }

    if ($useHttps) {
        & icacls.exe "$Pfx" /grant "${Account}:(R)" /C /Q | Out-Null
        Note 'PFX 파일에 읽기 권한을 주었습니다'
    }

    # ---------------------------------------------------------------- 4. 서비스

    Step '4. Windows 서비스로 등록하기'
    if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
        Note '이미 등록된 서비스가 있어 멈추고 지웁니다'
        Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
        & sc.exe delete $ServiceName | Out-Null
        $deadline = (Get-Date).AddSeconds(20)
        while ((Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
            Start-Sleep -Milliseconds 300
        }
    }

    if ($Account -match '^(?i)NT AUTHORITY\\') {
        & sc.exe create $ServiceName binPath= "`"$Exe`"" obj= "$Account" start= auto DisplayName= "$DisplayName" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "서비스를 만들지 못했습니다(sc.exe 코드 $LASTEXITCODE)." }
    }
    else {
        $credential = Get-Credential -UserName $Account -Message "$Account 의 암호를 넣으세요. 화면에 보이지 않습니다."
        if (-not $credential) { throw '암호를 넣지 않아 서비스를 등록하지 못했습니다.' }
        New-Service -Name $ServiceName -BinaryPathName "`"$Exe`"" -Credential $credential `
            -StartupType Automatic -DisplayName $DisplayName | Out-Null
    }
    Good '서비스를 등록했습니다. 서버가 켜지면 자동으로 실행됩니다.'

    $environment = @(
        'DOTNET_ENVIRONMENT=Production',
        'ASPNETCORE_ENVIRONMENT=Production',
        'ASPNETCORE_DETAILEDERRORS=false',
        'DOTNET_CLI_TELEMETRY_OPTOUT=1'
    )
    $passwordFile = Join-Path $Root 'app-keys\cert-password.txt'
    if ($useHttps -and (Test-Path -LiteralPath $passwordFile)) {
        $environment += "AUDIT_CERT_PASSWORD_FILE=$passwordFile"
        Note 'PFX 암호 파일을 서비스에 연결했습니다'
    }
    New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName" `
        -Name Environment -PropertyType MultiString -Value $environment -Force | Out-Null

    & sc.exe description $ServiceName "감사파트 기록·징계기록 등재. 내부망 전용." | Out-Null
    & sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null
    Good '서비스가 멈추면 다시 시작하도록 해 두었습니다'

    # ---------------------------------------------------------------- 5. 방화벽

    if (-not $loopbackOnly -and -not $NoFirewall) {
        Step '5. 방화벽 열기'
        try {
            $ruleName = "감사기록 ($Port)"
            Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue |
                Remove-NetFirewallRule -ErrorAction SilentlyContinue
            New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
                -Protocol TCP -LocalPort $Port -Profile Domain,Private | Out-Null
            Good "도메인·개인 네트워크에서 $Port 포트를 열었습니다"
            Note '공용 네트워크는 열지 않았습니다'
        }
        catch {
            Warn "방화벽 규칙을 만들지 못했습니다: $($_.Exception.Message)"
            Note "직접 열려면: netsh advfirewall firewall add rule name=`"감사기록`" dir=in action=allow protocol=TCP localport=$Port"
        }
    }

    # ---------------------------------------------------------------- 6. 시작

    Step '6. 서비스 시작하기'
    Start-Service -Name $ServiceName
    $ok = $false
    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 700
        try {
            if (Test-NetConnection -ComputerName '127.0.0.1' -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue) {
                $ok = $true
                break
            }
        }
        catch {
            # Test-NetConnection 이 없는 환경이면 서비스 상태로 갈음합니다.
            $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
            if ($svc -and $svc.Status -eq 'Running') { Start-Sleep -Seconds 3; $ok = $true; break }
        }
    }
    if ($ok) { Good "서비스가 올라왔습니다. $localUrl 가 응답합니다." }
    else {
        Warn '아직 응답이 없습니다. 아래를 확인하세요.'
        Note "  $Root\data\startup-error.log"
        Note "  Get-Service $ServiceName"
    }

    # ---------------------------------------------------------------- 7. 바탕화면

    if (-not $NoShortcut) {
        Step '7. 바탕화면 아이콘 만들기'
        try {
            $desktop = Join-Path $env:PUBLIC 'Desktop'
            if (-not (Test-Path -LiteralPath $desktop)) { $desktop = Join-Path $env:USERPROFILE 'Desktop' }
            $link = Join-Path $desktop '감사기록.url'
            $body = @('[InternetShortcut]', "URL=$localUrl", "IconFile=$Exe", 'IconIndex=0') -join "`r`n"
            Set-Content -LiteralPath $link -Value $body -Encoding ASCII
            Good "바탕화면에 '감사기록' 아이콘을 만들었습니다"
            Note '그 아이콘은 브라우저로 화면을 엽니다. 프로그램은 서비스가 따로 돌립니다.'
        }
        catch { Warn "아이콘을 만들지 못했습니다: $($_.Exception.Message)" }
    }

    # ---------------------------------------------------------------- 끝

    Write-Host ''
    Write-Host '  ------------------------------------------------'
    Write-Host '  설치를 마쳤습니다.' -ForegroundColor White
    Write-Host ''
    Write-Host "  이 서버 주소   $localUrl"
    if (-not $loopbackOnly) { Write-Host "  내부망 주소    $lanUrl" }
    Write-Host ''
    Write-Host '  다음에 할 일' -ForegroundColor White
    Write-Host '   1. 이 서버 브라우저에서 접속 비밀번호를 정합니다. 설치 화면이 곧 열립니다.'
    Write-Host '   2. 파트원에게 내부망 주소와 그 비밀번호를 알려 줍니다.'
    Write-Host '   3. 관리 화면에서 보존 기준을 확인해야 자동 삭제가 돕니다.'
    Write-Host ''
    Write-Host '  서비스 다루기' -ForegroundColor White
    Write-Host "   멈춤   Stop-Service $ServiceName"
    Write-Host "   시작   Start-Service $ServiceName"
    Write-Host "   상태   Get-Service $ServiceName"
    Write-Host ''

    if (-not $NoBrowser) {
        try { Start-Process "$localUrl/account/setup" | Out-Null } catch { Note "브라우저에서 $localUrl 을 직접 여세요." }
    }

    try { Stop-Transcript -ErrorAction SilentlyContinue | Out-Null } catch { }
    Hold
    exit 0
}
catch {
    Write-Host ''
    Oops '설치를 마치지 못했습니다.'
    Write-Host ''
    Write-Host ('   ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host ''
    Note "자세한 내용은 $LogPath 에 있습니다."
    Note "프로그램이 남긴 한 줄은 $Root\data\startup-error.log 에 있습니다."
    try { Stop-Transcript -ErrorAction SilentlyContinue | Out-Null } catch { }
    Hold
    exit 1
}
