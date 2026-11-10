using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Cases;

public class NewModel : PageModel
{
    readonly AuditService _audit;
    public NewModel(AuditService audit) => _audit = audit;
    public IReadOnlyList<UserAccount> Users { get; set; } = [];
    public string? Error { get; set; }
    public string PreviewNo { get; set; } = "";

    public IActionResult OnGet(int? year, string? category)
    {
        if (HttpContext.Items["actor"] is not Actor) return RedirectToPage("/Account/Login");
        Users = _audit.ListUsers().Where(u => u.Enabled).ToList();
        PreviewNo = _audit.PeekDocumentNo(year is null or < 1990 ? DateTime.Now.Year : year.Value, string.IsNullOrEmpty(category) ? "업무진단" : category);
        return Page();
    }

    public IActionResult OnGetPreview(int year, string? category)
    {
        if (HttpContext.Items["actor"] is not Actor) return Unauthorized();
        return Content(_audit.PeekDocumentNo(year, category));
    }

    public IActionResult OnPost(int year, string? category, string? title, string? target, string? ownerId, string? shelf, string? binder, string? documentNo, string? summary, string? body, int? retentionYears, string? requestId)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Users = _audit.ListUsers().Where(u => u.Enabled).ToList();
        PreviewNo = _audit.PeekDocumentNo(year, category);
        var result = _audit.RegisterCase(actor, year, category, title, target, ownerId, shelf, binder, documentNo, summary, body, retentionYears, requestId);
        if (!result.Ok || result.CaseId is null)
        {
            Error = result.Message;
            if (FetchReply.Wanted(Request)) return FetchReply.Json(result);
            return Page();
        }
        if (FetchReply.Wanted(Request))
            return FetchReply.Json(OpResult.Success("초안을 저장했습니다. 초안 저장은 결과 확정이 아닙니다."), "/cases/" + result.CaseId);
        HttpContext.Session.SetString("flash", "초안을 저장했습니다. 초안 저장은 결과 확정이 아닙니다.");
        return Redirect($"/cases/{result.CaseId}");
    }
}
