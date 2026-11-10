using System.Net;
using System.Text.RegularExpressions;
using AuditRecord.Domain;
using AuditRecord.Services;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;

namespace AuditRecord.Tests;

public class FlowTests
{
    [Fact]
    public async Task Staff_admin_page_is_a_friendly_403()
    {
        await using var factory = new AuditFactory { Mode = "demo" };
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var staff = File.ReadAllLines(Path.Combine(factory.Root, "data", "demo-access.local"))[0].Split(' ', 2);
        await client.PostAsync("/account/login", Form(await Token(client, "/account/login"), new()
        {
            ["login"] = staff[0],
            ["password"] = staff[1]
        }));
        var response = await client.GetAsync("/admin");
        var html = await response.Content.ReadAsStringAsync();
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains("이 화면은 관리자만 엽니다", html, StringComparison.Ordinal);
        Assert.Contains("할 일로 돌아가기", html, StringComparison.Ordinal);
        Assert.Contains("<main", html, StringComparison.Ordinal);
        Assert.Contains("<header", html, StringComparison.Ordinal);
        Assert.DoesNotContain("계정 만들기", html, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Role_names_and_early_delete_ids_and_backup_table_are_contained()
    {
        await using var factory = new AuditFactory { Mode = "demo" };
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var adminLine = File.ReadAllLines(Path.Combine(factory.Root, "data", "demo-access.local"))
            .First(line => line.StartsWith("demo-admin ", StringComparison.Ordinal)).Split(' ', 2);
        await client.PostAsync("/account/login", Form(await Token(client, "/account/login"), new()
        {
            ["login"] = adminLine[0],
            ["password"] = adminLine[1]
        }));
        var svc = factory.Services.GetRequiredService<AuditService>();
        var actor = svc.ListUsers().Single(user => user.Login == "demo-admin").ToActor();
        Assert.True(svc.Backup(actor, null, Guid.NewGuid().ToString("N")).Ok);
        var admin = await client.GetStringAsync("/admin");
        Assert.Contains("aria-label=\"예시관리자 역할\"", admin, StringComparison.Ordinal);
        Assert.Contains("aria-label=\"예시담당 역할\"", admin, StringComparison.Ordinal);
        Assert.Contains("aria-label=\"예시파트장 역할\"", admin, StringComparison.Ordinal);
        Assert.Contains("aria-label=\"새 계정의 역할\"", admin, StringComparison.Ordinal);
        Assert.Contains("최근 백업 표 가로 이동", admin, StringComparison.Ordinal);
        var main = admin.IndexOf("<main", StringComparison.Ordinal);
        var banner = admin.IndexOf("class=\"banner", StringComparison.Ordinal);
        var mainEnd = admin.IndexOf("</main>", StringComparison.Ordinal);
        Assert.True(main >= 0 && banner > main && mainEnd > banner);

        var id = svc.ListCases(new CaseQuery()).Items.Single(item => item.Year == 2024).CaseId;
        var detailBefore = svc.GetCase(actor, id)!;
        using var one = new MemoryStream(new byte[] { 1 });
        using var two = new MemoryStream(new byte[] { 2 });
        var first = svc.AddFile(actor, id, detailBefore.Version, "증빙", "자료-가", one, Guid.NewGuid().ToString("N"));
        var second = svc.AddFile(actor, id, first.Version!.Value, "증빙", "자료-나", two, Guid.NewGuid().ToString("N"));
        Assert.True(second.Ok, second.Message);
        var html = await client.GetStringAsync("/cases/" + id);
        var ids = Regex.Matches(html, "id=\"(btn-early-delete[^\"]*)\"").Select(match => match.Groups[1].Value).ToList();
        Assert.True(ids.Count >= 2, html.Contains("btn-early-delete", StringComparison.Ordinal) ? string.Join(",", ids) : "missing");
        Assert.Equal(ids.Count, ids.Distinct(StringComparer.Ordinal).Count());
        Assert.DoesNotContain(ids, value => value == "btn-early-delete");
    }

    [Fact]
    public async Task Expiry_after_retention_is_shown_on_the_case_page()
    {
        await using var factory = new AuditFactory();
        var svc = factory.Services.GetRequiredService<AuditService>();
        var admin = MakeAdmin(svc);
        svc.AcknowledgePolicy(admin, Guid.NewGuid().ToString("N"));
        var staff = Account(svc, admin, "staff1", "시험담당", "담당");
        var lead = Account(svc, admin, "lead1", "시험파트", "파트장");
        var id = Register(svc, staff, 1, "만료되면사라질문장");
        Assert.True(svc.Confirm(lead, id, 1, Guid.NewGuid().ToString("N")).Ok);
        factory.Clock.Set(Kst.At(2027, 11, 6, 0, 1));
        var report = svc.ProcessDue();
        Assert.Equal("ran", report.Note);

        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        await client.PostAsync("/account/login", Form(await Token(client, "/account/login"), new()
        {
            ["login"] = "staff1",
            ["password"] = Harness.Password
        }));
        var html = await client.GetStringAsync("/cases/" + id);
        Assert.Contains("자료삭제됨", html, StringComparison.Ordinal);
        Assert.DoesNotContain("만료되면사라질문장", html, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Tamper_shows_the_history_banner()
    {
        await using var factory = new AuditFactory();
        var svc = factory.Services.GetRequiredService<AuditService>();
        MakeAdmin(svc);
        using (var conn = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(factory.Root, "data", "audit.db"),
            Pooling = false
        }.ToString()))
        {
            conn.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "UPDATE History SET SummarySafe=SummarySafe || '변조' WHERE Seq=1;";
            cmd.ExecuteNonQuery();
        }
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        await client.PostAsync("/account/login", Form(await Token(client, "/account/login"), new()
        {
            ["login"] = "admin-user",
            ["password"] = Harness.Password
        }));
        var html = await client.GetStringAsync("/todo");
        Assert.Contains("이력 훼손", html, StringComparison.Ordinal);
        Assert.Contains("변경 입력은 숨겼습니다", html, StringComparison.Ordinal);
        var main = html.IndexOf("<main", StringComparison.Ordinal);
        var banner = html.IndexOf("이력 훼손", StringComparison.Ordinal);
        var mainEnd = html.IndexOf("</main>", StringComparison.Ordinal);
        Assert.True(banner > main && banner < mainEnd);
    }

    static Actor MakeAdmin(AuditService svc)
    {
        var result = svc.SetupAdmin("admin-user", "시험관리", Harness.Password);
        return svc.FindUser(result.UserId!)!.ToActor();
    }

    static Actor Account(AuditService svc, Actor admin, string login, string name, string role)
    {
        var result = svc.CreateAccount(admin, login, name, role, Guid.NewGuid().ToString("N"));
        var actor = svc.FindUser(result.UserId!)!.ToActor();
        svc.ChangePassword(actor, result.TakeIssuedPassword(), Harness.Password, Guid.NewGuid().ToString("N"));
        return actor;
    }

    static string Register(AuditService svc, Actor owner, int retention, string body)
    {
        var result = svc.RegisterCase(owner, 2026, "업무진단", "예시 제목", "예시조직", owner.UserId, "서가", "철-9", "예시-문서", "예시 결과", body, retention, Guid.NewGuid().ToString("N"));
        return result.CaseId!;
    }

    static async Task<string> Token(HttpClient client, string path) => TokenOf(await client.GetStringAsync(path));

    static string TokenOf(string html)
    {
        var match = Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"");
        if (!match.Success)
            match = Regex.Match(html, "value=\"([^\"]+)\"[^>]*name=\"__RequestVerificationToken\"");
        return match.Groups[1].Value;
    }

    static FormUrlEncodedContent Form(string token, Dictionary<string, string> fields)
    {
        fields["__RequestVerificationToken"] = token;
        return new FormUrlEncodedContent(fields);
    }
}
