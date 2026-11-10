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
    public string? Text { get; set; }
    public string? Sanction { get; set; }

    public IActionResult OnGet(string? text, string? sanction)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Allowed = _audit.CanReadDiscipline(actor);
        Admin = actor.IsAdmin;
        Text = text;
        Sanction = sanction;
        if (Allowed) Rows = _audit.ListDiscipline(text, sanction);
        return Page();
    }

    public IActionResult OnPostExport(string? requestId)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        if (!actor.IsAdmin) return Forbid();
        var csv = _audit.ExportDisciplineCsv(actor, requestId);
        return File(System.Text.Encoding.UTF8.GetBytes(csv), "text/csv", "discipline.csv");
    }
}
