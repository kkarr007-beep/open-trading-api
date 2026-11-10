using AuditRecord.Services;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AuditRecord.Pages;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;

    public IActionResult OnGet()
    {
        if (!_audit.AnyUsers()) return RedirectToPage("/Account/Setup");
        if (User.Identity?.IsAuthenticated != true) return RedirectToPage("/Account/Login");
        return RedirectToPage("/Dashboard/Index");
    }
}
