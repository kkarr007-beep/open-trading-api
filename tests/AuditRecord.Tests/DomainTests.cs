using AuditRecord.Domain;
using AuditRecord.Storage;
using AuditRecord.Services;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Tests;

public class DomainTests
{
    [Fact]
    public void Leap_day_moves_to_month_end_and_expiry_is_midnight_kst()
    {
        var anchor = new DateOnly(2024, 2, 29);
        var due = Kst.AddYears(anchor, 1);
        Assert.Equal(new DateOnly(2025, 2, 28), due);
        var expiry = Kst.ExpiryInstant(anchor, 1);
        Assert.Equal(new DateTimeOffset(2025, 2, 28, 0, 0, 0, TimeSpan.FromHours(9)), expiry);
        Assert.False(Kst.IsDue(anchor, 1, expiry.AddSeconds(-1)));
        Assert.True(Kst.IsDue(anchor, 1, expiry));
        Assert.False(Kst.IsDue(new DateOnly(2022, 11, 6), 4, Kst.At(2026, 11, 5, 23, 59)));
        Assert.True(Kst.IsDue(new DateOnly(2022, 11, 6), 4, Kst.At(2026, 11, 6, 0, 0)));
    }

    [Fact]
    public void Confirm_requires_positive_retention_and_does_not_set_date_on_save()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var id = Register(h, staff, retention: null);
        var denied = h.Svc.Confirm(admin, id, 1, NewId());
        Assert.Equal(422, denied.Status);
        Assert.Equal("RETENTION_REQUIRED", denied.Code);
        var view = h.Svc.GetCase(staff, id)!;
        Assert.Null(view.FirstConfirmedOn);
        Assert.Equal("초안", view.DisplayState);
    }

    [Fact]
    public void Staff_cannot_finalize_cancel_close_hold_or_early_delete()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5);
        Assert.Equal(403, h.Svc.Confirm(staff, id, 1, NewId()).Status);
        Assert.Equal(200, h.Svc.Confirm(lead, id, 1, NewId()).Status);
        var after = h.Svc.GetCase(staff, id)!;
        Assert.Equal(403, h.Svc.CancelConfirm(staff, id, after.Version, "사유", NewId()).Status);
        Assert.Equal(403, h.Svc.CloseCase(staff, id, after.Version, true, NewId()).Status);
        Assert.Equal(403, h.Svc.SetHold(staff, id, after.Version, "사유", NewId()).Status);
        Assert.Equal(403, h.Svc.EarlyDeleteFile(lead, id, after.Version, "none", "사유", NewId()).Status);
    }

    [Fact]
    public void Reply_is_not_verification_and_close_keeps_body()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5, "남길본문");
        var confirmed = h.Svc.Confirm(lead, id, 1, NewId());
        var added = h.Svc.AddAction(staff, id, confirmed.Version!.Value, "조치", "개선요구", "예시조직", "2026-11-01", "2026-11-02", "내용", NewId());
        var replied = h.Svc.RecordReply(staff, id, added.Version!.Value, ActionId(h, id), "회신내용", NewId());
        var mid = h.Svc.GetCase(staff, id)!;
        Assert.Equal("회신수신", mid.Actions[0].State);
        Assert.Equal(422, h.Svc.CloseCase(lead, id, mid.Version, false, NewId()).Status);
        var verified = h.Svc.VerifyAction(staff, id, replied.Version!.Value, mid.Actions[0].ActionId, "확인내용", NewId());
        Assert.Equal("이행완료", h.Svc.GetCase(staff, id)!.Actions[0].State);
        var closed = h.Svc.CloseCase(lead, id, verified.Version!.Value, false, NewId());
        Assert.True(closed.Ok);
        var done = h.Svc.GetCase(staff, id)!;
        Assert.Equal("종결", done.DisplayState);
        Assert.Equal("남길본문", done.Body);
        Assert.Contains(done.History, row => row.Action == "회신" && row.Summary.Contains("이행 완료 아님", StringComparison.Ordinal));
        Assert.Contains(done.History, row => row.Action == "이행확인");
    }

    [Fact]
    public void Anchor_survives_cancel_and_staff_privileges_do_not_return()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5);
        h.Svc.Confirm(lead, id, 1, NewId());
        var version = h.Svc.GetCase(staff, id)!.Version;
        var changed = h.Svc.ChangeRetention(lead, id, version, 7, "기간조정", NewId());
        Assert.Contains(h.Svc.GetCase(staff, id)!.History, row => row.Summary.Contains("5년 → 7년", StringComparison.Ordinal) && row.Summary.Contains("기간조정", StringComparison.Ordinal));
        Assert.Equal(422, h.Svc.ChangeRetention(lead, id, changed.Version!.Value, 3, "  ", NewId()).Status);
        var cancelled = h.Svc.CancelConfirm(lead, id, changed.Version!.Value, "취소사유", NewId());
        var draft = h.Svc.GetCase(staff, id)!;
        Assert.Equal("초안", draft.Phase);
        Assert.Equal("2026-11-06", draft.FirstConfirmedOn);
        Assert.Equal(403, h.Svc.ChangeRetention(staff, id, draft.Version, 3, null, NewId()).Status);
        Assert.True(draft.EverConfirmed);
        h.Svc.Confirm(lead, id, cancelled.Version!.Value, NewId());
        var again = h.Svc.GetCase(staff, id)!;
        Assert.Equal("2026-11-06", again.FirstConfirmedOn);
        Assert.Contains(again.History, row => row.Summary.Contains("재확정", StringComparison.Ordinal));
    }

    [Fact]
    public void Expiry_deletes_bodies_and_files_but_keeps_metadata()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Ack(admin);
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 1, "지워질본문");
        var confirmed = h.Svc.Confirm(lead, id, 1, NewId());
        var added = h.Svc.AddAction(staff, id, confirmed.Version!.Value, "조치제목", "권고", "예시조직", "2026-11-01", "2026-11-02", "지워질조치", NewId());
        h.Svc.RecordReply(staff, id, added.Version!.Value, ActionId(h, id), "지워질회신", NewId());
        var version = h.Svc.GetCase(staff, id)!.Version;
        using var stream = new MemoryStream(new byte[] { 1, 2, 3 });
        h.Svc.AddFile(staff, id, version, "증빙", "예시자료", stream, NewId());
        h.Clock.Set(Kst.At(2027, 11, 5, 23, 59));
        h.Svc.ProcessDue();
        Assert.NotNull(h.Svc.GetCase(staff, id)!.Body);
        h.Clock.Set(Kst.At(2027, 11, 6, 0, 1));
        h.Svc.ProcessDue();
        var purged = h.Svc.GetCase(staff, id)!;
        Assert.Equal("자료삭제됨", purged.DisplayState);
        Assert.Null(purged.Body);
        Assert.Equal("예시 결과", purged.Summary);
        Assert.Equal("조치제목", purged.Actions[0].Title);
        Assert.Null(purged.Actions[0].Content);
        Assert.Equal("Deleted", purged.Files[0].State);
        Assert.Null(purged.Files[0].DisplayLocation);
        Assert.DoesNotContain("지워질본문", purged.History.Select(x => x.Summary));
        Assert.DoesNotContain(purged.History, row => row.Summary.Contains("지워질", StringComparison.Ordinal));
    }

    [Fact]
    public void Hold_skips_deletion_and_unhold_processes_without_extension()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Ack(admin);
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 1, "보류본문");
        var confirmed = h.Svc.Confirm(lead, id, 1, NewId());
        h.Svc.SetHold(admin, id, confirmed.Version!.Value, "검토", NewId());
        h.Clock.Set(Kst.At(2027, 11, 6, 1, 0));
        h.Svc.ProcessDue();
        Assert.Equal("보류본문", h.Svc.GetCase(staff, id)!.Body);
        var held = h.Svc.GetCase(staff, id)!;
        Assert.Equal("2026-11-06", held.FirstConfirmedOn);
        h.Svc.ReleaseHold(admin, id, held.Version, "해제", NewId());
        var after = h.Svc.GetCase(staff, id)!;
        Assert.Equal("자료삭제됨", after.DisplayState);
        Assert.Null(after.Body);
    }

    [Fact]
    public void Startup_clock_processes_downtime_and_file_failure_is_not_complete()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Ack(admin);
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 1, "잠김본문");
        var confirmed = h.Svc.Confirm(lead, id, 1, NewId());
        using var stream = new MemoryStream(new byte[] { 9 });
        var uploaded = h.Svc.AddFile(staff, id, confirmed.Version!.Value, "증빙", "잠김파일", stream, NewId());
        var fileId = h.Svc.GetCase(staff, id)!.Files[0].AttachmentId;
        h.Deleter.FailTimes(fileId, 1);
        h.Clock.Set(Kst.At(2027, 11, 6, 2, 0));
        h.Svc.ProcessDue();
        var partial = h.Svc.GetCase(staff, id)!;
        Assert.NotEqual("자료삭제됨", partial.DisplayState);
        Assert.True(partial.PurgeIncomplete);
        Assert.Null(partial.Body);
        Assert.NotEqual("Deleted", partial.Files[0].State);
        h.Clock.Advance(TimeSpan.FromMinutes(2));
        h.Svc.ProcessDue();
        var done = h.Svc.GetCase(staff, id)!;
        Assert.Equal("자료삭제됨", done.DisplayState);
        Assert.Equal("Deleted", done.Files[0].State);
        var before = done.History.Count;
        h.Svc.ProcessDue();
        Assert.Equal(before, h.Svc.GetCase(staff, id)!.History.Count);
        _ = uploaded;
    }

    [Fact]
    public void Early_delete_does_not_clear_body_or_resurrect_after_cancel()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5, "남는본문");
        var version = 1;
        using (var stream = new MemoryStream(new byte[] { 4 }))
        {
            var added = h.Svc.AddFile(staff, id, version, "증빙", "초안파일", stream, NewId());
            version = added.Version!.Value;
        }
        var fileId = h.Svc.GetCase(staff, id)!.Files[0].AttachmentId;
        var confirmed = h.Svc.Confirm(lead, id, version, NewId());
        Assert.Equal(403, h.Svc.RemoveDraftFile(staff, id, confirmed.Version!.Value, fileId, NewId()).Status);
        var removed = h.Svc.EarlyDeleteFile(admin, id, confirmed.Version!.Value, fileId, "오등록", NewId());
        Assert.True(removed.Ok, removed.Message);
        var after = h.Svc.GetCase(staff, id)!;
        Assert.Equal("남는본문", after.Body);
        Assert.Equal("Deleted", after.Files[0].State);
        Assert.NotEqual("자료삭제됨", after.DisplayState);
        h.Svc.CancelConfirm(lead, id, after.Version, "취소", NewId());
        var cancelled = h.Svc.GetCase(staff, id)!;
        Assert.Equal("Deleted", cancelled.Files[0].State);
        Assert.Null(cancelled.Files[0].DisplayLocation);
        Assert.Contains(cancelled.History, row => row.Summary.Contains("복구되지 않음", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Concurrent_updates_do_not_overwrite()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5);
        OpResult first = null!, second = null!;
        var barrier = new Barrier(2);
        var t1 = Task.Run(() => { barrier.SignalAndWait(); first = h.Svc.SaveCaseFields(staff, id, 1, 2026, "업무진단", "가안", "예시조직", staff.UserId, "서가", "철", NewId()); });
        var t2 = Task.Run(() => { barrier.SignalAndWait(); second = h.Svc.SaveCaseFields(lead, id, 1, 2026, "업무진단", "나안", "예시조직", staff.UserId, "서가", "철", NewId()); });
        await Task.WhenAll(t1, t2);
        var results = new[] { first, second };
        Assert.Equal(1, results.Count(r => r.Ok));
        Assert.Equal(1, results.Count(r => r.Status == 409));
        Assert.Equal(1, h.Svc.GetCase(staff, id)!.History.Count(row => row.Summary == "건 목록 정보 수정"));
    }

    [Fact]
    public void Search_uses_metadata_only()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        Register(h, staff, 5, "본문고유어");
        var byTitle = h.Svc.ListCases(new CaseQuery { Text = "예시 제목" });
        var byBody = h.Svc.ListCases(new CaseQuery { Text = "본문고유어" });
        var byBinder = h.Svc.ListCases(new CaseQuery { Text = "철-9" });
        Assert.Single(byTitle.Items);
        Assert.Empty(byBody.Items);
        Assert.Single(byBinder.Items);
    }

    [Fact]
    public void Backup_is_consistent_and_spares_unrelated_files()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var id = Register(h, staff, 5, "백업본문");
        using var stream = new MemoryStream(new byte[] { 7, 7 });
        h.Svc.AddFile(staff, id, 1, "증빙", "백업자료", stream, NewId());
        var stranger = Path.Combine(h.Paths.BackupAllowRoot, "keep-me");
        Directory.CreateDirectory(stranger);
        File.WriteAllText(Path.Combine(stranger, "note.txt"), "leave");
        Assert.Equal(403, h.Svc.Backup(staff, null, NewId()).Status);
        var first = h.Svc.Backup(admin, null, NewId());
        Assert.True(first.Ok, first.Message);
        var backupDir = Directory.GetDirectories(h.Paths.BackupAllowRoot, "audit-backup-*").Single();
        using (var conn = new SqliteConnection($"Data Source={Path.Combine(backupDir, "audit.db")}"))
        {
            conn.Open();
            using var check = conn.CreateCommand();
            check.CommandText = "PRAGMA integrity_check;";
            Assert.Equal("ok", check.ExecuteScalar() as string);
            using var count = conn.CreateCommand();
            count.CommandText = "SELECT COUNT(*) FROM Attachments WHERE StorageState='Present';";
            Assert.Equal(1, Convert.ToInt32(count.ExecuteScalar()));
        }
        Assert.True(File.Exists(Path.Combine(stranger, "note.txt")));
        h.Svc.UpdateBackupLimits(admin, 7, 1, NewId());
        var second = h.Svc.Backup(admin, null, NewId());
        Assert.True(second.Ok, second.Message);
        Assert.Single(Directory.GetDirectories(h.Paths.BackupAllowRoot, "audit-backup-*"));
        Assert.True(File.Exists(Path.Combine(stranger, "note.txt")));
    }

    [Fact]
    public void Tamper_of_first_middle_last_and_sole_rows_is_visible()
    {
        using var sole = new Harness();
        sole.Admin();
        TamperSummary(sole, 1);
        Assert.False(sole.Svc.IntegrityIntact());

        using var chain = new Harness();
        var admin = chain.Admin();
        var staff = chain.Account(admin, "staff1", "시험담당", "담당");
        var lead = chain.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(chain, staff, 5);
        chain.Svc.Confirm(lead, id, 1, NewId());
        var seqs = Seqs(chain);
        Assert.True(seqs.Count >= 3);
        TamperSummary(chain, seqs[0]);
        Assert.False(chain.Svc.IntegrityIntact());
    }

    [Fact]
    public void Tamper_of_middle_and_last_rows_is_visible()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 5);
        h.Svc.Confirm(lead, id, 1, NewId());
        var seqs = Seqs(h);
        TamperSummary(h, seqs[^1]);
        Assert.False(h.Svc.IntegrityIntact());
        Assert.Equal(503, h.Svc.SaveResult(lead, id, 2, "문서", "한줄", "본문", NewId()).Status);
    }

    [Fact]
    public void Middle_row_tamper_is_visible()
    {
        using var h = new Harness();
        var admin = h.Admin();
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        Register(h, staff, 5);
        var seqs = Seqs(h);
        TamperSummary(h, seqs[seqs.Count / 2]);
        Assert.False(h.Svc.IntegrityIntact());
    }

    [Fact]
    public void Full_recompute_is_outside_detection_and_tail_gap_is_inside()
    {
        using var h = new Harness();
        h.Admin();
        var seqs = Seqs(h);
        using var conn = Open(h);
        using var del = conn.CreateCommand();
        del.CommandText = "DELETE FROM History WHERE Seq=$s;";
        del.Parameters.AddWithValue("$s", seqs[^1]);
        del.ExecuteNonQuery();
        Assert.False(h.Svc.IntegrityIntact());
    }

    [Fact]
    public void Unacked_policy_does_not_purge_and_demo_seeds_two_cases()
    {
        using var prod = new Harness();
        var admin = prod.Admin();
        var staff = prod.Account(admin, "staff1", "시험담당", "담당");
        var lead = prod.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(prod, staff, 1, "운영본문");
        prod.Svc.Confirm(lead, id, 1, NewId());
        prod.Clock.Set(Kst.At(2027, 11, 6, 0, 1));
        var report = prod.Svc.ProcessDue();
        Assert.Equal("policy", report.Note);
        Assert.Equal("운영본문", prod.Svc.GetCase(staff, id)!.Body);

        using var demo = new Harness("demo");
        demo.Svc.SeedDemo();
        var list = demo.Svc.ListCases(new CaseQuery());
        Assert.Equal(2, list.Total);
        Assert.Contains(list.Items, x => x.Year == 2024 && x.Category == "업무진단" && x.Target == "예시조직");
        Assert.Contains(list.Items, x => x.Year == 2022 && x.Category == "준법감사" && x.DisplayState == "자료삭제됨");
        Assert.Equal(3, demo.Svc.ListUsers().Count(u => u.Login.StartsWith("demo-", StringComparison.Ordinal)));
    }

    [Fact]
    public void Production_has_no_demo_users()
    {
        using var h = new Harness();
        Assert.False(h.Svc.AnyUsers());
        h.Admin();
        Assert.DoesNotContain(h.Svc.ListUsers(), u => u.Login.StartsWith("demo-", StringComparison.Ordinal));
    }

    [Fact]
    public void Restore_without_ledger_hides_bodies_and_with_ledger_reapplies_deletion()
    {
        using var h = new Harness();
        var admin = h.Admin();
        h.Ack(admin);
        var staff = h.Account(admin, "staff1", "시험담당", "담당");
        var lead = h.Account(admin, "lead1", "시험파트", "파트장");
        var id = Register(h, staff, 1, "부활하면안되는문장");
        h.Svc.Confirm(lead, id, 1, NewId());
        var backup = h.Svc.Backup(admin, null, NewId());
        Assert.True(backup.Ok, backup.Message);
        var backupDb = Path.Combine(Directory.GetDirectories(h.Paths.BackupAllowRoot, "audit-backup-*").Single(), "audit.db");
        h.Clock.Set(Kst.At(2027, 11, 6, 0, 1));
        h.Svc.ProcessDue();
        Assert.Null(h.Svc.GetCase(staff, id)!.Body);
        SqliteConnection.ClearAllPools();
        foreach (var extra in new[] { h.Paths.DatabasePath, h.Paths.DatabasePath + "-wal", h.Paths.DatabasePath + "-shm" })
            if (File.Exists(extra)) File.Delete(extra);
        File.Copy(backupDb, h.Paths.DatabasePath);
        SqliteConnection.ClearAllPools();
        h.Svc.ReconcileRestore();
        Assert.True(h.Svc.RestoreLocked);
        Assert.Null(h.Svc.GetCase(staff, id)!.Body);
        var reapplied = h.Svc.ReapplyDeletions(admin, NewId());
        Assert.True(reapplied.Ok, reapplied.Message);
        Assert.False(h.Svc.RestoreLocked);
        Assert.Null(h.Svc.GetCase(staff, id)!.Body);
        Assert.Equal("자료삭제됨", h.Svc.GetCase(staff, id)!.DisplayState);

        using var locked = new Harness();
        var admin2 = locked.Admin();
        var staff2 = locked.Account(admin2, "staff1", "시험담당", "담당");
        var hiddenId = Register(locked, staff2, 5, "숨겨야할문장");
        File.WriteAllText(locked.Paths.RestoreLockPath, "restore");
        locked.Svc.ReconcileRestore();
        Assert.True(locked.Svc.RestoreLocked);
        var hidden = locked.Svc.GetCase(staff2, hiddenId)!;
        Assert.True(hidden.BodiesHidden);
        Assert.Null(hidden.Body);
        Assert.Equal(503, locked.Svc.SaveResult(admin2, hiddenId, hidden.Version, "문서", "한줄", "새본문", NewId()).Status);
    }

    [Fact]
    public void Symlink_escape_is_rejected()
    {
        using var h = new Harness();
        var store = new FileStore(h.Paths, h.Deleter);
        var outside = Path.Combine(h.Root, "outside.txt");
        File.WriteAllText(outside, "safe");
        var link = Path.Combine(h.Paths.FilesDir, "c");
        Directory.CreateDirectory(h.Paths.FilesDir);
        File.CreateSymbolicLink(link, Path.Combine(h.Root));
        var key = "c/" + new string('a', 32) + "/a/" + new string('b', 32);
        Assert.Equal(FileDeleteResult.Unsafe, store.DeleteKey(key));
        Assert.True(File.Exists(outside));
        Assert.Equal(FileDeleteResult.Unsafe, store.DeleteKey("../" + Path.GetFileName(outside)));
    }

    [Fact]
    public void Password_is_not_stored_in_plaintext_and_last_admin_stays()
    {
        using var h = new Harness();
        var admin = h.Admin();
        using var conn = Open(h);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT PasswordHash FROM Users WHERE Login='admin-user';";
        var hash = (string)cmd.ExecuteScalar()!;
        Assert.DoesNotContain(Harness.Password, hash, StringComparison.Ordinal);
        Assert.StartsWith("AQ", hash, StringComparison.Ordinal);
        var other = h.Account(admin, "admin2", "시험관리2", "관리자");
        Assert.True(h.Svc.DisableAccount(admin, other.UserId, NewId()).Ok);
        Assert.Equal(422, h.Svc.DisableAccount(other, admin.UserId, NewId()).Status);
        Assert.Equal(422, h.Svc.ChangeAccountRole(admin, admin.UserId, "담당", NewId()).Status);
    }

    static string Register(Harness h, Actor owner, int? retention, string? body = "예시 본문")
    {
        var result = h.Svc.RegisterCase(owner, 2026, "업무진단", "예시 제목", "예시조직", owner.UserId, "서가", "철-9", "예시-문서", "예시 결과", body, retention, NewId());
        if (!result.Ok || result.CaseId is null) throw new InvalidOperationException(result.Message);
        return result.CaseId;
    }

    static string ActionId(Harness h, string caseId) => h.Svc.GetCase(h.Svc.ListUsers()[0].ToActor(), caseId)!.Actions[0].ActionId;

    static string NewId() => Guid.NewGuid().ToString("N");

    static SqliteConnection Open(Harness h)
    {
        var conn = new SqliteConnection($"Data Source={h.Paths.DatabasePath}");
        conn.Open();
        return conn;
    }

    static List<long> Seqs(Harness h)
    {
        using var conn = Open(h);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "SELECT Seq FROM History ORDER BY Seq;";
        using var reader = cmd.ExecuteReader();
        var list = new List<long>();
        while (reader.Read()) list.Add(reader.GetInt64(0));
        return list;
    }

    static void TamperSummary(Harness h, long seq)
    {
        using var conn = Open(h);
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "UPDATE History SET SummarySafe=SummarySafe || '변조' WHERE Seq=$s;";
        cmd.Parameters.AddWithValue("$s", seq);
        cmd.ExecuteNonQuery();
    }
}
