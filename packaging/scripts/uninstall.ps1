# 서비스 등록만 제거합니다. data, files, backup 은 지우지 않습니다.
# 같은 동작은 service.ps1 -Action uninstall 입니다. Windows에서 실행한 결과는 아닙니다.
$ErrorActionPreference = 'Continue'
$ServiceName = 'AuditRecord'
Stop-Service -Name $ServiceName -Force -ErrorAction SilentlyContinue
& sc.exe delete $ServiceName | Out-Null
Write-Host '서비스 등록만 제거했습니다. 데이터 폴더는 그대로 둡니다.'
