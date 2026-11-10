using System.Security.Claims;
using AuditRecord.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;

namespace AuditRecord.Web;

public sealed class StampMiddleware
{
    readonly RequestDelegate _next;

    public StampMiddleware(RequestDelegate next) => _next = next;

    public async Task InvokeAsync(HttpContext context, AuditService audit)
    {
        if (context.User.Identity?.IsAuthenticated == true)
        {
            var id = context.User.FindFirstValue(ClaimTypes.NameIdentifier);
            var stamp = context.User.FindFirstValue("stamp");
            var issued = context.User.FindFirstValue("iat");
            var user = string.IsNullOrEmpty(id) ? null : audit.FindUser(id);
            var expired = false;
            if (long.TryParse(issued, out var unix))
            {
                var at = DateTimeOffset.FromUnixTimeSeconds(unix);
                expired = DateTimeOffset.UtcNow - at > TimeSpan.FromHours(8);
            }
            if (user is null || !user.Enabled || user.Stamp != stamp || expired)
            {
                await context.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                context.Response.ContentType = "text/html; charset=utf-8";
                await context.Response.WriteAsync("""
                    <!DOCTYPE html><html lang="ko"><head><meta charset="utf-8"><title>로그인 · 감사기록</title>
                    <link rel="stylesheet" href="/css/site.css"></head><body><main class="narrow">
                    <h1>로그인이 필요합니다</h1>
                    <p>세션이 만료되었거나 비밀번호·계정 상태가 바뀌었습니다.</p>
                    <p><a href="/account/login">로그인</a></p>
                    </main></body></html>
                    """);
                return;
            }
            context.Items["actor"] = user.ToActor();
            var path = context.Request.Path.Value ?? "";
            var allowed = path.StartsWith("/account/password", StringComparison.OrdinalIgnoreCase)
                || path.StartsWith("/account/logout", StringComparison.OrdinalIgnoreCase)
                || path.StartsWith("/css/", StringComparison.OrdinalIgnoreCase)
                || path.StartsWith("/js/", StringComparison.OrdinalIgnoreCase);
            if (user.MustChange && !allowed)
            {
                context.Response.Redirect("/account/password");
                return;
            }
        }
        await _next(context);
    }
}
