using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Cases;

public class NewModel : PageModel
{
    readonly AuditService _audit;
    public NewModel(AuditService audit) => _audit = audit;
    public IReadOnlyList<UserAccount> Users { get; set; } = [];
    public string? Error { get; set; }

    public IActionResult OnGet()
    {
        if (HttpContext.Items["actor"] is not Actor) return RedirectToPage("/Account/Login");
        Users = _audit.ListUsers().Where(u => u.Enabled).ToList();
        return Page();
    }

    public IActionResult OnPost(int year, string? category, string? title, string? target, string? ownerId, string? shelf, string? binder, string? documentNo, string? summary, string? body, int? retentionYears, string? requestId)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Users = _audit.ListUsers().Where(u => u.Enabled).ToList();
        var result = _audit.RegisterCase(actor, year, category, title, target, ownerId, shelf, binder, documentNo, summary, body, retentionYears, requestId);
        if (!result.Ok || result.CaseId is null)
        {
            Error = result.Message;
            return Page();
        }
        HttpContext.Session.SetString("flash", "초안을 저장했습니다. 초안 저장은 결과 확정이 아닙니다.");
        return Redirect($"/cases/{result.CaseId}");
    }
}
