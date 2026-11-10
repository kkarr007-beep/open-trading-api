using System.Security.Claims;
using AuditRecord.Domain;
using AuditRecord.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AuditRecord.Pages.Account;

public class LoginModel : PageModel
{
    readonly AuditService _audit;
    public LoginModel(AuditService audit) => _audit = audit;

    public string? Notice { get; set; }
    public string? Error { get; set; }

    public IActionResult OnGet()
    {
        if (!_audit.AnyUsers()) return RedirectToPage("/Account/Setup");
        Notice = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(string? login, string? password)
    {
        if (!_audit.AnyUsers()) return RedirectToPage("/Account/Setup");
        var attempt = _audit.Login(login, password);
        if (!attempt.Ok || attempt.User is null)
        {
            Error = Msg.LoginFailed;
            return Page();
        }
        await SignInAsync(HttpContext, attempt.User);
        if (attempt.User.MustChange) return RedirectToPage("/Account/Password");
        return RedirectToPage("/Todo/Index");
    }

    public static async Task SignInAsync(HttpContext context, UserAccount user)
    {
        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier, user.UserId),
            new("stamp", user.Stamp),
            new("iat", DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(System.Globalization.CultureInfo.InvariantCulture)),
            new(ClaimTypes.Name, user.DisplayName)
        };
        var identity = new ClaimsIdentity(claims, CookieAuthenticationDefaults.AuthenticationScheme);
        await context.SignInAsync(CookieAuthenticationDefaults.AuthenticationScheme, new ClaimsPrincipal(identity));
    }
}
