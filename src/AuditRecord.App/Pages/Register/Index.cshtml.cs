using System.Globalization;
using System.Text;
using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Register;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;

    public IReadOnlyList<RegisterRow> Rows { get; set; } = [];
    public IReadOnlyList<int> Years { get; set; } = [];
    public int? Year { get; set; }
    public string? Category { get; set; }
    public string PrintedAt { get; set; } = "";

    public IActionResult OnGet(int? year, string? category)
    {
        if (HttpContext.Items["actor"] is not Actor) return RedirectToPage("/Account/Login");
        Year = year;
        Category = category is "업무진단" or "준법감사" ? category : null;
        Rows = _audit.ListRegister(Year, Category);
        Years = _audit.Years();
        PrintedAt = Kst.Display(DateTimeOffset.UtcNow);
        return Page();
    }

    /// <summary>
    /// 등록대장 CSV. Excel에서 바로 열리도록 UTF-8 BOM을 붙인다.
    /// 본문·파일 내용은 포함하지 않으며, 연한 뒤에도 남는 목록 수준 항목만 담는다.
    /// </summary>
    public IActionResult OnGetCsv(int? year, string? category)
    {
        if (HttpContext.Items["actor"] is not Actor) return RedirectToPage("/Account/Login");
        var rows = _audit.ListRegister(year, category is "업무진단" or "준법감사" ? category : null);
        var sb = new StringBuilder();
        sb.AppendLine("등록번호,연도,구분,제목,대상,담당,등재일,결과확정일,보존연한(년),만료예정일,상태,삭제보류,문서번호,서가,철번호,결과 한 줄,자료 수,삭제된 자료 수");
        foreach (var row in rows)
        {
            sb.AppendLine(string.Join(",",
                Csv(row.RecordNo), row.Year.ToString(CultureInfo.InvariantCulture), Csv(row.Category),
                Csv(row.Title), Csv(row.Target), Csv(row.OwnerName), Csv(row.CreatedDate),
                Csv(row.FirstConfirmedOn ?? ""),
                row.RetentionYears?.ToString(CultureInfo.InvariantCulture) ?? "",
                Csv(row.ExpiryDate ?? ""), Csv(row.DisplayState), row.Hold ? "보류" : "",
                Csv(row.DocumentNo), Csv(row.Shelf), Csv(row.BinderNo), Csv(row.SummaryLine),
                row.AttachmentCount.ToString(CultureInfo.InvariantCulture),
                row.DeletedAttachmentCount.ToString(CultureInfo.InvariantCulture)));
        }
        var bom = Encoding.UTF8.GetPreamble();
        var body = Encoding.UTF8.GetBytes(sb.ToString());
        var bytes = new byte[bom.Length + body.Length];
        bom.CopyTo(bytes, 0);
        body.CopyTo(bytes, bom.Length);
        var stamp = Kst.DateOf(DateTimeOffset.UtcNow).ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        return File(bytes, "text/csv; charset=utf-8", $"등록대장-{stamp}.csv");
    }

    static string Csv(string value)
    {
        // 수식 주입 방지: 셀 머리의 = + - @ 앞에 작은따옴표를 둔다.
        if (value.Length > 0 && value[0] is '=' or '+' or '-' or '@')
            value = "'" + value;
        if (value.Contains('"') || value.Contains(',') || value.Contains('\n') || value.Contains('\r'))
            return "\"" + value.Replace("\"", "\"\"") + "\"";
        return value;
    }
}
