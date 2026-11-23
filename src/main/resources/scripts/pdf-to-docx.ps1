# PDF를 이 컴퓨터의 MS 워드로 열어(워드의 'PDF 열기' 변환) 워드 문서로 저장한다. 표·합친 칸·들여쓰기를 살려 읽기 위한 것.
# 원본 PDF는 고치지 않는다. 워드가 없거나 변환에 실패하면 사유를 한 줄로 적고 끝낸다.
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Out
)
$ErrorActionPreference = 'Stop'

function Release($o) {
    if ($null -ne $o) { try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($o) } catch { } }
}

# 'PDF를 편집 가능한 문서로 바꿉니다' 안내 창이 뜨지 않게(이 사용자 설정만, 워드 2013·2016 이후)
foreach ($v in '15.0', '16.0') {
    try {
        $key = "HKCU:\Software\Microsoft\Office\$v\Word\Options"
        if (-not (Test-Path $key)) { [void](New-Item -Path $key -Force) }
        New-ItemProperty -Path $key -Name 'DisableConvertPdfWarning' -Value 1 -PropertyType DWord -Force | Out-Null
    } catch { }
}

try {
    $app = New-Object -ComObject Word.Application
    try {
        $app.Visible = $false
        $app.DisplayAlerts = 0
        try { $app.Options.ConfirmConversions = $false } catch { }
        # Open(FileName, ConfirmConversions, ReadOnly, AddToRecentFiles)
        $doc = $app.Documents.Open($Path, $false, $true, $false)
        # SaveAs2(FileName, FileFormat=16(wdFormatXMLDocument, docx))
        try { $doc.SaveAs2($Out, 16) }
        finally { $doc.Close($false); Release $doc }
    } finally { $app.Quit(); Release $app }
    if (-not (Test-Path $Out)) { throw '워드 문서가 만들어지지 않았습니다.' }
    exit 0
} catch {
    $message = $_.Exception.Message
    if ($message -match '80040154|REGDB_E_CLASSNOTREG|class not registered|클래스가 등록') { $message = 'MS 워드가 설치되어 있지 않습니다.' }
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    Write-Output ("PDF를 워드로 바꾸지 못했습니다: " + ($message -replace "[`r`n]+", ' '))
    exit 1
}
