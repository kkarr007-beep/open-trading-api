using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Discipline;

public class EditModel : PageModel
{
    readonly AuditService _audit;
    public EditModel(AuditService audit) => _audit = audit;
    public bool Allowed { get; set; }
    public bool Admin { get; set; }
    public DisciplineRow? Item { get; set; }
    public string? Error { get; set; }
    [BindProperty(SupportsGet = true)] public string? Id { get; set; }

    public IActionResult OnGet()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Allowed = _audit.CanReadDiscipline(actor);
        Admin = actor.IsAdmin;
        if (!Allowed) return Page();
        if (!string.IsNullOrEmpty(Id) && Id != "new")
        {
            Item = _audit.OpenDiscipline(actor, Id, recordView: true);
            if (Item is null)
            {
                Response.StatusCode = 404;
                Error = "징계기록을 찾을 수 없습니다.";
            }
        }
        return Page();
    }

    public IActionResult OnPost(int version, string? caseId, int year, string? who, string? whenText, string? whereText, string? whatText, string? howText, string? whyText, string? sanction, string? outcome, string? ruleText, string? requestId)
    {
        var actor = Need();
        if (actor is null) return RedirectToPage("/Account/Login");
        if (!_audit.CanReadDiscipline(actor)) return Forbid();
        var id = Id == "new" ? null : Id;
        var result = _audit.SaveDiscipline(actor, id, version, caseId, year, who, whenText, whereText, whatText, howText, whyText, sanction, outcome, ruleText, requestId);
        if (FetchReply.Wanted(Request))
            return FetchReply.Json(result, result.Ok ? "/discipline/" + result.Extra : null);
        if (!result.Ok)
        {
            Error = result.Message;
            Allowed = true;
            Admin = actor.IsAdmin;
            if (!string.IsNullOrEmpty(id)) Item = _audit.OpenDiscipline(actor, id, false);
            Response.StatusCode = result.Status == 0 ? 400 : result.Status;
            return Page();
        }
        return Redirect("/discipline/" + result.Extra);
    }

    public IActionResult OnPostHold(int version, string? reason, string? requestId, string? release)
    {
        var actor = Need();
        if (actor is null) return RedirectToPage("/Account/Login");
        var result = _audit.SetDisciplineHold(actor, Id ?? "", version, release != "1", reason, requestId);
        if (FetchReply.Wanted(Request)) return FetchReply.Json(result);
        if (!result.Ok) Error = result.Message;
        return Redirect("/discipline/" + Id);
    }

    Actor? Need() => HttpContext.Items["actor"] as Actor;
}
