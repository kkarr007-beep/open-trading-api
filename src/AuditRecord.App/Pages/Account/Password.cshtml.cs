using AuditRecord.Domain;
using AuditRecord.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AuditRecord.Pages.Account;

public class PasswordModel : PageModel
{
    readonly AuditService _audit;
    public PasswordModel(AuditService audit) => _audit = audit;
    public string? Error { get; set; }
    public bool MustChange { get; set; }

    public IActionResult OnGet()
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        var user = _audit.FindUser(actor.UserId);
        MustChange = user?.MustChange == true;
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(string? currentPassword, string? newPassword)
    {
        var actor = HttpContext.Items["actor"] as Actor;
        if (actor is null) return RedirectToPage("/Account/Login");
        var result = _audit.ChangePassword(actor, currentPassword, newPassword, Guid.NewGuid().ToString("N"));
        if (!result.Ok)
        {
            Error = result.Message;
            MustChange = _audit.FindUser(actor.UserId)?.MustChange == true;
            return Page();
        }
        HttpContext.Session.SetString("flash", "비밀번호를 변경했습니다. 다시 로그인하세요.");
        await HttpContext.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
        return RedirectToPage("/Account/Login");
    }
}
