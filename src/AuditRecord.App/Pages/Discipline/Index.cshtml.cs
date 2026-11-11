using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Discipline;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;
    public bool Allowed { get; set; }
    public bool Admin { get; set; }
    public List<DisciplineRow> Rows { get; set; } = [];
    public IReadOnlyList<int> Years { get; set; } = [];
    public string? Text { get; set; }
    public string? Sanction { get; set; }
    public int? Year { get; set; }

    public IActionResult OnGet(string? text, string? sanction, int? year)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Allowed = _audit.CanReadDiscipline(actor);
        Admin = actor.IsAdmin;
        Text = text;
        Sanction = sanction;
        Year = year;
        if (Allowed)
        {
            Rows = _audit.ListDiscipline(text, sanction, year);
            Years = _audit.DisciplineYears();
        }
        return Page();
    }

    public IActionResult OnPostExport(string? requestId)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        if (!actor.IsAdmin) return Forbid();
        var csv = _audit.ExportDisciplineCsv(actor, requestId);
        var stamp = Kst.DateOf(DateTimeOffset.UtcNow).ToString("yyyyMMdd", System.Globalization.CultureInfo.InvariantCulture);
        return File(System.Text.Encoding.UTF8.GetBytes(csv), "text/csv; charset=utf-8", $"징계기록-{stamp}.csv");
    }
}
