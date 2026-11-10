# 감사기록 Windows 서비스. sc.exe 는 계정 암호를 물어보지 않습니다.
# 이 스크립트는 New-Service -Credential 로 자격 증명을 넘기고, 암호를 출력하지 않습니다.
# Linux에서 파일을 만들었고 Windows에서 실행하지 않았습니다.
#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('install', 'start', 'stop', 'uninstall')]
    [string]$Action,
    [string]$Account,
    [string]$Binary,
    [string]$CertPasswordFile
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'AuditRecord'

function Resolve-BinaryPath {
    if ($Binary) {
        if (-not (Test-Path -LiteralPath $Binary)) { throw "실행 파일이 없습니다: $Binary" }
        return (Resolve-Path -LiteralPath $Binary).Path
    }
    $candidate = Join-Path (Split-Path $PSScriptRoot -Parent) 'app\AuditRecord.App.exe'
    if (-not (Test-Path -LiteralPath $candidate)) { throw "실행 파일이 없습니다: $candidate" }
    return (Resolve-Path -LiteralPath $candidate).Path
}

switch ($Action) {
    'install' {
        if (-not $Account) { throw '-Account 가 필요합니다. 예: DOMAIN\audit-svc. LocalSystem 은 쓰지 않습니다.' }
        if ($Account -match '^(?i)(\.\\)?(LocalSystem|SYSTEM)$' -or $Account -eq 'NT AUTHORITY\SYSTEM') {
            throw 'LocalSystem 은 쓰지 않습니다.'
        }
        $exe = Resolve-BinaryPath
        $secure = Read-Host -AsSecureString -Prompt '서비스 계정 암호 (화면에 표시하지 않습니다)'
        $credential = New-Object System.Management.Automation.PSCredential ($Account, $secure)
        New-Service -Name $ServiceName -BinaryPathName "`"$exe`"" -Credential $credential -StartupType Automatic -DisplayName '감사기록' | Out-Null
        $environment = @(
            'DOTNET_ENVIRONMENT=Production',
            'ASPNETCORE_ENVIRONMENT=Production',
            'ASPNETCORE_DETAILEDERRORS=false',
            'DOTNET_CLI_TELEMETRY_OPTOUT=1'
        )
        if ($CertPasswordFile) {
            if (-not (Test-Path -LiteralPath $CertPasswordFile)) { throw "인증서 암호 파일이 없습니다: $CertPasswordFile" }
            $full = (Resolve-Path -LiteralPath $CertPasswordFile).Path
            $environment += "AUDIT_CERT_PASSWORD_FILE=$full"
        }
        New-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Services\$ServiceName" -Name Environment -PropertyType MultiString -Value $environment -Force | Out-Null
        Write-Host '서비스를 등록했습니다. 암호 값은 출력하지 않았습니다. 시작은 -Action start 입니다.'
        if (-not $CertPasswordFile) {
            Write-Host 'PFX를 쓰면 암호를 설정 파일에 적지 마세요. 서비스 계정만 읽을 수 있는 파일을 만들고 -CertPasswordFile 로 다시 등록하세요.'
        }
    }
    'start' {
        Start-Service -Name $ServiceName
        Write-Host '서비스를 시작했습니다.'
    }
    'stop' {
        Stop-Service -Name $ServiceName -Force
        Write-Host '서비스를 멈췄습니다.'
    }
    'uninstall' {
        $existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
        if ($existing) {
            Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
            & sc.exe delete $ServiceName | Out-Null
            $deadline = (Get-Date).AddSeconds(20)
            while ((Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
                Start-Sleep -Milliseconds 300
            }
        }
        Write-Host '서비스 등록만 제거했습니다. data, files, backup 은 그대로 둡니다.'
    }
}
