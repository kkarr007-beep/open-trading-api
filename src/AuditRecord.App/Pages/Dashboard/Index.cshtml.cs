using System.Text.Json;
using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Dashboard;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;

    public DashboardData Data { get; set; } = new();
    public string ChartsJson { get; set; } = "{}";
    public string? Flash { get; set; }

    static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping
    };

    public IActionResult OnGet()
    {
        if (HttpContext.Items["actor"] is not Actor) return RedirectToPage("/Account/Login");
        Data = _audit.GetDashboard();
        ChartsJson = JsonSerializer.Serialize(new
        {
            byYear = Data.ByYear.Select(x => new { label = x.Label, count = x.Count }),
            byPhase = Data.ByPhase.Select(x => new { label = x.Label, count = x.Count }),
            byCategory = Data.ByCategory.Select(x => new { label = x.Label, count = x.Count }),
            byActionState = Data.ByActionState.Select(x => new { label = x.Label, count = x.Count })
        }, JsonOptions);
        Flash = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
        return Page();
    }
}
