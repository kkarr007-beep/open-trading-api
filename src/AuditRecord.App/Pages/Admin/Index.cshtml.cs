using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Admin;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;
    public SystemState State { get; set; } = new();
    public IReadOnlyList<UserAccount> Users { get; set; } = [];
    public IReadOnlyList<BackupRow> Backups { get; set; } = [];
    public IReadOnlyList<DeleteJobRow> Jobs { get; set; } = [];
    public CaseListPage Cases { get; set; } = new();
    public bool Forbidden { get; private set; }
    public string? Error { get; set; }
    public string? Flash { get; set; }
    public string? Issued { get; set; }
    public string BackupRoot { get; set; } = "";

    public IActionResult OnGet()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        if (!actor.IsAdmin) return Deny();
        Load(actor);
        Issued = HttpContext.Session.GetString("once");
        HttpContext.Session.Remove("once");
        return Page();
    }

    public IActionResult OnPostCreate(string? login, string? displayName, string? role, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        var result = _audit.CreateAccount(actor, login, displayName, role, requestId);
        return Done(actor, result);
    }

    public IActionResult OnPostDisable(string userId, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.DisableAccount(actor, userId, requestId));
    }

    public IActionResult OnPostReset(string userId, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.ResetAccountPassword(actor, userId, requestId));
    }

    public IActionResult OnPostRole(string userId, string? role, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.ChangeAccountRole(actor, userId, role, requestId));
    }

    public IActionResult OnPostAck(string? requestId, bool accepted)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        if (!accepted) return Done(actor, OpResult.Fail(422, "BAD_INPUT", "확인란을 선택해야 자동 삭제를 켤 수 있습니다."));
        return Done(actor, _audit.AcknowledgePolicy(actor, requestId));
    }

    public IActionResult OnPostLimits(int days, int generations, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.UpdateBackupLimits(actor, days, generations, requestId));
    }

    public IActionResult OnPostBackup(string? subfolder, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.Backup(actor, subfolder, requestId));
    }

    public IActionResult OnPostVerify(string? backupId, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.VerifyBackup(actor, backupId, requestId));
    }

    public IActionResult OnPostReapply(string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.ReapplyDeletions(actor, requestId));
    }

    public IActionResult OnPostHold(string caseId, int version, string? reason, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.SetHold(actor, caseId, version, reason, requestId));
    }

    public IActionResult OnPostUnhold(string caseId, int version, string? reason, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.ReleaseHold(actor, caseId, version, reason, requestId));
    }

    public IActionResult OnPostRunDue(string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.RunDueNow(actor, requestId));
    }

    public IActionResult OnPostClearIntegrity(string? reason, string? requestId)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.AcknowledgeIntegrity(actor, reason, requestId));
    }

    public IActionResult OnPostVacuum()
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        return Done(actor, _audit.Vacuum(actor));
    }

    public IActionResult OnPostFind(string? text)
    {
        var actor = Admin();
        if (actor is null) return DenyOrLogin();
        HttpContext.Session.SetString("admin-q", text?.Trim() ?? "");
        return RedirectToPage();
    }

    IActionResult Done(Actor actor, OpResult result)
    {
        var issued = result.TakeIssuedPassword();
        if (!string.IsNullOrEmpty(issued)) HttpContext.Session.SetString("once", issued);
        if (FetchReply.Wanted(Request))
        {
            if (!string.IsNullOrEmpty(issued)) HttpContext.Session.SetString("once", issued);
            return FetchReply.Json(result, reload: !string.IsNullOrEmpty(issued));
        }
        if (result.Ok) HttpContext.Session.SetString("flash", result.Message);
        else HttpContext.Session.SetString("flash", result.Message);
        if (!result.Ok) Response.StatusCode = result.Status;
        if (result.Ok) return RedirectToPage();
        Load(actor);
        Error = result.Message;
        Issued = issued;
        return Page();
    }

    void Load(Actor actor)
    {
        State = _audit.GetState();
        Users = _audit.ListUsers();
        Backups = _audit.RecentBackups();
        Jobs = _audit.RecentDeleteJobs();
        BackupRoot = _audit.BackupRoot;
        var text = HttpContext.Session.GetString("admin-q");
        Cases = _audit.ListCases(new CaseQuery { Text = string.IsNullOrWhiteSpace(text) ? null : text, PageSize = 25 });
        Flash = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
        _ = actor;
    }

    Actor? Admin()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        return actor is { IsAdmin: true } ? actor : null;
    }

    IActionResult DenyOrLogin()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        return Deny();
    }

    IActionResult Deny()
    {
        Forbidden = true;
        return Page();
    }
}
