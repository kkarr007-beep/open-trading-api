using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AuditRecord.Pages.Account;

public class DeniedModel : PageModel
{
    public IActionResult OnGet()
    {
        return Page();
    }
}
