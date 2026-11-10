using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Cases;

public class PrintModel : PageModel
{
    readonly AuditService _audit;
    public PrintModel(AuditService audit) => _audit = audit;
    public CaseDetail? Detail { get; set; }
    [BindProperty(SupportsGet = true)] public string Id { get; set; } = "";

    public IActionResult OnGet()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Detail = _audit.GetCase(actor, Id);
        if (Detail is null)
        {
            Response.StatusCode = 404;
            return Page();
        }
        return Page();
    }
}
