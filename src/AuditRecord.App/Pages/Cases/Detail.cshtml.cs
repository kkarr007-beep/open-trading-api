using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Cases;

public class DetailModel : PageModel
{
    readonly AuditService _audit;
    public DetailModel(AuditService audit) => _audit = audit;

    public CaseDetail? Detail { get; set; }
    public IReadOnlyList<UserAccount> Users { get; set; } = [];
    public string? Error { get; set; }
    public string? Flash { get; set; }
    [BindProperty(SupportsGet = true)] public string Id { get; set; } = "";

    public IActionResult OnGet()
    {
        var actor = Actor();
        if (actor is null) return RedirectToPage("/Account/Login");
        Detail = _audit.GetCase(actor, Id);
        if (Detail is null)
        {
            Response.StatusCode = 404;
            return Page();
        }
        Users = _audit.ListUsers();
        Flash = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
        return Page();
    }

    public IActionResult OnPostSaveCase(int version, int year, string? category, string? title, string? target, string? ownerId, string? shelf, string? binder, string? requestId) =>
        Finish(_audit.SaveCaseFields(Need(), Id, version, year, category, title, target, ownerId, shelf, binder, requestId));

    public IActionResult OnPostSaveResult(int version, string? documentNo, string? summary, string? body, string? requestId) =>
        Finish(_audit.SaveResult(Need(), Id, version, documentNo, summary, body, requestId));

    public IActionResult OnPostRetention(int version, int years, string? reason, string? requestId) =>
        Finish(_audit.ChangeRetention(Need(), Id, version, years, reason, requestId));

    public IActionResult OnPostConfirm(int version, string? requestId) =>
        Finish(_audit.Confirm(Need(), Id, version, requestId));

    public IActionResult OnPostCancel(int version, string? reason, string? requestId) =>
        Finish(_audit.CancelConfirm(Need(), Id, version, reason, requestId));

    public IActionResult OnPostClose(int version, bool noActionConfirmed, string? requestId) =>
        Finish(_audit.CloseCase(Need(), Id, version, noActionConfirmed, requestId));

    public IActionResult OnPostAddAction(int version, string? title, string? type, string? dept, string? requestedOn, string? dueOn, string? content, string? requestId) =>
        Finish(_audit.AddAction(Need(), Id, version, title, type, dept, requestedOn, dueOn, content, requestId));

    public IActionResult OnPostReply(int version, string actionId, string? reply, string? requestId) =>
        Finish(_audit.RecordReply(Need(), Id, version, actionId, reply, requestId));

    public IActionResult OnPostVerify(int version, string actionId, string? check, string? requestId) =>
        Finish(_audit.VerifyAction(Need(), Id, version, actionId, check, requestId));

    public IActionResult OnPostUpload(int version, string? kind, string? displayName, IFormFile? upload, string? requestId)
    {
        var actor = Need();
        if (upload is null || upload.Length == 0)
            return Finish(OpResult.Fail(422, "BAD_INPUT", "파일을 선택하세요."));
        using var stream = upload.OpenReadStream();
        return Finish(_audit.AddFile(actor, Id, version, kind, displayName, stream, requestId));
    }

    public IActionResult OnPostRemove(int version, string attachmentId, string? requestId) =>
        Finish(_audit.RemoveDraftFile(Need(), Id, version, attachmentId, requestId));

    public IActionResult OnPostEarly(int version, string attachmentId, string? reason, string? requestId) =>
        Finish(_audit.EarlyDeleteFile(Need(), Id, version, attachmentId, reason, requestId));

    public IActionResult OnPostHash(int version, string attachmentId, string? requestId) =>
        Finish(_audit.VerifyFileHash(Need(), Id, attachmentId, requestId));

    IActionResult Finish(OpResult result)
    {
        if (HttpContext.Items["actor"] is not Actor actor)
        {
            if (FetchReply.Wanted(Request))
                return FetchReply.Json(OpResult.Fail(401, "UNAUTHORIZED", "다시 로그인하세요."));
            return RedirectToPage("/Account/Login");
        }
        if (FetchReply.Wanted(Request)) return FetchReply.Json(result);
        if (result.Status == 403)
        {
            Error = string.IsNullOrWhiteSpace(result.Message) ? "이 작업 권한이 없습니다." : result.Message;
            Detail = _audit.GetCase(actor, Id);
            Users = _audit.ListUsers();
            Response.StatusCode = StatusCodes.Status403Forbidden;
            return Page();
        }
        if (result.Ok)
        {
            HttpContext.Session.SetString("flash", result.Message);
            return Redirect($"/cases/{Id}");
        }
        Error = result.Message;
        Detail = _audit.GetCase(actor, Id);
        Users = _audit.ListUsers();
        Response.StatusCode = result.Status == 0 ? 400 : result.Status;
        return Page();
    }

    Actor Need() => (Actor)HttpContext.Items["actor"]!;
    Actor? Actor() => HttpContext.Items["actor"] as Actor;
}
