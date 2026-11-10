using System.Net;
using Microsoft.Extensions.DependencyInjection;
using System.Text.RegularExpressions;
using AuditRecord.Domain;

namespace AuditRecord.Tests;

public class HttpTests
{
    [Fact]
    public async Task Staff_confirm_is_forbidden_and_missing_csrf_is_rejected()
    {
        await using var factory = new AuditFactory();
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        await client.PostAsync("/account/setup", Form(await Token(client, "/account/setup"), new()
        {
            ["login"] = "admin-user",
            ["displayName"] = "시험관리",
            ["password"] = Harness.Password,
            ["confirm"] = Harness.Password
        }));
        var admin = factory.Services.GetRequiredService<AuditRecord.Services.AuditService>();
        var adminActor = admin.FindUser(admin.ListUsers().Single().UserId)!.ToActor();
        var created = admin.CreateAccount(adminActor, "staff1", "시험담당", "담당", Guid.NewGuid().ToString("N"));
        var staffActor = admin.FindUser(created.UserId!)!.ToActor();
        var staffPassword = created.TakeIssuedPassword();
        admin.ChangePassword(staffActor, staffPassword, Harness.Password, Guid.NewGuid().ToString("N"));
        using var staff = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var login = await staff.PostAsync("/account/login", Form(await Token(staff, "/account/login"), new()
        {
            ["login"] = "staff1",
            ["password"] = Harness.Password
        }));
        Assert.Equal(HttpStatusCode.Redirect, login.StatusCode);
        var page = await staff.GetAsync("/cases/new");
        var html = await page.Content.ReadAsStringAsync();
        var id = await CreateCase(staff, html);
        var detail = await staff.GetStringAsync("/cases/" + id);
        Assert.DoesNotContain("id=\"btn-confirm\"", detail, StringComparison.Ordinal);
        var version = VersionOf(detail);
        var denied = await staff.PostAsync("/cases/" + id + "?handler=Confirm", Form(TokenOf(detail), new()
        {
            ["version"] = version,
            ["requestId"] = Guid.NewGuid().ToString("N")
        }));
        Assert.Equal(HttpStatusCode.Forbidden, denied.StatusCode);
        var bare = new HttpRequestMessage(HttpMethod.Post, "/cases/" + id + "?handler=SaveResult");
        bare.Content = new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["version"] = version,
            ["requestId"] = Guid.NewGuid().ToString("N"),
            ["documentNo"] = "x",
            ["summary"] = "y"
        });
        var csrf = await staff.SendAsync(bare);
        Assert.Equal(HttpStatusCode.BadRequest, csrf.StatusCode);
    }

    [Fact]
    public async Task Reset_and_disable_revoke_the_old_session()
    {
        await using var factory = new AuditFactory();
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        await client.PostAsync("/account/setup", Form(await Token(client, "/account/setup"), new()
        {
            ["login"] = "admin-user",
            ["displayName"] = "시험관리",
            ["password"] = Harness.Password,
            ["confirm"] = Harness.Password
        }));
        var svc = factory.Services.GetRequiredService<AuditRecord.Services.AuditService>();
        var adminActor = svc.ListUsers().Single().ToActor();
        var created = svc.CreateAccount(adminActor, "staff1", "시험담당", "담당", Guid.NewGuid().ToString("N"));
        var staffActor = svc.FindUser(created.UserId!)!.ToActor();
        svc.ChangePassword(staffActor, created.TakeIssuedPassword(), Harness.Password, Guid.NewGuid().ToString("N"));
        using var staff = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        Assert.Equal(HttpStatusCode.Redirect, (await staff.PostAsync("/account/login", Form(await Token(staff, "/account/login"), new()
        {
            ["login"] = "staff1",
            ["password"] = Harness.Password
        }))).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await staff.GetAsync("/todo")).StatusCode);
        svc.ResetAccountPassword(adminActor, created.UserId!, Guid.NewGuid().ToString("N"));
        Assert.Equal(HttpStatusCode.Unauthorized, (await staff.GetAsync("/todo")).StatusCode);

        using var again = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var temp = svc.ResetAccountPassword(adminActor, created.UserId!, Guid.NewGuid().ToString("N")).TakeIssuedPassword()!;
        await again.PostAsync("/account/login", Form(await Token(again, "/account/login"), new()
        {
            ["login"] = "staff1",
            ["password"] = temp
        }));
        Assert.Equal(HttpStatusCode.OK, (await again.GetAsync("/account/password")).StatusCode);
        svc.DisableAccount(adminActor, created.UserId!, Guid.NewGuid().ToString("N"));
        Assert.Equal(HttpStatusCode.Unauthorized, (await again.GetAsync("/account/password")).StatusCode);
    }

    [Fact]
    public async Task Pages_have_no_external_urls_and_search_stays_out_of_the_url()
    {
        await using var factory = new AuditFactory { Mode = "demo" };
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var access = File.ReadAllLines(Path.Combine(factory.Root, "data", "demo-access.local"));
        var staff = access[0].Split(' ', 2);
        var loginPage = await client.GetStringAsync("/account/login");
        Assert.DoesNotContain("http://", loginPage, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("https://", loginPage, StringComparison.OrdinalIgnoreCase);
        var css = await client.GetStringAsync("/css/site.css");
        Assert.DoesNotContain("http://", css, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("@font-face", css, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("Malgun Gothic", css, StringComparison.Ordinal);
        await client.PostAsync("/account/login", Form(TokenOf(loginPage), new()
        {
            ["login"] = staff[0],
            ["password"] = staff[1]
        }));
        var listHtml = await client.GetStringAsync("/cases");
        Assert.Contains("aria-sort", listHtml, StringComparison.Ordinal);
        var id = Regex.Match(listHtml, "/cases/([0-9a-f]{32})").Groups[1].Value;
        Assert.False(string.IsNullOrEmpty(id));
        var posted = await client.PostAsync("/cases?handler=Apply", Form(TokenOf(listHtml), new()
        {
            ["text"] = "본문고유검색어"
        }));
        Assert.Equal(HttpStatusCode.Redirect, posted.StatusCode);
        Assert.DoesNotContain("본문고유검색어", posted.Headers.Location?.ToString(), StringComparison.Ordinal);
        var todo = await client.GetStringAsync("/todo");
        Assert.Contains("할 일", todo, StringComparison.Ordinal);
        Assert.Contains("회신기한 초과", todo, StringComparison.Ordinal);
        Assert.DoesNotContain("<canvas", todo, StringComparison.OrdinalIgnoreCase);
        var cases = await client.GetStringAsync("/cases");
        Assert.Contains("검색 조건 적용됨", cases, StringComparison.Ordinal);
        var detail = await client.GetStringAsync("/cases/" + id);
        Assert.Contains("시간순 이력", detail, StringComparison.Ordinal);
        Assert.Contains("이 위치의 파일은 사용자 컴퓨터의 나스카로 연다.", detail, StringComparison.Ordinal);
        var admin = access.First(line => line.StartsWith("demo-admin ", StringComparison.Ordinal)).Split(' ', 2);
        using var adminClient = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        await adminClient.PostAsync("/account/login", Form(await Token(adminClient, "/account/login"), new()
        {
            ["login"] = admin[0],
            ["password"] = admin[1]
        }));
        var manage = await adminClient.GetAsync("/admin");
        Assert.Equal(HttpStatusCode.OK, manage.StatusCode);
        Assert.Contains("관리", await manage.Content.ReadAsStringAsync(), StringComparison.Ordinal);
        var denied = await client.GetAsync("/admin");
        Assert.Equal(HttpStatusCode.OK, denied.StatusCode);
        Assert.Contains("이 화면은 관리자만 엽니다", await denied.Content.ReadAsStringAsync(), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Demo_overdue_link_is_a_real_anchor()
    {
        await using var factory = new AuditFactory { Mode = "demo" };
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var line = File.ReadAllLines(Path.Combine(factory.Root, "data", "demo-access.local"))[0].Split(' ', 2);
        await client.PostAsync("/account/login", Form(await Token(client, "/account/login"), new()
        {
            ["login"] = line[0],
            ["password"] = line[1]
        }));
        var html = await client.GetStringAsync("/todo");
        Assert.True(html.Contains("예시 업무진단", StringComparison.Ordinal), html.Length > 1800 ? html[..1800] : html);
        Assert.Matches(new Regex("href=\"/cases/[0-9a-f]{32}#action-[0-9a-f]{32}\""), html);
        Assert.DoesNotContain("예시 본문입니다", html, StringComparison.Ordinal);
    }

    static async Task<string> Token(HttpClient client, string path) => TokenOf(await client.GetStringAsync(path));

    static string TokenOf(string html)
    {
        var match = Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"");
        if (!match.Success)
            match = Regex.Match(html, "value=\"([^\"]+)\"[^>]*name=\"__RequestVerificationToken\"");
        return match.Groups[1].Value;
    }

    static string VersionOf(string html) =>
        Regex.Match(html, "name=\"version\" value=\"(\\d+)\"").Groups[1].Value;

    static async Task<string> CreateCase(HttpClient client, string newPage)
    {
        var owner = Regex.Match(newPage, "name=\"ownerId\"[\\s\\S]*?<option value=\"([0-9a-f]{32})\"").Groups[1].Value;
        var response = await client.PostAsync("/cases/new", Form(TokenOf(newPage), new()
        {
            ["requestId"] = Guid.NewGuid().ToString("N"),
            ["year"] = "2026",
            ["category"] = "업무진단",
            ["title"] = "화면 등록",
            ["target"] = "예시조직",
            ["ownerId"] = owner,
            ["shelf"] = "서가",
            ["binder"] = "철",
            ["documentNo"] = "문서",
            ["summary"] = "한줄",
            ["retentionYears"] = "3"
        }));
        Assert.Equal(HttpStatusCode.Redirect, response.StatusCode);
        return response.Headers.Location!.ToString().Split('/').Last();
    }

    static FormUrlEncodedContent Form(string token, Dictionary<string, string> fields)
    {
        fields["__RequestVerificationToken"] = token;
        return new FormUrlEncodedContent(fields);
    }
}
