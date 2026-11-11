using System.Reflection;
using AuditRecord.Domain;
using AuditRecord.Services;
using AuditRecord.Storage;

namespace AuditRecord.Pages.Settings;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    readonly AppPaths _paths;
    public IndexModel(AuditService audit, AppPaths paths)
    {
        _audit = audit;
        _paths = paths;
    }

    public bool Admin { get; set; }
    public string Version { get; set; } = "";
    public string? Error { get; set; }
    public string? Flash { get; set; }
    public IReadOnlyList<(string Category, string Code, bool Used)> Codes { get; set; } = [];
    public AccessState Access { get; set; } = new();
    public AppPaths Paths => _paths;

    public IActionResult OnGet()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Admin = actor.IsAdmin;
        Version = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "1.0.0";
        Codes = _audit.ListCategoryCodes();
        Access = _audit.GetAccessState();
        Flash = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
        return Page();
    }

    public IActionResult OnPostCode(string? category, string? code, string? requestId)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        var result = _audit.SetCategoryCode(actor, category, code, requestId);
        if (FetchReply.Wanted(Request)) return FetchReply.Json(result);
        if (!result.Ok) Error = result.Message;
        else HttpContext.Session.SetString("flash", result.Message);
        if (result.Ok) return RedirectToPage();
        Admin = actor.IsAdmin;
        Version = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "1.0.0";
        Codes = _audit.ListCategoryCodes();
        return Page();
    }

    /// <summary>접속 비밀번호 변경. 관리자만. 바꾼 뒤에는 새 비밀번호만 들어옵니다.</summary>
    public IActionResult OnPostAccess(string? current, string? next, string? confirm, string? requestId)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        var result = _audit.ChangeAccessPassword(actor, current, next, confirm, requestId);
        HttpContext.Session.SetString("flash", result.Message);
        if (!result.Ok)
        {
            Admin = actor.IsAdmin;
            Version = Assembly.GetExecutingAssembly().GetName().Version?.ToString() ?? "1.0.0";
            Codes = _audit.ListCategoryCodes();
            Access = _audit.GetAccessState();
            Error = result.Message;
            HttpContext.Session.Remove("flash");
            Response.StatusCode = result.Status == 0 ? 400 : result.Status;
            return Page();
        }
        return RedirectToPage();
    }
}
