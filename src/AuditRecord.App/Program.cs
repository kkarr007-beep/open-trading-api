using System.Net;
using System.Text.Encodings.Web;
using System.Text.Unicode;
using AuditRecord.Data;
using AuditRecord.Domain;
using AuditRecord.Services;
using AuditRecord.Storage;
using AuditRecord.Web;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.HttpOverrides;

try
{
    StartApp(args);
}
catch (StartupFailure ex)
{
    Console.Error.WriteLine(ex.Message);
    TryWriteStartupNote(ex.Message);
    var entry = System.Reflection.Assembly.GetEntryAssembly()?.GetName().Name;
    if (entry == "AuditRecord.App")
        Environment.Exit(2);
    throw;
}

static void StartApp(string[] args)
{
var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    ContentRootPath = AppContext.BaseDirectory
});
builder.Host.UseWindowsService(o => o.ServiceName = "AuditRecord");
builder.Logging.ClearProviders();
builder.Logging.AddSimpleConsole(o =>
{
    o.SingleLine = true;
    o.TimestampFormat = "HH:mm:ss ";
});
builder.Logging.AddFilter("Microsoft", LogLevel.Warning);
builder.Logging.AddFilter("System", LogLevel.Warning);

var paths = AppPaths.FromConfiguration(builder.Configuration, builder.Environment.ContentRootPath);
var urls = ListenUrls.Resolve(args, Environment.GetEnvironmentVariable("AUDIT_URLS"), paths.Urls);
if (!builder.Environment.IsDevelopment())
    builder.WebHost.UseSetting(WebHostDefaults.DetailedErrorsKey, "false");
if (!string.IsNullOrWhiteSpace(paths.CertificatePath))
{
    var password = ReadCertPassword();
    StartupChecks.EnsureCertificate(paths.CertificatePath, password);
    IPAddress address;
    try
    {
        address = IPAddress.Parse(paths.BindAddress ?? "127.0.0.1");
    }
    catch (FormatException)
    {
        throw new StartupFailure("BindAddress 가 IP 주소가 아닙니다. 비우면 127.0.0.1 입니다.");
    }
    var port = paths.BindPort == 0 ? 8443 : paths.BindPort;
    builder.WebHost.ConfigureKestrel(k =>
    {
        k.AddServerHeader = false;
        k.Limits.MaxRequestBodySize = FileStore.MaxBytes + (1024 * 1024);
        k.Listen(address, port, o => o.UseHttps(paths.CertificatePath, password));
    });
}
else
{
    StartupChecks.EnsureHttpLoopback(urls, paths.AllowInsecureHttp);
    builder.WebHost.UseUrls(urls);
    builder.WebHost.ConfigureKestrel(k =>
    {
        k.AddServerHeader = false;
        k.Limits.MaxRequestBodySize = FileStore.MaxBytes + (1024 * 1024);
    });
}

builder.Services.AddSingleton(paths);
builder.Services.AddSingleton<InstanceLock>();
var clockDays = StartupChecks.DemoClockOffsetDays(paths.Mode, builder.Configuration["Audit:DemoClockOffsetDays"]);
if (clockDays == 0)
    builder.Services.AddSingleton<IClock, SystemClock>();
else
    builder.Services.AddSingleton<IClock>(new OffsetClock(new SystemClock(), TimeSpan.FromDays(clockDays)));
builder.Services.AddSingleton<Database>();
builder.Services.AddSingleton<IFileDeleter, SystemFileDeleter>();
builder.Services.AddSingleton<FileStore>();
builder.Services.AddSingleton<RetentionLedger>();
builder.Services.AddSingleton<MaintenanceGate>();
builder.Services.AddSingleton<Passwords>();
builder.Services.AddSingleton<AuditService>();
builder.Services.AddSingleton<Bootstrap>();
builder.Services.AddHostedService<RetentionWorker>();

var dataProtection = builder.Services.AddDataProtection()
    .PersistKeysToFileSystem(new DirectoryInfo(paths.KeysDir))
    .SetApplicationName("audit-record");
if (OperatingSystem.IsWindows())
    dataProtection.ProtectKeysWithDpapi(protectToLocalMachine: true);

builder.Services.AddAuthentication(CookieAuthenticationDefaults.AuthenticationScheme)
    .AddCookie(o =>
    {
        o.Cookie.Name = "audit.session";
        o.Cookie.HttpOnly = true;
        o.Cookie.SameSite = SameSiteMode.Strict;
        o.Cookie.SecurePolicy = CookieSecurePolicy.SameAsRequest;
        o.LoginPath = "/account/login";
        o.AccessDeniedPath = "/account/denied";
        o.SlidingExpiration = true;
        o.ExpireTimeSpan = TimeSpan.FromMinutes(30);
    });
builder.Services.AddAuthorization();
builder.Services.AddAntiforgery(o =>
{
    o.Cookie.Name = "audit.csrf";
    o.Cookie.HttpOnly = true;
    o.Cookie.SameSite = SameSiteMode.Strict;
    o.Cookie.SecurePolicy = CookieSecurePolicy.SameAsRequest;
    o.HeaderName = "RequestVerificationToken";
});
builder.Services.AddDistributedMemoryCache();
builder.Services.AddSession(o =>
{
    o.Cookie.Name = "audit.view";
    o.Cookie.HttpOnly = true;
    o.Cookie.SameSite = SameSiteMode.Strict;
    o.Cookie.IsEssential = true;
    o.IdleTimeout = TimeSpan.FromMinutes(30);
});
builder.Services.Configure<FormOptions>(o =>
{
    o.MultipartBodyLengthLimit = FileStore.MaxBytes;
    o.ValueLengthLimit = 30000;
});
builder.Services.AddSingleton(HtmlEncoder.Create(UnicodeRanges.All));
builder.Services.AddRazorPages(o =>
{
    o.Conventions.AuthorizeFolder("/");
    o.Conventions.AllowAnonymousToPage("/Account/Login");
    o.Conventions.AllowAnonymousToPage("/Account/Setup");
    o.Conventions.AllowAnonymousToPage("/Error");
});

builder.Services.Configure<ForwardedHeadersOptions>(o =>
{
    o.ForwardedHeaders = ForwardedHeaders.None;
});

var app = builder.Build();
app.Services.GetRequiredService<Bootstrap>().Run();

app.UseExceptionHandler("/error");
app.Use(async (context, next) =>
{
    context.Response.Headers["Content-Security-Policy"] =
        "default-src 'self'; style-src 'self'; script-src 'self'; img-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
    context.Response.Headers["X-Content-Type-Options"] = "nosniff";
    context.Response.Headers["Referrer-Policy"] = "no-referrer";
    context.Response.Headers["X-Frame-Options"] = "DENY";
    context.Response.Headers["Cache-Control"] = "no-store";
    context.Response.Headers.Remove("Server");
    await next();
});
app.UseStaticFiles();
app.Use(async (context, next) =>
{
    if (HttpMethods.IsPost(context.Request.Method))
    {
        var audit = context.RequestServices.GetRequiredService<AuditService>();
        var integrity = !audit.IntegrityIntact();
        var restore = audit.RestoreLocked;
        if ((integrity || restore) && !FrozenPostAllowed(context, integrity, restore))
        {
            var back = context.Request.Path.HasValue ? context.Request.Path.Value! : "/todo";
            context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
            context.Response.ContentType = "text/html; charset=utf-8";
            await context.Response.WriteAsync($$"""
                <!DOCTYPE html>
                <html lang="ko">
                <head><meta charset="utf-8"><title>읽기 전용 · 감사기록</title></head>
                <body>
                <main>
                <h1>읽기 전용</h1>
                <p>이력 훼손 또는 복구 대조 중에는 변경을 받지 않습니다. 조사 확인이나 삭제 기록 다시 적용 전에는 저장되지 않습니다.</p>
                <p><a href="{{back}}">돌아가기</a></p>
                </main>
                </body>
                </html>
                """);
            return;
        }
    }
    await next();
});
app.UseRouting();
app.UseAuthentication();
app.UseAuthorization();
app.UseSession();
app.UseMiddleware<StampMiddleware>();
app.MapGet("/cases/{id}/files/{attachmentId}", (string id, string attachmentId, string? dl, HttpContext http, AuditService audit) =>
{
    var actor = http.Items["actor"] as Actor;
    if (actor is null) return Results.Redirect("/account/login");
    var result = audit.OpenAttachment(actor, id, attachmentId, dl == "1", out var opened);
    if (!result.Ok || opened is null)
        return Results.Text(result.Message, "text/plain", System.Text.Encoding.UTF8, result.Status == 0 ? 400 : result.Status);
    http.Response.Headers["X-Content-Type-Options"] = "nosniff";
    http.Response.Headers["Content-Security-Policy"] =
        "sandbox; default-src 'none'; script-src 'none'; style-src 'none'; img-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'";
    http.Response.Headers["Cache-Control"] = "no-store";
    var kind = opened.Inline ? "inline" : "attachment";
    http.Response.Headers.ContentDisposition = $"{kind}; filename=\"file\"; filename*=UTF-8''{Uri.EscapeDataString(opened.DownloadName)}";
    return Results.Stream(opened.Content, opened.ContentType);
});
app.MapRazorPages();
app.Run();
}

static bool FrozenPostAllowed(HttpContext context, bool integrity, bool restore)
{
    var path = context.Request.Path.Value ?? "";
    if (path.StartsWith("/account/login", StringComparison.OrdinalIgnoreCase)
        || path.StartsWith("/account/logout", StringComparison.OrdinalIgnoreCase)
        || path.StartsWith("/account/setup", StringComparison.OrdinalIgnoreCase))
        return true;
    var handler = context.Request.Query["handler"].ToString();
    if (handler is "Apply" or "Sort" or "Page" or "Clear" or "Find") return true;
    if (integrity && handler.Equals("ClearIntegrity", StringComparison.OrdinalIgnoreCase)) return true;
    if (restore && handler.Equals("Reapply", StringComparison.OrdinalIgnoreCase)) return true;
    return false;
}

static void TryWriteStartupNote(string message)
{
    try
    {
        var dir = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "data"));
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, "startup-error.log"), message + Environment.NewLine);
    }
    catch (Exception)
    {
        // the console line is enough when the folder cannot be written
    }
}

static string ReadCertPassword()
{
    var direct = Environment.GetEnvironmentVariable("AUDIT_CERT_PASSWORD");
    if (!string.IsNullOrEmpty(direct)) return direct;
    var file = Environment.GetEnvironmentVariable("AUDIT_CERT_PASSWORD_FILE");
    if (string.IsNullOrWhiteSpace(file) || !File.Exists(file)) return "";
    return File.ReadAllText(file).Trim();
}

public partial class Program;
