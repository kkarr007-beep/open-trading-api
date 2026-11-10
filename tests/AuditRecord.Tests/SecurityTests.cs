using AuditRecord.Domain;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Tests;

public class SecurityTests
{
    [Fact]
    public void Backup_expire_restore_without_lock_stays_readonly_until_admin_reapplies()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Ack(admin);
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 1, "부활하면안되는문장");
        using var stream = new MemoryStream(new byte[] { 9, 9, 9 });
        var added = h.Svc.AddFile(staff, id, 1, "증빙", "복구자료", stream, NewId());
        Assert.True(added.Ok, added.Message);
        var version = added.Version ?? 2;
        var confirmed = h.Svc.Confirm(lead, id, version, NewId());
        Assert.True(confirmed.Ok, confirmed.Message);
        var backup = h.Svc.Backup(admin, null, NewId());
        Assert.True(backup.Ok, backup.Message);
        var backupDir = Directory.GetDirectories(h.Paths.BackupAllowRoot, "audit-backup-*").Single();
        h.Clock.Set(Kst.At(2027, 11, 6, 0, 1));
        var purged = h.Svc.ProcessDue();
        Assert.Equal("ran", purged.Note);
        Assert.Null(ReadBody(h, id));
        Assert.False(File.Exists(h.Paths.RestoreLockPath));

        SqliteConnection.ClearAllPools();
        foreach (var extra in new[] { h.Paths.DatabasePath, h.Paths.DatabasePath + "-wal", h.Paths.DatabasePath + "-shm" })
            if (File.Exists(extra)) File.Delete(extra);
        File.Copy(Path.Combine(backupDir, "audit.db"), h.Paths.DatabasePath);
        CopyTree(Path.Combine(backupDir, "files"), h.Paths.FilesDir);
        SqliteConnection.ClearAllPools();
        Assert.Equal("부활하면안되는문장", ReadBody(h, id));
        h.Svc.ReconcileRestore();

        Assert.True(h.Svc.RestoreLocked);
        Assert.Null(h.Svc.GetCase(staff, id)!.Body);
        Assert.Equal(503, h.Svc.SaveResult(lead, id, 1, "문서", "한줄", "새본문", NewId()).Status);
        Assert.Equal("부활하면안되는문장", ReadBody(h, id));
        Assert.True(Directory.EnumerateFiles(h.Paths.FilesDir, "*", SearchOption.AllDirectories).Any());

        var reapplied = h.Svc.ReapplyDeletions(admin, NewId());
        Assert.True(reapplied.Ok, reapplied.Message);
        Assert.False(h.Svc.RestoreLocked);
        Assert.Null(ReadBody(h, id));
        Assert.Empty(Directory.EnumerateFiles(h.Paths.FilesDir, "*", SearchOption.AllDirectories));
        var history = string.Join("\n", h.Svc.GetCase(admin, id)!.History.Select(row => row.Summary));
        Assert.Contains("복구 대조", history, StringComparison.Ordinal);
        Assert.Contains("관리자가 삭제 기록에 따라 삭제를 다시 적용", HistoryText(h), StringComparison.Ordinal);
    }

    [Fact]
    public void Reason_is_stored_in_history_for_cancel_retention_early_delete_and_hold_release()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5, "사유본문");
        using var stream = new MemoryStream(new byte[] { 1, 2 });
        var added = h.Svc.AddFile(staff, id, 1, "증빙", "사유자료", stream, NewId());
        var version = added.Version ?? 2;
        version = h.Svc.Confirm(lead, id, version, NewId()).Version ?? version + 1;
        version = h.Svc.ChangeRetention(lead, id, version, 6, "연한사유표시", NewId()).Version ?? version + 1;
        var attachment = h.Svc.GetCase(admin, id)!.Files[0].AttachmentId;
        version = h.Svc.EarlyDeleteFile(admin, id, version, attachment, "조기삭제사유표시", NewId()).Version ?? version + 1;
        version = h.Svc.SetHold(admin, id, version, "보류사유표시", NewId()).Version ?? version + 1;
        version = h.Svc.ReleaseHold(admin, id, version, "해제사유표시", NewId()).Version ?? version + 1;
        h.Svc.CancelConfirm(lead, id, version, "취소사유표시", NewId());
        var text = string.Join("\n", h.Svc.GetCase(admin, id)!.History.Select(row => row.Summary));
        Assert.Contains("연한사유표시", text, StringComparison.Ordinal);
        Assert.Contains("조기삭제사유표시", text, StringComparison.Ordinal);
        Assert.Contains("보류사유표시", text, StringComparison.Ordinal);
        Assert.Contains("해제사유표시", text, StringComparison.Ordinal);
        Assert.Contains("취소사유표시", text, StringComparison.Ordinal);
    }

    [Fact]
    public void Delete_jobs_are_unique_visible_and_complete_when_target_is_already_gone()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5, "작업본문");
        using var stream = new MemoryStream(new byte[] { 4, 4 });
        var added = h.Svc.AddFile(staff, id, 1, "증빙", "작업자료", stream, NewId());
        var version = h.Svc.Confirm(lead, id, added.Version ?? 2, NewId()).Version ?? 3;
        var attachment = h.Svc.GetCase(admin, id)!.Files[0].AttachmentId;
        var file = Directory.EnumerateFiles(h.Paths.FilesDir, "*", SearchOption.AllDirectories).Single();
        h.Deleter.FailTimes(Path.GetFileName(file), 1);
        var first = h.Svc.EarlyDeleteFile(admin, id, version, attachment, "첫번째사유", NewId());
        Assert.Equal(503, first.Status);
        Assert.Single(h.Svc.RecentDeleteJobs(), job => job.Status != "Complete");
        Assert.Contains(h.Svc.RecentDeleteJobs(), job => job.Status == "RetryWait" && job.Title == "예시 제목");

        var current = h.Svc.GetCase(admin, id)!.Version;
        var second = h.Svc.EarlyDeleteFile(admin, id, current, attachment, "두번째사유", NewId());
        Assert.True(second.Ok, second.Message);
        Assert.Equal(1, CountJobs(h, id));
        Assert.DoesNotContain(h.Svc.RecentDeleteJobs(), job => job.Status is "Pending" or "RetryWait");

        var other = Register(h, staff, 5, "없는파일");
        using var empty = new MemoryStream(new byte[] { 8 });
        var otherAdd = h.Svc.AddFile(staff, other, 1, "증빙", "사라질자료", empty, NewId());
        var otherVersion = h.Svc.Confirm(lead, other, otherAdd.Version ?? 2, NewId()).Version ?? 3;
        var otherAttachment = h.Svc.GetCase(admin, other)!.Files[0].AttachmentId;
        foreach (var path in Directory.EnumerateFiles(h.Paths.FilesDir, "*", SearchOption.AllDirectories))
            File.Delete(path);
        var gone = h.Svc.EarlyDeleteFile(admin, other, otherVersion, otherAttachment, "없는파일사유", NewId());
        Assert.True(gone.Ok, gone.Message);
        Assert.Contains("이미 없음 확인. 사유: 없는파일사유", string.Join("\n", h.Svc.GetCase(admin, other)!.History.Select(row => row.Summary)), StringComparison.Ordinal);
        Assert.DoesNotContain(h.Svc.RecentDeleteJobs().Where(job => job.CaseId == other), job => job.Status != "Complete");
    }

    [Fact]
    public void Long_backup_subfolder_is_a_friendly_error()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var result = h.Svc.Backup(admin, new string('가', 300), NewId());
        Assert.Equal(422, result.Status);
        Assert.Contains("40자", result.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("Exception", result.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Tamper_updates_system_meta_integrity_state()
    {
        using var h = new Harness();
        h.Admin();
        Assert.Equal("ok", MetaState(h));
        Assert.True(h.Svc.GetState().IntegrityOk);
        using (var conn = Open(h))
        using (var cmd = conn.CreateCommand())
        {
            cmd.CommandText = "UPDATE History SET SummarySafe=SummarySafe || '변조' WHERE Seq=1;";
            cmd.ExecuteNonQuery();
        }
        Assert.False(h.Svc.IntegrityIntact());
        Assert.Equal("failed", MetaState(h));
        Assert.False(h.Svc.GetState().IntegrityOk);
    }

    [Fact]
    public void Backup_verification_reports_integrity_without_restoring()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var id = Register(h, staff, 5, "검사본문");
        var backup = h.Svc.Backup(admin, null, NewId());
        Assert.True(backup.Ok, backup.Message);
        var check = h.Svc.VerifyBackup(admin, backup.Extra, NewId());
        Assert.True(check.Ok, check.Message);
        Assert.Contains("이상 없음", check.Message, StringComparison.Ordinal);
        Assert.Equal("검사본문", h.Svc.GetCase(staff, id)!.Body);
        Assert.Contains("백업 무결성 확인: 이상 없음", HistoryText(h), StringComparison.Ordinal);

        var db = Path.Combine(Directory.GetDirectories(h.Paths.BackupAllowRoot, "audit-backup-*").Single(), "audit.db");
        using (var stream = new FileStream(db, FileMode.Open, FileAccess.Write))
        {
            stream.Position = 0;
            stream.Write(new byte[] { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        }
        var broken = h.Svc.VerifyBackup(admin, backup.Extra, NewId());
        Assert.Equal(422, broken.Status);
        Assert.Contains("되돌리지 마세요", broken.Message, StringComparison.Ordinal);
        Assert.Equal("검사본문", h.Svc.GetCase(staff, id)!.Body);
    }

    [Fact]
    public void Backup_verification_rejects_tampered_file_database_and_history()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var id = Register(h, staff, 5, "봉인본문");
        using var stream = new MemoryStream(new byte[] { 9, 1, 2, 3 });
        Assert.True(h.Svc.AddFile(staff, id, 1, "증빙", "봉인자료", stream, NewId()).Ok);
        var backup = h.Svc.Backup(admin, null, NewId());
        Assert.True(backup.Ok, backup.Message);
        var dir = Directory.GetDirectories(h.Paths.BackupAllowRoot, "audit-backup-*").Single();

        var attachment = Directory.EnumerateFiles(Path.Combine(dir, "files"), "*", SearchOption.AllDirectories).Single();
        var bytes = File.ReadAllBytes(attachment);
        bytes[0] ^= 0x5a;
        File.WriteAllBytes(attachment, bytes);
        var fileTamper = h.Svc.VerifyBackup(admin, backup.Extra, NewId());
        Assert.Equal(422, fileTamper.Status);
        Assert.Contains("파일 해시", fileTamper.Message, StringComparison.Ordinal);
        bytes[0] ^= 0x5a;
        File.WriteAllBytes(attachment, bytes);

        using (var conn = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = Path.Combine(dir, "audit.db"), Pooling = false }.ToString()))
        {
            conn.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "UPDATE Cases SET Title=Title || '변조';";
            cmd.ExecuteNonQuery();
        }
        var dbTamper = h.Svc.VerifyBackup(admin, backup.Extra, NewId());
        Assert.Equal(422, dbTamper.Status);
        Assert.Contains("파일 해시", dbTamper.Message, StringComparison.Ordinal);

        using (var conn = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = Path.Combine(dir, "audit.db"), Pooling = false }.ToString()))
        {
            conn.Open();
            using var undo = conn.CreateCommand();
            undo.CommandText = "UPDATE Cases SET Title=replace(Title, '변조', '');";
            undo.ExecuteNonQuery();
            using var hist = conn.CreateCommand();
            hist.CommandText = "UPDATE History SET SummarySafe=SummarySafe || '이력변조' WHERE Seq=1;";
            hist.ExecuteNonQuery();
        }
        var historyTamper = h.Svc.VerifyBackup(admin, backup.Extra, NewId());
        Assert.Equal(422, historyTamper.Status);
        Assert.Contains("이력 체인", historyTamper.Message, StringComparison.Ordinal);
    }

    static string Register(Harness h, Actor owner, int? retention, string? body)
    {
        var result = h.Svc.RegisterCase(owner, 2026, "업무진단", "예시 제목", "예시조직", owner.UserId, "서가", "철-9", "예시-문서", "예시 결과", body, retention, NewId());
        if (!result.Ok || result.CaseId is null) throw new InvalidOperationException(result.Message);
        return result.CaseId;
    }

    static string NewId() => Guid.NewGuid().ToString("N");

    static SqliteConnection Open(Harness h)
    {
        var conn = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = h.Paths.DatabasePath, Pooling = false }.ToString());
        conn.Open();
        return conn;
    }

    static string? ReadBody(Harness h, string caseId)
    {
        using var conn = Open(h);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT Body FROM Results WHERE CaseId=$id;";
        cmd.Parameters.AddWithValue("$id", caseId);
        return cmd.ExecuteScalar() as string;
    }

    static string MetaState(Harness h)
    {
        using var conn = Open(h);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT IntegrityState FROM SystemMeta WHERE Id=1;";
        return (string)cmd.ExecuteScalar()!;
    }

    static int CountJobs(Harness h, string caseId)
    {
        using var conn = Open(h);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM DeleteJobs WHERE CaseId=$id;";
        cmd.Parameters.AddWithValue("$id", caseId);
        return Convert.ToInt32(cmd.ExecuteScalar());
    }

    static string HistoryText(Harness h)
    {
        using var conn = Open(h);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT SummarySafe FROM History ORDER BY Seq;";
        using var reader = cmd.ExecuteReader();
        var lines = new List<string>();
        while (reader.Read()) lines.Add(reader.GetString(0));
        return string.Join("\n", lines);
    }

    static void CopyTree(string source, string destination)
    {
        if (!Directory.Exists(source)) return;
        foreach (var file in Directory.EnumerateFiles(source, "*", SearchOption.AllDirectories))
        {
            var relative = Path.GetRelativePath(source, file);
            var target = Path.Combine(destination, relative);
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(file, target, true);
        }
    }
}
