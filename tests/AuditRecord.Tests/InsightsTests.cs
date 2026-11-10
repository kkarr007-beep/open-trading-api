using AuditRecord.Domain;

namespace AuditRecord.Tests;

/// <summary>등록번호 채번, 등록대장, 현황판 집계, CSV 내려받기.</summary>
public class InsightsTests
{
    [Fact]
    public void Record_numbers_are_assigned_per_year_and_never_change()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var first = h.Svc.RegisterCase(admin, 2026, "업무진단", "첫 건", "예시조직", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));
        var second = h.Svc.RegisterCase(admin, 2026, "준법감사", "둘째 건", "예시조직", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));
        var other = h.Svc.RegisterCase(admin, 2025, "업무진단", "작년 건", "예시조직", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));
        Assert.True(first.Ok && second.Ok && other.Ok);

        var rows = h.Svc.ListRegister(null, null);
        Assert.Equal("2026-001", rows.Single(r => r.Title == "첫 건").RecordNo);
        Assert.Equal("2026-002", rows.Single(r => r.Title == "둘째 건").RecordNo);
        Assert.Equal("2025-001", rows.Single(r => r.Title == "작년 건").RecordNo);

        // 연도를 고쳐도 등록번호는 그대로다.
        var detail = h.Svc.GetCase(admin, second.CaseId)!;
        var moved = h.Svc.SaveCaseFields(admin, second.CaseId!, detail.Version, 2025, "준법감사", "둘째 건", "예시조직", admin.UserId, "", "", Guid.NewGuid().ToString("N"));
        Assert.True(moved.Ok, moved.Message);
        Assert.Equal("2026-002", h.Svc.GetCase(admin, second.CaseId)!.RecordNo);

        // 그 다음 2026년 등록은 2026-003이다. 번호를 다시 쓰지 않는다.
        var third = h.Svc.RegisterCase(admin, 2026, "업무진단", "셋째 건", "예시조직", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));
        Assert.True(third.Ok);
        Assert.Equal("2026-003", h.Svc.GetCase(admin, third.CaseId)!.RecordNo);
    }

    [Fact]
    public void Register_history_line_contains_the_record_number()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var made = h.Svc.RegisterCase(admin, 2026, "업무진단", "이력 확인", "예시조직", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));
        var detail = h.Svc.GetCase(admin, made.CaseId)!;
        Assert.Contains(detail.History, row => row.Action == "등록" && row.Summary.Contains("2026-001", StringComparison.Ordinal));
    }

    [Fact]
    public void Register_ledger_filters_by_year_and_category()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Svc.RegisterCase(admin, 2026, "업무진단", "가", "조직1", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));
        h.Svc.RegisterCase(admin, 2026, "준법감사", "나", "조직2", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));
        h.Svc.RegisterCase(admin, 2025, "업무진단", "다", "조직3", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));

        Assert.Equal(3, h.Svc.ListRegister(null, null).Count);
        Assert.Equal(2, h.Svc.ListRegister(2026, null).Count);
        Assert.Single(h.Svc.ListRegister(2026, "준법감사"));
        Assert.Single(h.Svc.ListRegister(null, "준법감사"));
    }

    [Fact]
    public void Dashboard_counts_cases_actions_and_recent_history()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var made = h.Svc.RegisterCase(admin, 2026, "업무진단", "현황 건", "예시조직", admin.UserId, "", "", "", "한 줄", null, 5, Guid.NewGuid().ToString("N"));
        var withAction = h.Svc.AddAction(admin, made.CaseId!, 1, "조치 제목", "지적", "대상부서", "2026-11-01", "2026-11-03", null, Guid.NewGuid().ToString("N"));
        Assert.True(withAction.Ok, withAction.Message);

        var dash = h.Svc.GetDashboard();
        Assert.Equal(1, dash.TotalCases);
        Assert.Equal(1, dash.DraftCount);
        Assert.Equal(1, dash.ThisYearRegistered);
        Assert.Equal(1, dash.OpenActions);
        Assert.Equal(1, dash.OverdueActions); // 기준 시각 2026-11-06, 기한 2026-11-03
        Assert.Contains(dash.ByYear, x => x.Label == "2026" && x.Count == 1);
        Assert.Contains(dash.ByCategory, x => x.Label == "업무진단" && x.Count == 1);
        Assert.NotEmpty(dash.Recent);
        // 이력 요약에 본문은 없다.
        Assert.DoesNotContain(dash.Recent, row => row.Summary.Contains("한 줄", StringComparison.Ordinal) && row.Action != "등록");
    }

    [Fact]
    public async Task Register_page_and_csv_download_work_over_http()
    {
        await using var factory = new AuditFactory { Mode = "demo" };
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var access = File.ReadAllLines(Path.Combine(factory.Root, "data", "demo-access.local"));
        var staff = access[0].Split(' ', 2);
        var loginPage = await client.GetStringAsync("/account/login");
        await client.PostAsync("/account/login", HttpTests.Form(HttpTests.TokenOf(loginPage), new()
        {
            ["login"] = staff[0],
            ["password"] = staff[1]
        }));

        var page = await client.GetStringAsync("/register");
        Assert.Contains("기록물 등록대장", page, StringComparison.Ordinal);
        Assert.Contains("-001", page, StringComparison.Ordinal);

        var csv = await client.GetAsync("/register?handler=Csv");
        Assert.Equal(System.Net.HttpStatusCode.OK, csv.StatusCode);
        Assert.StartsWith("text/csv", csv.Content.Headers.ContentType?.MediaType, StringComparison.Ordinal);
        var bytes = await csv.Content.ReadAsByteArrayAsync();
        Assert.True(bytes.Length > 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF, "UTF-8 BOM이 있어야 Excel이 바로 연다");
        var text = System.Text.Encoding.UTF8.GetString(bytes);
        Assert.Contains("등록번호", text, StringComparison.Ordinal);

        var dash = await client.GetStringAsync("/dashboard");
        Assert.Contains("현황판", dash, StringComparison.Ordinal);
        Assert.Contains("dashboard-data", dash, StringComparison.Ordinal);
    }

    [Fact]
    public void Old_v1_database_gains_record_numbers_on_migration()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Svc.RegisterCase(admin, 2026, "업무진단", "이전 건", "예시조직", admin.UserId, "", "", "", "", null, 5, Guid.NewGuid().ToString("N"));

        // v1 상태로 되돌린 뒤 다시 초기화하면 소급 채번된다.
        var db = new AuditRecord.Data.Database(h.Paths);
        using (var conn = db.Open())
        {
            using var strip = conn.CreateCommand();
            strip.CommandText = "UPDATE Cases SET RecordNo=''; UPDATE SystemMeta SET SchemaVersion=1;";
            strip.ExecuteNonQuery();
        }
        db.Initialize("production");
        using (var conn = db.Open())
        {
            using var read = conn.CreateCommand();
            read.CommandText = "SELECT RecordNo FROM Cases;";
            Assert.Equal("2026-001", (string)read.ExecuteScalar()!);
            using var ver = conn.CreateCommand();
            ver.CommandText = "SELECT SchemaVersion FROM SystemMeta;";
            Assert.Equal(2L, (long)ver.ExecuteScalar()!);
        }
    }
}
