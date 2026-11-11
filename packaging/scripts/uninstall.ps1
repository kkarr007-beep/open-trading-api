# 서비스 등록, 방화벽 규칙, 바탕화면 아이콘만 제거합니다.
# data, files, backup, app-keys 는 지우지 않습니다. 기록은 그대로 남습니다.
# 같은 동작의 일부는 service.ps1 -Action uninstall 입니다. Windows에서 실행한 결과는 아닙니다.
$ErrorActionPreference = 'Continue'
$ServiceName = 'AuditRecord'

Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
& sc.exe delete $ServiceName | Out-Null
Write-Host '서비스 등록을 제거했습니다.'

Get-NetFirewallRule -DisplayName '감사기록 (*' -ErrorAction SilentlyContinue |
    Remove-NetFirewallRule -ErrorAction SilentlyContinue
Write-Host '방화벽 규칙을 제거했습니다.'

foreach ($dir in @((Join-Path $env:PUBLIC 'Desktop'), [Environment]::GetFolderPath('Desktop'))) {
    $link = Join-Path $dir '감사기록.url'
    if (Test-Path -LiteralPath $link) {
        Remove-Item -LiteralPath $link -Force -ErrorAction SilentlyContinue
        Write-Host "바탕화면 아이콘을 지웠습니다: $link"
    }
}

Write-Host '데이터 폴더(data, files, backup, app-keys)는 그대로 둡니다.'
