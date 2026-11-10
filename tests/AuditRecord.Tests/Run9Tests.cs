using System.Net;
using AuditRecord.Domain;
using AuditRecord.Services;
using Microsoft.Extensions.DependencyInjection;

namespace AuditRecord.Tests;

public class Run9Tests
{
    [Fact]
    public void Document_numbers_are_per_year_and_category_and_are_not_reused()
    {
        Assert.Equal("2026-01-01", AuditService.FormatDocumentNo(2026, "01", 1));
        Assert.Equal("2026-01-99", AuditService.FormatDocumentNo(2026, "01", 99));
        Assert.Equal("2026-01-100", AuditService.FormatDocumentNo(2026, "01", 100));
        using var h = new Harness();
        var admin = h.Admin();
        var first = h.Svc.RegisterCase(admin, 2026, "업무진단", "첫째", "예시조직", admin.UserId, "서가", "철", "무시", "한줄", "본문", 3, Guid.NewGuid().ToString("N"));
        var second = h.Svc.RegisterCase(admin, 2026, "업무진단", "둘째", "예시조직", admin.UserId, "서가", "철", "무시", "한줄", "본문", 3, Guid.NewGuid().ToString("N"));
        var other = h.Svc.RegisterCase(admin, 2026, "준법감사", "준법", "예시조직", admin.UserId, "서가", "철", "무시", "한줄", "본문", 3, Guid.NewGuid().ToString("N"));
        Assert.Equal("2026-01-01", h.Svc.GetCase(admin, first.CaseId!)!.DocumentNo);
        Assert.Equal("2026-01-02", h.Svc.GetCase(admin, second.CaseId!)!.DocumentNo);
        Assert.Equal("2026-02-01", h.Svc.GetCase(admin, other.CaseId!)!.DocumentNo);
        Assert.Equal(422, h.Svc.SetCategoryCode(admin, "업무진단", "09", Guid.NewGuid().ToString("N")).Status);
        Assert.Equal("2026-01-03", h.Svc.PeekDocumentNo(2026, "업무진단"));
    }

    [Fact]
    public void Parallel_registration_does_not_duplicate_document_numbers()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var results = new OpResult[8];
        Parallel.For(0, 8, i =>
        {
            results[i] = h.Svc.RegisterCase(admin, 2026, "업무진단", "동시" + i, "예시조직", admin.UserId, "서가", "철", "", "한줄", "본문", 3, Guid.NewGuid().ToString("N"));
        });
        Assert.All(results, result => Assert.True(result.Ok, result.Message));
        var numbers = results.Select(result => h.Svc.GetCase(admin, result.CaseId!)!.DocumentNo).ToList();
        Assert.Equal(8, numbers.Distinct(StringComparer.Ordinal).Count());
    }

    [Fact]
    public void Case_search_includes_file_names()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var id = h.Svc.RegisterCase(admin, 2026, "업무진단", "검색건", "예시조직", admin.UserId, "서가", "철", "", "한줄", "본문고유검색어", 3, Guid.NewGuid().ToString("N")).CaseId!;
        Assert.True(h.Svc.AddFile(admin, id, 1, "증빙", "찾기쉬운이름.png", new MemoryStream([1, 2, 3, 4]), Guid.NewGuid().ToString("N")).Ok);
        var found = h.Svc.ListCases(new CaseQuery { Text = "찾기쉬운이름" });
        Assert.Contains(found.Items, item => item.CaseId == id);
        var hidden = h.Svc.ListCases(new CaseQuery { Text = "본문고유검색어" });
        Assert.DoesNotContain(hidden.Items, item => item.CaseId == id);
    }

    [Fact]
    public async Task Fetch_save_returns_json_without_leaving_the_page()
    {
        await using var factory = new AuditFactory { Mode = "demo" };
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var access = File.ReadAllLines(Path.Combine(factory.Root, "data", "demo-access.local"));
        var adminLine = access.Single(line => line.StartsWith("demo-admin ", StringComparison.Ordinal));
        var html = await client.GetStringAsync("/account/login");
        var token = System.Text.RegularExpressions.Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value;
        var login = await client.PostAsync("/account/login", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["__RequestVerificationToken"] = token,
            ["login"] = "demo-admin",
            ["password"] = adminLine["demo-admin ".Length..]
        }));
        Assert.Equal(HttpStatusCode.Redirect, login.StatusCode);
        var page = await client.GetStringAsync("/cases/new");
        var owner = System.Text.RegularExpressions.Regex.Match(page, "name=\"ownerId\"[\\s\\S]*?<option value=\"([0-9a-f]{32})\"").Groups[1].Value;
        var newToken = System.Text.RegularExpressions.Regex.Match(page, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"").Groups[1].Value;
        var request = new HttpRequestMessage(HttpMethod.Post, "/cases/new")
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["__RequestVerificationToken"] = newToken,
                ["requestId"] = Guid.NewGuid().ToString("N"),
                ["year"] = "2026",
                ["category"] = "업무진단",
                ["title"] = "화면 등록",
                ["target"] = "예시조직",
                ["ownerId"] = owner,
                ["summary"] = "한줄",
                ["retentionYears"] = "3"
            })
        };
        request.Headers.Add("X-Audit-Fetch", "1");
        var response = await client.SendAsync(request);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("application/json", response.Content.Headers.ContentType?.MediaType);
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("\"ok\":true", body, StringComparison.Ordinal);
        Assert.Contains("/cases/", body, StringComparison.Ordinal);
    }

    [Fact]
    public void Discipline_names_are_masked_and_views_are_recorded()
    {
        Assert.Equal("가*갑", AuditService.MaskPerson("가상갑"));
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff9", "시험담당", "담당");
        var lead = h.Account(admin, "lead9", "시험파트장", "파트장");
        Assert.False(h.Svc.CanReadDiscipline(staff));
        var saved = h.Svc.SaveDiscipline(lead, null, 0, null, 2026, "가상갑", "2026-01-02", "예시부서", "허위 보고", "장부를 고침", "확인을 빠뜨림", "견책", "경고", "예시 기준", Guid.NewGuid().ToString("N"));
        Assert.True(saved.Ok, saved.Message);
        var list = h.Svc.ListDiscipline(null, null);
        Assert.Contains(list, row => row.WhoMasked == "가*갑" && row.WhoName == "가상갑");
        Assert.Null(h.Svc.OpenDiscipline(staff, saved.Extra, true));
        var opened = h.Svc.OpenDiscipline(lead, saved.Extra, true);
        Assert.Equal("가상갑", opened!.WhoName);
        Assert.Contains(opened.History, row => row.Action == "열람");
        Assert.Equal("", h.Svc.ExportDisciplineCsv(staff, Guid.NewGuid().ToString("N")));
        var csv = h.Svc.ExportDisciplineCsv(admin, Guid.NewGuid().ToString("N"));
        Assert.Contains("가상갑", csv, StringComparison.Ordinal);
    }

    [Fact]
    public void Csv_exports_neutralize_formula_prefixes()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var lead = h.Account(admin, "leadcsv", "시험파트장", "파트장");
        var dangerous = new[] { "=1+1", "+cmd|calc", "-minus", "@SUM(A1)", "\tcmd", "\rline" };
        var id = h.Svc.RegisterCase(admin, 2026, "업무진단", dangerous[0], dangerous[1], admin.UserId, "서가", "철", "", "한줄", "본문", 3, Guid.NewGuid().ToString("N")).CaseId!;
        var other = h.Svc.RegisterCase(admin, 2026, "업무진단", "둘째", "예시조직", admin.UserId, dangerous[2], dangerous[3], "", "한줄", "본문", 3, Guid.NewGuid().ToString("N")).CaseId!;
        using (var conn = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={h.Paths.DatabasePath}"))
        {
            conn.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "UPDATE Cases SET Shelf=$tab, BinderNo=$cr WHERE CaseId=$id;";
            cmd.Parameters.AddWithValue("$tab", dangerous[4]);
            cmd.Parameters.AddWithValue("$cr", dangerous[5]);
            cmd.Parameters.AddWithValue("$id", id);
            Assert.Equal(1, cmd.ExecuteNonQuery());
        }
        var cases = h.Svc.ExportCasesCsv(admin, new CaseQuery(), Guid.NewGuid().ToString("N"));
        Assert.StartsWith("\uFEFF", cases, StringComparison.Ordinal);
        foreach (var value in dangerous) AssertNeutralized(cases, value);
        _ = other;

        Assert.True(h.Svc.SaveDiscipline(lead, null, 0, null, 2026, dangerous[0], "2026-01-02", dangerous[1], "내용", dangerous[3], "이유", "견책", dangerous[2], "예시 기준", Guid.NewGuid().ToString("N")).Ok);
        using (var conn = new Microsoft.Data.Sqlite.SqliteConnection($"Data Source={h.Paths.DatabasePath}"))
        {
            conn.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "UPDATE Discipline SET WhatText=$tab, WhyText=$cr WHERE WhoName=$who;";
            cmd.Parameters.AddWithValue("$tab", dangerous[4]);
            cmd.Parameters.AddWithValue("$cr", dangerous[5]);
            cmd.Parameters.AddWithValue("$who", dangerous[0]);
            Assert.Equal(1, cmd.ExecuteNonQuery());
        }
        var discipline = h.Svc.ExportDisciplineCsv(admin, Guid.NewGuid().ToString("N"));
        Assert.StartsWith("\uFEFF", discipline, StringComparison.Ordinal);
        foreach (var value in dangerous) AssertNeutralized(discipline, value);
        Assert.Contains("업무진단", cases, StringComparison.Ordinal);
        Assert.DoesNotContain("\"'업무진단\"", cases, StringComparison.Ordinal);
    }

    static void AssertNeutralized(string csv, string raw)
    {
        var quoted = "\"'" + raw.Replace("\"", "\"\"", StringComparison.Ordinal) + "\"";
        Assert.Contains(quoted, csv, StringComparison.Ordinal);
        Assert.DoesNotContain("," + raw + ",", csv, StringComparison.Ordinal);
        Assert.DoesNotContain("," + raw + "\n", csv, StringComparison.Ordinal);
    }
}
