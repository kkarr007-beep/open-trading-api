using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Todo;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;
    public TodoPage Data { get; set; } = new();
    public string? Flash { get; set; }

    public IActionResult OnGet()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Data = _audit.Todos(actor);
        Flash = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
        return Page();
    }
}
