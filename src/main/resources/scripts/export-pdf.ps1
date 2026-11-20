# 문서를 이 컴퓨터에 설치된 사무용 프로그램으로 읽기 전용으로 열어 PDF로 저장한다('원본 보기'용).
# 원본 파일은 고치지 않는다. 문서보안(DRM)은 그 프로그램과 보안 에이전트가 푼다.
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Ext
)
$ErrorActionPreference = 'Stop'

function Release($o) {
    if ($null -ne $o) { try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($o) } catch { } }
}

function Pdf-Word {
    $app = New-Object -ComObject Word.Application
    try {
        $app.Visible = $false
        $app.DisplayAlerts = 0
        $doc = $app.Documents.Open($Path, $false, $true, $false)
        # ExportAsFixedFormat(OutputFileName, ExportFormat=17(PDF), OpenAfterExport, OptimizeFor=0(인쇄 품질))
        try { $doc.ExportAsFixedFormat($Out, 17, $false, 0) }
        finally { $doc.Close($false); Release $doc }
    } finally { $app.Quit(); Release $app }
}

function Pdf-Excel {
    $app = New-Object -ComObject Excel.Application
    try {
        $app.Visible = $false
        $app.DisplayAlerts = $false
        $app.AskToUpdateLinks = $false
        try { $app.AutomationSecurity = 3 } catch { }
        $book = $app.Workbooks.Open($Path, 0, $true)
        # ExportAsFixedFormat(Type=0(PDF), Filename, Quality=0(표준))
        try { $book.ExportAsFixedFormat(0, $Out, 0) }
        finally { $book.Close($false); Release $book }
    } finally { $app.Quit(); Release $app }
}

function Pdf-PowerPoint {
    $app = New-Object -ComObject PowerPoint.Application
    try {
        $deck = $app.Presentations.Open($Path, -1, 0, 0)
        # SaveAs(FileName, ppSaveAsPDF=32)
        try { $deck.SaveAs($Out, 32) }
        finally { $deck.Close(); Release $deck }
    } finally { $app.Quit(); Release $app }
}

function Pdf-Hwp {
    $hwp = New-Object -ComObject HWPFrame.HwpObject
    try {
        try { [void]$hwp.RegisterModule('FilePathCheckDLL', 'FilePathCheckerModule') } catch { }
        try { $hwp.XHwpWindows.Item(0).Visible = $false } catch { }
        $opened = $hwp.Open($Path, '', 'forceopen:true;readonly:true;versionwarning:false')
        if (-not $opened) { throw '한글이 문서를 열지 못했습니다.' }
        try {
            $ok = $hwp.SaveAs($Out, 'PDF', '')
            if (-not $ok) { throw '한글이 PDF로 저장하지 못했습니다.' }
        } finally { try { [void]$hwp.Clear(1) } catch { } }
    } finally { try { $hwp.Quit() } catch { }; Release $hwp }
}

try {
    switch ($Ext.ToLower()) {
        { $_ -in '.doc', '.docx', '.rtf', '.odt' } { Pdf-Word; break }
        { $_ -in '.xls', '.xlsx', '.xlsm', '.ods' } { Pdf-Excel; break }
        { $_ -in '.ppt', '.pptx', '.odp' }         { Pdf-PowerPoint; break }
        { $_ -in '.hwp', '.hwpx' }                 { Pdf-Hwp; break }
        default { throw "지원하지 않는 형식입니다: $Ext" }
    }
    if (-not (Test-Path $Out)) { throw 'PDF가 만들어지지 않았습니다.' }
    exit 0
} catch {
    $message = $_.Exception.Message
    if ($message -match '80040154|REGDB_E_CLASSNOTREG|class not registered|클래스가 등록') {
        $message = '이 형식을 여는 프로그램이 설치되어 있지 않습니다.'
    }
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    Write-Output ("PDF로 바꾸지 못했습니다: " + ($message -replace "[`r`n]+", ' '))
    exit 1
}
