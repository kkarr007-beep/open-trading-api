using AuditRecord.Domain;

namespace AuditRecord.Pages;

public static class FetchReply
{
    public static bool Wanted(HttpRequest request) =>
        string.Equals(request.Headers["X-Audit-Fetch"], "1", StringComparison.Ordinal);

    public static IActionResult Json(OpResult result, string? go = null, bool reload = false)
    {
        var status = result.Ok ? 200 : result.Status == 0 ? 400 : result.Status;
        var tone = result.Ok ? "ok" : status == 503 ? "warn" : "danger";
        return new JsonResult(new
        {
            ok = result.Ok,
            status,
            message = result.Message,
            tone,
            go,
            reload
        })
        { StatusCode = status };
    }
}
