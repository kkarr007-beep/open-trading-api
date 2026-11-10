# 감사기록 폴더를 확인합니다. 네트워크에서 받지 않고, 비밀번호를 출력하지 않습니다.
# 최초 관리자는 서버의 127.0.0.1 에서 만든 뒤에 내부망 주소를 엽니다. 자세한 절차는 guide\INSTALL_KO.md 입니다.
$ErrorActionPreference = 'Stop'
$Root = Split-Path $PSScriptRoot -Parent
foreach ($name in @('data', 'files', 'backup', 'app-keys')) {
    $path = Join-Path $Root $name
    if (-not (Test-Path -LiteralPath $path)) { New-Item -ItemType Directory -Path $path | Out-Null }
}
Write-Host '폴더를 확인했습니다. 계정 권한과 인증서는 INSTALL_KO.md 를 따르세요.'
Write-Host '처음에는 scripts\start.cmd 로 127.0.0.1 에서 관리자를 만든 다음 서비스를 등록하세요.'
