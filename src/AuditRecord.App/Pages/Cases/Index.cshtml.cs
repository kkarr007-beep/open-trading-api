using AuditRecord.Domain;
using AuditRecord.Services;

namespace AuditRecord.Pages.Cases;

public class IndexModel : PageModel
{
    readonly AuditService _audit;
    public IndexModel(AuditService audit) => _audit = audit;

    public CaseQuery Query { get; set; } = new();
    public CaseListPage Result { get; set; } = new();
    public IReadOnlyList<int> Years { get; set; } = [];
    public string? Flash { get; set; }
    public bool Searched { get; set; }

    public IActionResult OnGet()
    {
        if (HttpContext.Items["actor"] is not Actor) return RedirectToPage("/Account/Login");
        Load();
        return Page();
    }

    public IActionResult OnPostExport(string? requestId)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        Load();
        var csv = _audit.ExportCasesCsv(actor, Query, string.IsNullOrWhiteSpace(requestId) ? Guid.NewGuid().ToString("N") : requestId);
        return File(System.Text.Encoding.UTF8.GetBytes(csv), "text/csv", "cases.csv");
    }

    public IActionResult OnPostApply(int? year, string? category, string? status, bool overdue, bool dueSoon, string? text, int? pageSize)
    {
        HttpContext.Session.SetString("f-year", year?.ToString() ?? "");
        HttpContext.Session.SetString("f-cat", category ?? "");
        HttpContext.Session.SetString("f-status", status ?? "");
        HttpContext.Session.SetString("f-over", overdue ? "1" : "");
        HttpContext.Session.SetString("f-soon", dueSoon ? "1" : "");
        HttpContext.Session.SetString("f-q", text?.Trim() ?? "");
        if (pageSize is 25 or 50 or 100) HttpContext.Session.SetString("f-size", pageSize.Value.ToString());
        HttpContext.Session.SetString("f-page", "1");
        return RedirectToPage();
    }

    public IActionResult OnPostClear(string? key)
    {
        var map = key switch
        {
            "year" => "f-year",
            "category" => "f-cat",
            "status" => "f-status",
            "overdue" => "f-over",
            "soon" => "f-soon",
            "text" => "f-q",
            _ => null
        };
        if (key == "all")
        {
            foreach (var item in new[] { "f-year", "f-cat", "f-status", "f-over", "f-soon", "f-q", "f-sort", "f-dir", "f-page" })
                HttpContext.Session.Remove(item);
        }
        else if (map != null)
        {
            HttpContext.Session.Remove(map);
            HttpContext.Session.SetString("f-page", "1");
        }
        return RedirectToPage();
    }

    public IActionResult OnPostSort(string? sort)
    {
        var current = HttpContext.Session.GetString("f-sort") ?? "";
        var dir = HttpContext.Session.GetString("f-dir") ?? "desc";
        if (sort is not ("year" or "title" or "due" or "expiry")) sort = "year";
        if (current != sort)
        {
            HttpContext.Session.SetString("f-sort", sort);
            HttpContext.Session.SetString("f-dir", "asc");
        }
        else if (dir == "asc")
        {
            HttpContext.Session.SetString("f-dir", "desc");
        }
        else
        {
            HttpContext.Session.Remove("f-sort");
            HttpContext.Session.Remove("f-dir");
        }
        return RedirectToPage();
    }

    public IActionResult OnPostPage(int page, int? pageSize)
    {
        HttpContext.Session.SetString("f-page", page.ToString());
        if (pageSize is 25 or 50 or 100) HttpContext.Session.SetString("f-size", pageSize.Value.ToString());
        return RedirectToPage();
    }

    void Load()
    {
        int? year = int.TryParse(HttpContext.Session.GetString("f-year"), out var y) ? y : null;
        var size = int.TryParse(HttpContext.Session.GetString("f-size"), out var s) ? s : 50;
        var page = int.TryParse(HttpContext.Session.GetString("f-page"), out var p) ? p : 1;
        Query = new CaseQuery
        {
            Year = year,
            Category = NullIfEmpty(HttpContext.Session.GetString("f-cat")),
            Status = NullIfEmpty(HttpContext.Session.GetString("f-status")),
            Overdue = HttpContext.Session.GetString("f-over") == "1",
            DueSoon = HttpContext.Session.GetString("f-soon") == "1",
            Text = NullIfEmpty(HttpContext.Session.GetString("f-q")),
            Sort = HttpContext.Session.GetString("f-sort") ?? "year",
            Dir = HttpContext.Session.GetString("f-dir") ?? "desc",
            Page = page,
            PageSize = size
        };
        Searched = !string.IsNullOrEmpty(Query.Text);
        Result = _audit.ListCases(Query);
        Years = _audit.Years();
        Flash = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
    }

    public string SortLabel(string column)
    {
        var current = Query.Sort;
        var dir = Query.Dir;
        if (current != column) return "정렬 안 함, 누르면 오름차순";
        return dir == "asc" ? "오름차순, 누르면 내림차순" : "내림차순, 누르면 기본 정렬";
    }

    public string AriaSort(string column)
    {
        if (Query.Sort != column) return "none";
        return Query.Dir == "asc" ? "ascending" : "descending";
    }

    static string? NullIfEmpty(string? value) => string.IsNullOrWhiteSpace(value) ? null : value;
}
