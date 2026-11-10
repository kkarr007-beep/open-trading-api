using System.Net;
using AuditRecord.Domain;
using AuditRecord.Services;
using AuditRecord.Storage;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.DependencyInjection;

namespace AuditRecord.Tests;

public class FreezeTests
{
    [Fact]
    public void Tamper_latches_until_admin_acknowledges_after_the_chain_is_repaired()
    {
        using var h = new Harness();
        var admin = h.Admin();
        Tamper(h);
        Assert.False(h.Svc.IntegrityIntact());
        Assert.Equal("failed", State(h));
        Assert.Equal(1, Incidents(h));
        Assert.False(h.Svc.IntegrityIntact());

        var early = h.Svc.AcknowledgeIntegrity(admin, "아직 조사 중", Guid.NewGuid().ToString("N"));
        Assert.True(early.Ok, early.Message);
        Assert.Contains("아직 맞지 않아", early.Message, StringComparison.Ordinal);
        Assert.Equal("failed", State(h));
        Assert.Contains("아직 조사 중", History(h), StringComparison.Ordinal);

        Revert(h);
        var done = h.Svc.AcknowledgeIntegrity(admin, "원문 대조 끝", Guid.NewGuid().ToString("N"));
        Assert.True(done.Ok, done.Message);
        Assert.Contains("해제했습니다", done.Message, StringComparison.Ordinal);
        Assert.Equal("ok", State(h));
        Assert.True(h.Svc.IntegrityIntact());
        Assert.Contains("원문 대조 끝", History(h), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Frozen_page_hides_writes_and_does_not_answer_503()
    {
        await using var factory = new AuditFactory();
        var svc = factory.Services.GetRequiredService<AuditService>();
        var admin = svc.SetupAdmin("admin-user", "시험관리", Harness.Password);
        var actor = svc.FindUser(admin.UserId!)!.ToActor();
        var id = svc.RegisterCase(actor, 2026, "업무진단", "예시 제목", "예시조직", actor.UserId, "서가", "철", "문서", "한줄", "본문", 5, Guid.NewGuid().ToString("N")).CaseId!;
        using (var conn = Open(factory.Root))
        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText = "UPDATE History SET SummarySafe=SummarySafe || '변조' WHERE Seq=1;";
            cmd.ExecuteNonQuery();
        }
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        await client.PostAsync("/account/login", Form(await Token(client), new()
        {
            ["login"] = "admin-user",
            ["password"] = Harness.Password
        }));
        var page = await client.GetStringAsync("/cases/" + id);
        Assert.Contains("frozen-integrity", page, StringComparison.Ordinal);
        Assert.Contains("변경 입력은 숨겼습니다", page, StringComparison.Ordinal);
        var posted = await client.PostAsync("/cases/" + id + "?handler=SaveResult", new FormUrlEncodedContent(new Dictionary<string, string>
        {
            ["version"] = "1",
            ["documentNo"] = "x",
            ["summary"] = "y",
            ["body"] = "z",
            ["requestId"] = Guid.NewGuid().ToString("N")
        }));
        Assert.Equal(HttpStatusCode.ServiceUnavailable, posted.StatusCode);
        Assert.Contains("읽기 전용", await posted.Content.ReadAsStringAsync(), StringComparison.Ordinal);
    }

    [Fact]
    public void Run_due_now_deletes_only_expired_unheld_cases_and_records_history()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Ack(admin);
        var staff = Account(h, admin, "staff1", "시험담당", "담당");
        var lead = Account(h, admin, "lead1", "시험파트", "파트장");
        var waiting = Register(h, staff, 5, "아직본문");
        var due = Register(h, staff, 1, "지워야할본문");
        var held = Register(h, staff, 1, "보류본문");
        h.Svc.Confirm(lead, waiting, 1, Guid.NewGuid().ToString("N"));
        h.Svc.Confirm(lead, due, 1, Guid.NewGuid().ToString("N"));
        var heldVersion = h.Svc.Confirm(lead, held, 1, Guid.NewGuid().ToString("N")).Version!.Value;
        h.Svc.SetHold(admin, held, heldVersion, "보류사유", Guid.NewGuid().ToString("N"));

        var early = h.Svc.RunDueNow(admin, Guid.NewGuid().ToString("N"));
        Assert.True(early.Ok, early.Message);
        Assert.Equal("아직본문", Body(h, waiting));
        Assert.Equal("지워야할본문", Body(h, due));
        Assert.Contains("만료 삭제 지금 실행", History(h), StringComparison.Ordinal);

        h.Clock.Set(Kst.At(2027, 11, 6, 0, 1));
        var ran = h.Svc.RunDueNow(admin, Guid.NewGuid().ToString("N"));
        Assert.True(ran.Ok, ran.Message);
        Assert.Equal("아직본문", Body(h, waiting));
        Assert.Null(Body(h, due));
        Assert.Equal("보류본문", Body(h, held));
    }

    [Fact]
    public void Parallel_run_now_and_writes_do_not_false_latch()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Ack(admin);
        var staff = Account(h, admin, "staff1", "시험담당", "담당");
        var lead = Account(h, admin, "lead1", "시험파트", "파트장");
        var ids = new string[6];
        for (var i = 0; i < ids.Length; i++)
        {
            ids[i] = Register(h, staff, 1, "본문" + i);
            Assert.True(h.Svc.Confirm(lead, ids[i], 1, Guid.NewGuid().ToString("N")).Ok);
        }
        h.Clock.Set(Kst.At(2027, 11, 6, 0, 1));
        var messages = new System.Collections.Concurrent.ConcurrentBag<string>();
        var errors = new System.Collections.Concurrent.ConcurrentBag<string>();
        var barrier = new Barrier(12);
        Parallel.For(0, 12, i =>
        {
            try
            {
                barrier.SignalAndWait();
                if (i < 8)
                {
                    var result = h.Svc.RunDueNow(admin, Guid.NewGuid().ToString("N"));
                    messages.Add(result.Message);
                }
                else
                {
                    var id = ids[i - 8];
                    var version = h.Svc.GetCase(lead, id)!.Version;
                    var result = h.Svc.SaveResult(lead, id, version, "문서", "한줄", "겹친본문", Guid.NewGuid().ToString("N"));
                    messages.Add(result.Code + result.Message);
                }
            }
            catch (Exception ex)
            {
                errors.Add(ex.GetType().Name + " " + ex.Message);
            }
        });
        Assert.Empty(errors);
        Assert.Contains(messages, message => message.Contains("이미 실행 중", StringComparison.Ordinal));
        Assert.True(h.Svc.IntegrityIntact());
        Assert.Equal("ok", State(h));
        Assert.Equal(0, Incidents(h));
    }

    [Fact]
    public void Pending_delete_records_attempt_and_shows_waiting_badge()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = Account(h, admin, "staff1", "시험담당", "담당");
        var lead = Account(h, admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5, "대기본문");
        using var stream = new MemoryStream(new byte[] { 3, 3 });
        var added = h.Svc.AddFile(staff, id, 1, "증빙", "대기자료", stream, Guid.NewGuid().ToString("N"));
        var version = h.Svc.Confirm(lead, id, added.Version ?? 2, Guid.NewGuid().ToString("N")).Version ?? 3;
        var file = Directory.EnumerateFiles(h.Paths.FilesDir, "*", SearchOption.AllDirectories).Single();
        h.Deleter.FailTimes(Path.GetFileName(file), 1);
        var attachment = h.Svc.GetCase(admin, id)!.Files[0].AttachmentId;
        var failed = h.Svc.EarlyDeleteFile(admin, id, version, attachment, "대기사유", Guid.NewGuid().ToString("N"));
        Assert.Equal(503, failed.Status);
        var detail = h.Svc.GetCase(admin, id)!;
        Assert.True(detail.DeletionQueued);
        Assert.True(detail.DeletionAttempts >= 1);
        Assert.False(string.IsNullOrEmpty(detail.DeletionError));
    }

    [Fact]
    public void Demo_clock_offset_is_refused_in_production_and_shifts_demo_time()
    {
        var denied = Assert.Throws<StartupFailure>(() => StartupChecks.DemoClockOffsetDays("production", "30"));
        Assert.Contains("예시 모드에서만", denied.Message, StringComparison.Ordinal);
        Assert.Equal(0, StartupChecks.DemoClockOffsetDays("production", "0"));
        Assert.Equal(30, StartupChecks.DemoClockOffsetDays("demo", "30"));
        var start = new DateTimeOffset(2026, 11, 6, 0, 0, 0, TimeSpan.Zero);
        var clock = new OffsetClock(new ManualClock(start), TimeSpan.FromDays(30));
        Assert.Equal(start.AddDays(30), clock.UtcNow);
    }

    static void Tamper(Harness h)
    {
        using var conn = Open(h.Root);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE History SET SummarySafe=SummarySafe || '변조' WHERE Seq=1;";
        cmd.ExecuteNonQuery();
    }

    static void Revert(Harness h)
    {
        using var conn = Open(h.Root);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE History SET SummarySafe=replace(SummarySafe, '변조', '') WHERE Seq=1;";
        cmd.ExecuteNonQuery();
    }

    static string State(Harness h)
    {
        using var conn = Open(h.Root);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT IntegrityState FROM SystemMeta WHERE Id=1;";
        return (string)cmd.ExecuteScalar()!;
    }

    static int Incidents(Harness h)
    {
        using var conn = Open(h.Root);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM IntegrityIncidents;";
        return Convert.ToInt32(cmd.ExecuteScalar());
    }

    static string? Body(Harness h, string caseId)
    {
        using var conn = Open(h.Root);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT Body FROM Results WHERE CaseId=$id;";
        cmd.Parameters.AddWithValue("$id", caseId);
        return cmd.ExecuteScalar() as string;
    }

    static string History(Harness h)
    {
        using var conn = Open(h.Root);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT SummarySafe FROM History ORDER BY Seq;";
        using var reader = cmd.ExecuteReader();
        var lines = new List<string>();
        while (reader.Read()) lines.Add(reader.GetString(0));
        return string.Join("\n", lines);
    }

    static SqliteConnection Open(string root)
    {
        var conn = new SqliteConnection(new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(root, "data", "audit.db"),
            Pooling = false
        }.ToString());
        conn.Open();
        return conn;
    }

    static string Register(Harness h, Actor owner, int years, string body)
    {
        var result = h.Svc.RegisterCase(owner, 2026, "업무진단", "예시 제목", "예시조직", owner.UserId, "서가", "철", "문서", "한줄", body, years, Guid.NewGuid().ToString("N"));
        return result.CaseId!;
    }

    static Actor Account(Harness h, Actor admin, string login, string name, string role)
    {
        var created = h.Svc.CreateAccount(admin, login, name, role, Guid.NewGuid().ToString("N"));
        return h.Svc.FindUser(created.UserId!)!.ToActor();
    }

    static async Task<string> Token(HttpClient client)
    {
        var html = await client.GetStringAsync("/account/login");
        var match = System.Text.RegularExpressions.Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"");
        return match.Groups[1].Value;
    }

    static FormUrlEncodedContent Form(string token, Dictionary<string, string> fields)
    {
        fields["__RequestVerificationToken"] = token;
        return new FormUrlEncodedContent(fields);
    }
}
