# 감사기록 간편 설치
#
# 하는 일
#   1. data, files, backup, app-keys 폴더를 만듭니다.
#   2. 서비스 계정이 쓸 수 있도록 그 폴더의 권한을 줍니다.
#   3. Windows 서비스로 등록합니다. 서버가 켜지면 자동으로 실행됩니다.
#   4. 바탕화면에 바로 가기를 만듭니다.
#   5. 브라우저로 설치 화면을 엽니다.
#
# 쓰는 법
#   간편설치.cmd                                   이 서버에서만 씁니다(127.0.0.1)
#   간편설치.cmd -Listen 10.1.2.10                 그 주소로 다른 PC도 들어옵니다(HTTP)
#   간편설치.cmd -Listen 10.1.2.10 -Pfx C:\감사기록\app-keys\audit.pfx   HTTPS로 엽니다
#   간편설치.cmd -Account DOMAIN\audit-svc         전용 계정으로 서비스를 돌립니다
#
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
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'AuditRecord'
$DisplayName = '감사기록'

function Step([string]$text) { Write-Host ''; Write-Host ('  == ' + $text) -ForegroundColor Cyan }
function Good([string]$text) { Write-Host ('   + ' + $text) -ForegroundColor Green }
function Note([string]$text) { Write-Host ('   . ' + $text) -ForegroundColor Gray }
function Warn([string]$text) { Write-Host ('   ! ' + $text) -ForegroundColor Yellow }

$Root = Split-Path $PSScriptRoot -Parent
$Exe = Join-Path $Root 'app\AuditRecord.App.exe'

Write-Host ''
Write-Host '  감사기록 간편 설치' -ForegroundColor White
Write-Host '  ------------------------------------------------'
Note "설치 폴더: $Root"

if (-not (Test-Path -LiteralPath $Exe)) {
    throw "app\AuditRecord.App.exe 가 없습니다. 압축을 통째로 풀어서 app, data, scripts 가 나란히 있게 하세요."
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw '관리자 권한이 필요합니다. 간편설치.cmd 를 오른쪽 눌러 관리자 권한으로 실행하세요.'
}

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

if ($loopbackOnly -and -not $useHttps) {
    Note '이 서버에서만 엽니다. 다른 PC는 아직 들어오지 못합니다.'
    Note '나중에 열려면 -Listen 에 서버 내부 IP를 주고 다시 실행하세요.'
}
else {
    $settings = [ordered]@{
        Logging = [ordered]@{ LogLevel = [ordered]@{ Default = 'Information'; 'Microsoft.AspNetCore' = 'Warning' } }
        Audit   = [ordered]@{
            Mode             = 'Production'
            Root             = '..'
            Urls             = $localUrl
            AllowInsecureHttp = (-not $useHttps)
            FilesShareUnc    = $FilesShare
            BackupAllowRoot  = ''
            WorkerSeconds    = 60
            CertificatePath  = $(if ($useHttps) { (Resolve-Path -LiteralPath $Pfx).Path } else { '' })
            BindAddress      = $Listen
            Port             = $Port
        }
    }
    $target = Join-Path $Root 'app\appsettings.Production.json'
    if (Test-Path -LiteralPath $target) {
        $backup = "$target.bak"
        Copy-Item -LiteralPath $target -Destination $backup -Force
        Note "기존 설정을 $backup 으로 옮겼습니다"
    }
    $settings | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $target -Encoding UTF8
    Good "설정을 적었습니다: $target"
    Good "접속 주소: $lanUrl"
    if (-not $useHttps) {
        Warn '인증서 없이 HTTP로 엽니다. 내부망 안에서만 쓰고, 가능하면 -Pfx 로 HTTPS를 쓰세요.'
    }
}

# ---------------------------------------------------------------- 3. 권한

Step '3. 서비스 계정 권한 주기'
Note "서비스 계정: $Account"
& icacls.exe "$Root\app" /grant "${Account}:(OI)(CI)(RX)" /T /C /Q | Out-Null
foreach ($name in @('data', 'files', 'backup', 'app-keys')) {
    & icacls.exe "$Root\$name" /grant "${Account}:(OI)(CI)(M)" /T /C /Q | Out-Null
}
if ($LASTEXITCODE -eq 0) { Good 'app 은 읽기·실행, data·files·backup·app-keys 는 수정 권한을 주었습니다' }
else { Warn "권한 설정이 깔끔하게 끝나지 않았습니다(코드 $LASTEXITCODE). icacls 로 직접 확인하세요." }

if ($useHttps) {
    & icacls.exe "$Pfx" /grant "${Account}:(R)" /C /Q | Out-Null
    Note 'PFX 파일에 읽기 권한을 주었습니다'
}

# ---------------------------------------------------------------- 4. 서비스

Step '4. Windows 서비스로 등록하기'
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    Note '이미 등록된 서비스가 있어 멈추고 지웁니다'
    Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
    & sc.exe delete $ServiceName | Out-Null
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 300
    }
}

$builtIn = $Account -match '^(?i)NT AUTHORITY\\'
if ($builtIn) {
    & sc.exe create $ServiceName binPath= "`"$Exe`"" obj= "$Account" start= auto DisplayName= "$DisplayName" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "서비스를 만들지 못했습니다(코드 $LASTEXITCODE)." }
}
else {
    $secure = Read-Host -AsSecureString -Prompt "   $Account 의 암호 (화면에 보이지 않습니다)"
    $credential = New-Object System.Management.Automation.PSCredential ($Account, $secure)
    New-Service -Name $ServiceName -BinaryPathName "`"$Exe`"" -Credential $credential -StartupType Automatic -DisplayName $DisplayName | Out-Null
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
New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName" -Name Environment -PropertyType MultiString -Value $environment -Force | Out-Null

& sc.exe description $ServiceName "감사파트 기록·징계기록 등재. 내부망 전용." | Out-Null
& sc.exe failure $ServiceName reset= 86400 actions= restart/5000/restart/10000/restart/30000 | Out-Null
Good '서비스가 멈추면 다시 시작하도록 해 두었습니다'

# ---------------------------------------------------------------- 5. 방화벽

if (-not $loopbackOnly -and -not $NoFirewall) {
    Step '5. 방화벽 열기'
    $ruleName = "감사기록 ($Port)"
    Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue | Remove-NetFirewallRule -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $ruleName -Direction Inbound -Action Allow `
        -Protocol TCP -LocalPort $Port -Profile Domain,Private | Out-Null
    Good "도메인·개인 네트워크에서 $Port 포트를 열었습니다"
    Note '공용 네트워크는 열지 않았습니다'
}

# ---------------------------------------------------------------- 6. 시작

Step '6. 서비스 시작하기'
Start-Service -Name $ServiceName
$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 700
    try {
        $probe = Test-NetConnection -ComputerName '127.0.0.1' -Port $Port -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($probe) { $ok = $true; break }
    }
    catch { }
}
if ($ok) { Good "서비스가 올라왔습니다. $localUrl 가 응답합니다." }
else {
    Warn '아직 응답이 없습니다. 아래를 확인하세요.'
    Note "  data\startup-error.log"
    Note "  Get-Service $ServiceName"
    Note "  Get-EventLog -LogName Application -Source AuditRecord -Newest 20"
}

# ---------------------------------------------------------------- 7. 바탕화면

if (-not $NoShortcut) {
    Step '7. 바탕화면 아이콘 만들기'
    $desktop = Join-Path $env:PUBLIC 'Desktop'
    if (-not (Test-Path -LiteralPath $desktop)) { $desktop = [Environment]::GetFolderPath('Desktop') }
    $link = Join-Path $desktop '감사기록.url'
    $body = @(
        '[InternetShortcut]',
        "URL=$localUrl",
        "IconFile=$Exe",
        'IconIndex=0'
    ) -join "`r`n"
    Set-Content -LiteralPath $link -Value $body -Encoding ASCII
    Good "바탕화면에 '감사기록' 아이콘을 만들었습니다"
    Note '그 아이콘은 브라우저로 화면을 엽니다. 프로그램은 서비스가 따로 돌립니다.'
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
    Start-Process "$localUrl/account/setup" | Out-Null
}
