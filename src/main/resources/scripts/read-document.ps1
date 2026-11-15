# 문서를 설치된 사무용 프로그램으로 읽기 전용으로 열어 글자만 꺼낸다.
# 문서보안(DRM) 암호는 그 프로그램과 보안 에이전트가 푼다. 이 스크립트는 암호를 다루지 않는다.
# 포털에 로그인되어 있지 않거나 열람 권한이 없으면 실패하고, 그 사유를 한 줄로 적는다.
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][string]$Ext
)
$ErrorActionPreference = 'Stop'
$limit = 400000

function Release($o) {
    if ($null -ne $o) { try { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($o) } catch { } }
}

function Read-Word {
    $app = New-Object -ComObject Word.Application
    try {
        $app.Visible = $false
        $app.DisplayAlerts = 0
        # Open(FileName, ConfirmConversions, ReadOnly, AddToRecentFiles)
        $doc = $app.Documents.Open($Path, $false, $true, $false)
        try { return [string]$doc.Content.Text }
        finally { $doc.Close($false); Release $doc }
    } finally { $app.Quit(); Release $app }
}

function Read-Excel {
    $app = New-Object -ComObject Excel.Application
    try {
        $app.Visible = $false
        $app.DisplayAlerts = $false
        $app.AskToUpdateLinks = $false
        try { $app.AutomationSecurity = 3 } catch { }   # 매크로 실행 안 함
        # Open(Filename, UpdateLinks, ReadOnly)
        $book = $app.Workbooks.Open($Path, 0, $true)
        try {
            $sb = New-Object System.Text.StringBuilder
            foreach ($sheet in $book.Worksheets) {
                $used = $sheet.UsedRange
                $rows = [Math]::Min($used.Rows.Count, 5000)
                $cols = [Math]::Min($used.Columns.Count, 60)
                for ($r = 1; $r -le $rows; $r++) {
                    $line = New-Object System.Collections.Generic.List[string]
                    for ($c = 1; $c -le $cols; $c++) {
                        $line.Add(([string]$used.Cells.Item($r, $c).Text) -replace "[`t`r`n]", ' ')
                    }
                    $text = ($line -join "`t").TrimEnd()
                    if ($text.Length -gt 0) { [void]$sb.AppendLine($text) }
                    if ($sb.Length -gt $limit) { break }
                }
                [void]$sb.AppendLine()
                Release $used
                if ($sb.Length -gt $limit) { break }
            }
            return $sb.ToString()
        } finally { $book.Close($false); Release $book }
    } finally { $app.Quit(); Release $app }
}

function Read-PowerPoint {
    $app = New-Object -ComObject PowerPoint.Application
    try {
        # Open(FileName, ReadOnly, Untitled, WithWindow)
        $deck = $app.Presentations.Open($Path, -1, 0, 0)
        try {
            $sb = New-Object System.Text.StringBuilder
            foreach ($slide in $deck.Slides) {
                foreach ($shape in $slide.Shapes) {
                    if ($shape.HasTextFrame -and $shape.TextFrame.HasText) {
                        [void]$sb.AppendLine(([string]$shape.TextFrame.TextRange.Text) -replace "`r", "`n")
                    }
                    if ($shape.HasTable) {
                        foreach ($row in $shape.Table.Rows) {
                            $cells = foreach ($cell in $row.Cells) { ([string]$cell.Shape.TextFrame.TextRange.Text) -replace "[`t`r`n]", ' ' }
                            [void]$sb.AppendLine(($cells -join "`t"))
                        }
                    }
                }
                [void]$sb.AppendLine()
            }
            return $sb.ToString()
        } finally { $deck.Close(); Release $deck }
    } finally { $app.Quit(); Release $app }
}

function Read-Hwp {
    $hwp = New-Object -ComObject HWPFrame.HwpObject
    try {
        # 보안 승인 모듈이 등록되어 있으면 파일 접근 확인 창이 뜨지 않는다. 없으면 한글이 한 번 묻는다.
        try { [void]$hwp.RegisterModule('FilePathCheckDLL', 'FilePathCheckerModule') } catch { }
        try { $hwp.XHwpWindows.Item(0).Visible = $false } catch { }
        $opened = $hwp.Open($Path, '', 'forceopen:true;readonly:true;versionwarning:false')
        if (-not $opened) { throw '한글이 문서를 열지 못했습니다.' }
        try { return [string]$hwp.GetTextFile('UNICODE', '') }
        finally { try { [void]$hwp.Clear(1) } catch { } }
    } finally { try { $hwp.Quit() } catch { }; Release $hwp }
}

try {
    switch ($Ext.ToLower()) {
        { $_ -in '.doc', '.docx', '.rtf' } { $text = Read-Word; break }
        { $_ -in '.xls', '.xlsx' }         { $text = Read-Excel; break }
        { $_ -in '.ppt', '.pptx' }         { $text = Read-PowerPoint; break }
        { $_ -in '.hwp', '.hwpx' }         { $text = Read-Hwp; break }
        default { throw "지원하지 않는 형식입니다: $Ext" }
    }
    if ($null -eq $text) { $text = '' }
    if ($text.Length -gt $limit) { $text = $text.Substring(0, $limit) }
    [IO.File]::WriteAllText($Out, $text, (New-Object System.Text.UTF8Encoding($false)))
    exit 0
} catch {
    $message = $_.Exception.Message
    if ($message -match '80040154|REGDB_E_CLASSNOTREG|class not registered|클래스가 등록') {
        $message = '이 형식을 여는 프로그램이 설치되어 있지 않습니다.'
    }
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    Write-Output ("열지 못했습니다: " + ($message -replace "[`r`n]+", ' '))
    exit 1
} finally {
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
