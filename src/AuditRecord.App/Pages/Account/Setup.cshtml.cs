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
        if (BlockRemote()) return Page();
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(string? login, string? displayName, string? password, string? confirm)
    {
        if (_audit.AnyUsers()) return NotFound();
        if (BlockRemote()) return Page();
        if (!string.Equals(password, confirm, StringComparison.Ordinal))
        {
            Error = "비밀번호 확인이 일치하지 않습니다.";
            return Page();
        }
        var result = _audit.SetupAdmin(login, displayName, password);
        if (!result.Ok || result.UserId is null)
        {
            Error = result.Message;
            return Page();
        }
        var user = _audit.FindUser(result.UserId);
        if (user is null) return RedirectToPage("/Account/Login");
        await LoginModel.SignInAsync(HttpContext, user);
        return RedirectToPage("/Admin/Index");
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
