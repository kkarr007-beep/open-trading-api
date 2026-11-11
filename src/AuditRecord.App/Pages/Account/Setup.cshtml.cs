using System.Net;
using AuditRecord.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AuditRecord.Pages.Account;

public class SetupModel : PageModel
{
    readonly AuditService _audit;
    public SetupModel(AuditService audit) => _audit = audit;
    public string? Error { get; set; }
    public bool Blocked { get; private set; }

    public IActionResult OnGet()
    {
        if (_audit.AnyUsers()) return NotFound();
        BlockRemote();
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(string? teamName, string? password, string? confirm)
    {
        if (_audit.AnyUsers()) return NotFound();
        if (BlockRemote()) return Page();
        var result = _audit.InstallSimple(teamName, password, confirm);
        if (!result.Ok || result.UserId is null)
        {
            Error = result.Message;
            return Page();
        }
        var user = _audit.FindUser(result.UserId);
        if (user is null) return RedirectToPage("/Account/Login");
        await LoginModel.SignInAsync(HttpContext, user);
        return RedirectToPage("/Todo/Index");
    }

    bool BlockRemote()
    {
        if (IsLoopbackClient(HttpContext)) return false;
        Blocked = true;
        Response.StatusCode = 403;
        return true;
    }

    public static bool IsLoopbackClient(HttpContext http)
    {
        var ip = http.Connection.RemoteIpAddress;
        if (ip is null) return false;
        if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
        return IPAddress.IsLoopback(ip);
    }
}
