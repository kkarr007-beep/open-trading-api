using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Files;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;
    public List<FileHit> Files { get; set; } = [];
    public string? Name { get; set; }
    public string? Kind { get; set; }
    public string? Person { get; set; }
    public string? CaseTitle { get; set; }

    public IActionResult OnGet(string? name, string? kind, string? person, string? caseTitle)
    {
        if (HttpContext.Items["actor"] is not Actor) return RedirectToPage("/Account/Login");
        Name = name;
        Kind = kind;
        Person = person;
        CaseTitle = caseTitle;
        Files = _audit.SearchFiles(name, kind, person, caseTitle);
        return Page();
    }
}
