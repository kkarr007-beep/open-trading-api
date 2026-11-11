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
    public AccessState Access { get; set; } = new();
    public bool ShowAccountForm { get; set; }

    public IActionResult OnGet(bool account = false)
    {
        if (!_audit.AnyUsers()) return RedirectToPage("/Account/Setup");
        Access = _audit.GetAccessState();
        ShowAccountForm = account || !Access.AccessPasswordSet;
        Notice = HttpContext.Session.GetString("flash");
        HttpContext.Session.Remove("flash");
        return Page();
    }

    /// <summary>
    /// 들어가기. 로그인 이름을 적지 않았고 접속 비밀번호가 있으면 그 비밀번호 하나로 들어온다.
    /// 이름을 적었으면 그 계정으로 확인한다.
    /// </summary>
    public async Task<IActionResult> OnPostAsync(string? login, string? password)
    {
        if (!_audit.AnyUsers()) return RedirectToPage("/Account/Setup");
        Access = _audit.GetAccessState();
        var byAccount = !string.IsNullOrWhiteSpace(login) || !Access.AccessPasswordSet;
        ShowAccountForm = byAccount;
        var attempt = byAccount ? _audit.Login(login, password) : _audit.LoginWithAccessPassword(password);
        if (!attempt.Ok || attempt.User is null)
        {
            Error = !byAccount && attempt.Message.Length > 0 ? attempt.Message : Msg.LoginFailed;
            return Page();
        }
        await SignInAsync(HttpContext, attempt.User);
        if (attempt.User.MustChange) return RedirectToPage("/Account/Password");
        return RedirectToPage("/Todo/Index");
    }

    /// <summary>계정 이름과 비밀번호로 들어온다. 역할을 나눠 쓰는 자리에서 쓴다.</summary>
    public Task<IActionResult> OnPostAccountAsync(string? login, string? password) => OnPostAsync(login, password);

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
