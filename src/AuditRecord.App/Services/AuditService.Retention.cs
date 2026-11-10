using System.Globalization;
using System.Text.Json;
using AuditRecord.Data;
using AuditRecord.Domain;
using AuditRecord.Storage;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Services;

public sealed partial class AuditService
{
    public ProcessReport ProcessDue()
    {
        if (Interlocked.CompareExchange(ref _dueFlag, 1, 0) != 0)
            return new ProcessReport { Note = "busy" };
        try
        {
            return ProcessDue(Now);
        }
        finally
        {
            Interlocked.Exchange(ref _dueFlag, 0);
        }
    }

    ProcessReport ProcessDue(DateTimeOffset now)
    {
        if (RestoreLocked) return new ProcessReport { Note = "restore", Skipped = 1 };
        if (!IntegrityIntact()) return new ProcessReport { Note = "integrity", Skipped = 1 };
        if (!GetState().PolicyAcknowledged) return new ProcessReport { Note = "policy", Skipped = 1 };
        SettleAbsentJobs(now);
        var enqueued = EnqueueDue(now);
        var (completed, waiting) = RunJobs(now);
        try { _db.Checkpoint(false); } catch (SqliteException) { }
        return new ProcessReport { Enqueued = enqueued, Completed = completed, Waiting = waiting, Note = "ran" };
    }

    public void ReconcileRestore()
    {
        try
        {
            if (!TryReadLedger(out var events, out var ledgerError))
            {
                LockRestore(ledgerError);
                return;
            }
            var lockFile = File.Exists(_paths.RestoreLockPath);
            if (lockFile && events.Count == 0)
            {
                LockRestore("최신 삭제·보류 기록을 확인할 수 없어 일반 사용을 열지 않습니다. 본문과 자료 위치는 표시하지 않습니다.");
                return;
            }
            if (DeletionMismatch(events))
            {
                LockRestore("삭제 기록과 현재 데이터베이스가 다릅니다. 읽기 전용입니다. 관리자가 삭제를 다시 적용하기 전에는 본문과 자료 위치를 보여 주지 않고 변경을 받지 않습니다.");
                return;
            }
            if (lockFile)
            {
                LockRestore("복구 잠금이 있습니다. 관리자가 대조를 확인하기 전에는 변경할 수 없습니다. 본문과 자료 위치는 표시하지 않습니다.");
                return;
            }
            Volatile.Write(ref _restoreLocked, 0);
            _restoreMessage = "";
        }
        catch (Exception ex)
        {
            _log.LogWarning("reconcile {Type}", ex.GetType().Name);
            LockRestore("복구 대조를 마치지 못했습니다. 본문과 자료 위치는 표시하지 않습니다.");
        }
    }

    public OpResult ReapplyDeletions(Actor actor, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!RestoreLocked) return OpResult.Success("대조할 차이가 없습니다.");
        if (!IntegrityIntact()) return OpResult.Fail(503, "INTEGRITY_FAILED", Msg.Integrity);
        if (!TryReadLedger(out var events, out var ledgerError))
            return OpResult.Fail(503, "RESTORE_LOCKED", ledgerError);
        if (events.Count == 0)
            return OpResult.Fail(503, "RESTORE_LOCKED", "삭제 기록이 없어 다시 적용할 수 없습니다. 읽기 전용을 유지합니다.");
        foreach (var ev in events)
        {
            if (!ApplyLedger(ev, actor.UserId))
            {
                LockRestore("복구 대조 중 파일 삭제가 끝나지 않았습니다. 본문과 자료 위치는 표시하지 않습니다.");
                return OpResult.Fail(503, "FILE_DELETE_FAILED", "파일 삭제가 끝나지 않아 읽기 전용을 유지합니다.");
            }
        }
        var recorded = Transact(conn =>
        {
            AppendHistory(conn, null, Now, actor.UserId, "자료삭제", "복구", null, "관리자가 삭제 기록에 따라 삭제를 다시 적용", "reapply:" + requestId);
            return OpResult.Success("기록");
        });
        if (!recorded.Ok) return recorded;
        if (DeletionMismatch(events))
        {
            LockRestore("삭제를 다시 적용한 뒤에도 기록과 데이터베이스가 다릅니다. 읽기 전용을 유지합니다.");
            return OpResult.Fail(503, "RESTORE_LOCKED", _restoreMessage);
        }
        if (File.Exists(_paths.RestoreLockPath))
            File.Delete(_paths.RestoreLockPath);
        Volatile.Write(ref _restoreLocked, 0);
        _restoreMessage = "";
        return OpResult.Success("삭제 기록을 다시 적용했습니다. 일반 사용을 열었습니다.");
    }

    void LockRestore(string message)
    {
        var already = Volatile.Read(ref _restoreLocked) == 1 && _restoreMessage == message;
        Volatile.Write(ref _restoreLocked, 1);
        _restoreMessage = message;
        if (already) return;
        if (message.Contains("다릅니다", StringComparison.Ordinal))
            _log.LogError("ledger mismatch lock");
        else
            _log.LogWarning("restore lock");
    }

    public OpResult RunDueNow(Actor actor, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var report = ProcessDue();
        if (report.Note == "busy")
            return OpResult.Fail(409, "ALREADY_RUNNING", "이미 실행 중");
        var now = Now;
        Transact(conn =>
        {
            AppendHistory(conn, null, now, actor.UserId, "자료삭제", "보존", null, "만료 삭제 지금 실행", "due-now:" + requestId);
            return OpResult.Success("기록");
        });
        _log.LogWarning("retention run-now enqueued {Enqueued} completed {Completed} waiting {Waiting}", report.Enqueued, report.Completed, report.Waiting);
        return OpResult.Success("만료 삭제를 실행했습니다. 연한이 지난 보류 없는 건만 대상입니다.");
    }

    bool TryReadLedger(out IReadOnlyList<LedgerEvent> events, out string error)
    {
        events = [];
        error = "";
        try
        {
            events = _ledger.ReadAll();
        }
        catch (Exception ex) when (ex is InvalidDataException or JsonException or IOException)
        {
            error = "삭제 기록을 읽을 수 없습니다. 일반 사용을 열지 않습니다.";
            return false;
        }
        for (var i = 0; i < events.Count; i++)
        {
            if (events[i].Seq != i + 1)
            {
                error = "삭제 기록의 순서를 확인할 수 없습니다. 일반 사용을 열지 않습니다.";
                return false;
            }
        }
        return true;
    }

    bool DeletionMismatch(IReadOnlyList<LedgerEvent> events)
    {
        var expiry = events.Where(e => e.Kind == "expiry-done").Select(e => e.CaseId).ToHashSet(StringComparer.Ordinal);
        var bodies = events.Where(e => e.Kind == "bodies-cleared").Select(e => e.CaseId).ToHashSet(StringComparer.Ordinal);
        foreach (var caseId in bodies.Concat(expiry).Distinct(StringComparer.Ordinal))
        {
            if (!CaseExists(caseId)) continue;
            if (BodyTextRemains(caseId)) return true;
        }
        foreach (var caseId in expiry)
        {
            if (!CaseExists(caseId)) continue;
            if (!CaseMarkedPurged(caseId)) return true;
            if (CaseHasLiveFile(caseId)) return true;
        }
        foreach (var ev in events.Where(e => e.Kind == "early-file" && !string.IsNullOrEmpty(e.AttachmentId)))
        {
            if (expiry.Contains(ev.CaseId) || !CaseExists(ev.CaseId)) continue;
            if (AttachmentStillLive(ev.CaseId, ev.AttachmentId!)) return true;
        }
        return false;
    }

    bool CaseExists(string caseId)
    {
        using var conn = _db.Open();
        return ScalarInt(conn, "SELECT COUNT(*) FROM Cases WHERE CaseId=$id;", ("$id", caseId)) > 0;
    }

    bool BodyTextRemains(string caseId)
    {
        using var conn = _db.Open();
        using (var body = Database.Cmd(conn, "SELECT Body FROM Results WHERE CaseId=$id;", ("$id", caseId)))
        {
            var text = body.ExecuteScalar() as string;
            if (!string.IsNullOrEmpty(text)) return true;
        }
        if (ScalarInt(conn, "SELECT COUNT(*) FROM Actions WHERE CaseId=$id AND Content IS NOT NULL;", ("$id", caseId)) > 0)
            return true;
        return ScalarInt(conn, """
            SELECT COUNT(*) FROM Fulfillments f
            JOIN Actions a ON a.ActionId=f.ActionId
            WHERE a.CaseId=$id AND (f.ReplyBody IS NOT NULL OR f.CheckBody IS NOT NULL);
            """, ("$id", caseId)) > 0;
    }

    bool CaseMarkedPurged(string caseId)
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, "SELECT PurgedAt FROM Cases WHERE CaseId=$id;", ("$id", caseId));
        var value = cmd.ExecuteScalar();
        return value is string;
    }

    bool CaseHasLiveFile(string caseId)
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT StorageKey, StorageState FROM Attachments WHERE CaseId=$id;
            """, ("$id", caseId));
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            var key = reader.IsDBNull(0) ? null : reader.GetString(0);
            var state = reader.GetString(1);
            if (state != "Deleted") return true;
            if (!string.IsNullOrEmpty(key) && _files.TryResolve(key, out var abs) && File.Exists(abs)) return true;
        }
        return false;
    }

    bool AttachmentStillLive(string caseId, string attachmentId)
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT StorageKey, StorageState FROM Attachments WHERE AttachmentId=$id AND CaseId=$case;
            """, ("$id", attachmentId), ("$case", caseId));
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return false;
        var key = reader.IsDBNull(0) ? null : reader.GetString(0);
        var state = reader.GetString(1);
        if (state != "Deleted") return true;
        return !string.IsNullOrEmpty(key) && _files.TryResolve(key, out var abs) && File.Exists(abs);
    }

    public void HealLedger()
    {
        if (RestoreLocked) return;
        var events = _ledger.ReadAll();
        bool Has(string kind, string caseId, string? attachmentId = null) =>
            events.Any(e => e.Kind == kind && e.CaseId == caseId && (attachmentId == null || e.AttachmentId == attachmentId));
        using var conn = _db.Open();
        using var cases = Database.Cmd(conn, "SELECT CaseId, BodiesClearedAt, PurgedAt FROM Cases;");
        using var reader = cases.ExecuteReader();
        var rows = new List<(string Id, bool Bodies, bool Purged)>();
        while (reader.Read())
            rows.Add((reader.GetString(0), !reader.IsDBNull(1), !reader.IsDBNull(2)));
        reader.Close();
        foreach (var row in rows)
        {
            if (row.Bodies && !Has("bodies-cleared", row.Id))
                _ledger.Append("bodies-cleared", row.Id, null, Kst.Canon(Now), "본문 삭제");
            if (row.Purged && !Has("expiry-done", row.Id))
                _ledger.Append("expiry-done", row.Id, null, Kst.Canon(Now), "자료 삭제 완료");
            if (!row.Purged)
            {
                using var files = Database.Cmd(conn, """
                    SELECT AttachmentId FROM Attachments WHERE CaseId=$id AND StorageState='Deleted';
                    """, ("$id", row.Id));
                using var fileReader = files.ExecuteReader();
                while (fileReader.Read())
                {
                    var attachmentId = fileReader.GetString(0);
                    if (!Has("early-file", row.Id, attachmentId))
                        _ledger.Append("early-file", row.Id, attachmentId, Kst.Canon(Now), "파일 삭제");
                }
            }
        }
    }

    public void ScanMissingFiles()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT AttachmentId, StorageKey FROM Attachments
            WHERE StorageState='Present' AND StorageKey IS NOT NULL;
            """);
        var missing = new List<string>();
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                var id = reader.GetString(0);
                var key = reader.GetString(1);
                if (_files.TryResolve(key, out var abs) && !File.Exists(abs))
                    missing.Add(id);
            }
        }
        foreach (var id in missing)
        {
            using var upd = Database.Cmd(conn,
                "UPDATE Attachments SET StorageState='Missing' WHERE AttachmentId=$id AND StorageState='Present';",
                ("$id", id));
            upd.ExecuteNonQuery();
        }
    }

    public OpResult Backup(Actor actor, string? subfolder, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!TryBackupDirectory(subfolder, out var parent, out var error))
            return error!;
        var backupId = Text.NewId();
        var dir = Path.Combine(parent, "audit-backup-" + backupId);
        using var exclusive = _gate.EnterExclusive();
        if (!IntegrityIntact()) return OpResult.Fail(503, "INTEGRITY_FAILED", Msg.Integrity);
        try
        {
            Directory.CreateDirectory(dir);
            var dbPath = Path.Combine(dir, "audit.db");
            using (var source = _db.Open())
            using (var destination = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = dbPath, Pooling = false }.ToString()))
            {
                destination.Open();
                source.BackupDatabase(destination);
                using var check = destination.CreateCommand();
                check.CommandText = "PRAGMA integrity_check;";
                var integrity = Convert.ToString(check.ExecuteScalar(), CultureInfo.InvariantCulture);
                if (!string.Equals(integrity, "ok", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("backup integrity");
                CopySnapshotFiles(destination, Path.Combine(dir, "files"));
            }
            if (File.Exists(_paths.LedgerPath))
                File.Copy(_paths.LedgerPath, Path.Combine(dir, "ledger-copy.log"), overwrite: false);
            var manifestHash = WriteManifest(dir, backupId, Now);
            var now = Now;
            Transact(conn =>
            {
                var replay = Replay(conn, actor, requestId!, "backup", null);
                if (replay != null) return replay;
                using var op = Database.Cmd(conn, """
                    INSERT INTO BackupOperations(OperationId, State, At, ActorId, DestinationAlias, BackupId)
                    VALUES ($id,'Complete',$at,$actor,$alias,$bid);
                    """,
                    ("$id", Text.NewId()), ("$at", Kst.Canon(now)), ("$actor", actor.UserId),
                    ("$alias", backupId), ("$bid", backupId));
                op.ExecuteNonQuery();
                AppendHistory(conn, null, now, actor.UserId, "수정", "백업", backupId, "내부 백업 완료. 매니페스트 " + manifestHash, "backup:" + backupId);
                var done = OpResult.Success(Msg.BackupOk, extra: backupId);
                StoreReceipt(conn, actor, requestId!, "backup", null, null, done, now);
                return done;
            });
            Housekeep(parent, Now);
            Log("BACKUP_OK", null);
            return OpResult.Success(Msg.BackupOk, extra: backupId);
        }
        catch (Exception ex)
        {
            _log.LogWarning("backup {Type}", ex.GetType().Name);
            TryDeleteTree(dir);
            try
            {
                Transact(conn =>
                {
                    using var op = Database.Cmd(conn, """
                        INSERT INTO BackupOperations(OperationId, State, At, ActorId, DestinationAlias, ErrorCode)
                        VALUES ($id,'Failed',$at,$actor,$alias,'BACKUP_INCOMPLETE');
                        """,
                        ("$id", Text.NewId()), ("$at", Kst.Canon(Now)), ("$actor", actor.UserId), ("$alias", "failed"));
                    op.ExecuteNonQuery();
                    return OpResult.Success("recorded");
                });
            }
            catch (SqliteException)
            {
                // the failed attempt is still not a completed backup
            }
            return OpResult.Fail(503, "BACKUP_INCOMPLETE", Msg.BackupFail);
        }
    }

    public OpResult Vacuum(Actor actor)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        using var exclusive = _gate.EnterExclusive();
        _db.Checkpoint(vacuum: true);
        return OpResult.Success("저장소 정비를 마쳤습니다. 빈 공간 정리와 secure_delete는 디스크의 물리적 완전 삭제를 증명하지 않습니다.");
    }

    public IReadOnlyList<BackupRow> RecentBackups()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT b.OperationId, b.State, b.At, u.DisplayName, b.BackupId, b.ErrorCode
            FROM BackupOperations b JOIN Users u ON u.UserId=b.ActorId
            ORDER BY b.At DESC LIMIT 10;
            """);
        using var reader = cmd.ExecuteReader();
        var list = new List<BackupRow>();
        while (reader.Read())
        {
            list.Add(new BackupRow
            {
                OperationId = reader.GetString(0),
                State = reader.GetString(1),
                AtDisplay = DisplayStamp(reader.GetString(2)),
                ActorName = reader.GetString(3),
                BackupId = reader.IsDBNull(4) ? null : reader.GetString(4),
                ErrorCode = reader.IsDBNull(5) ? null : reader.GetString(5)
            });
        }
        return list;
    }

    public string BackupRoot => _paths.BackupAllowRoot;

    public IReadOnlyList<DeleteJobRow> RecentDeleteJobs()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT j.JobId, j.CaseId, c.Title, j.Kind, j.Status, j.ErrorCode, j.CreatedAt
            FROM DeleteJobs j JOIN Cases c ON c.CaseId=j.CaseId
            ORDER BY CASE WHEN j.Status='Complete' THEN 1 ELSE 0 END, j.CreatedAt DESC
            LIMIT 30;
            """);
        using var reader = cmd.ExecuteReader();
        var list = new List<DeleteJobRow>();
        while (reader.Read())
        {
            list.Add(new DeleteJobRow
            {
                JobId = reader.GetString(0),
                CaseId = reader.GetString(1),
                Title = reader.GetString(2),
                Kind = reader.GetString(3),
                Status = reader.GetString(4),
                ErrorCode = reader.IsDBNull(5) ? null : reader.GetString(5),
                AtDisplay = DisplayStamp(reader.GetString(6))
            });
        }
        return list;
    }

    public void SeedDemo()
    {
        if (AnyUsers()) return;
        var staffPw = Text.Secret();
        var leadPw = Text.Secret();
        var adminPw = Text.Secret();
        _seedNow = Kst.At(2022, 1, 10, 9, 0);
        var inserted = Transact(conn =>
        {
            InsertUser(conn, "demo-staff", "예시담당", "담당", staffPw, false);
            InsertUser(conn, "demo-lead", "예시파트장", "파트장", leadPw, false);
            InsertUser(conn, "demo-admin", "예시관리자", "관리자", adminPw, false);
            return OpResult.Success(Msg.Saved);
        });
        if (!inserted.Ok) return;
        File.WriteAllText(_paths.DemoAccessPath, "demo-staff " + staffPw + "\n" + "demo-lead " + leadPw + "\n" + "demo-admin " + adminPw + "\n");
        if (OperatingSystem.IsLinux() || OperatingSystem.IsMacOS())
        {
            try { File.SetUnixFileMode(_paths.DemoAccessPath, UnixFileMode.UserRead | UnixFileMode.UserWrite); }
            catch (Exception) { }
        }
        var staff = ActorByLogin("demo-staff");
        var lead = ActorByLogin("demo-lead");
        var admin = ActorByLogin("demo-admin");
        Acknowledge(admin, Text.NewId(), Now);

        var oldCase = RegisterCase(lead, 2022, "준법감사", "예시 준법감사", "예시조직", staff.UserId,
            "예시서가", "예시-02", "예시-2022-001", "예시 결과 한 줄", "예시 본문입니다.", 1, Text.NewId());
        _seedNow = Kst.At(2022, 1, 15, 10, 0);
        Confirm(lead, oldCase.CaseId!, 1, Text.NewId());
        _seedNow = Kst.At(2023, 1, 15, 0, 1);
        ProcessDue();

        _seedNow = Kst.At(2024, 3, 1, 10, 0);
        var current = RegisterCase(staff, 2024, "업무진단", "예시 업무진단", "예시조직", staff.UserId,
            "예시서가", "예시-01", "예시-2024-001", "예시 결과 한 줄", "예시 본문입니다.", 5, Text.NewId());
        var version = current.Version ?? 1;
        var confirmed = Confirm(lead, current.CaseId!, version, Text.NewId());
        version = confirmed.Version ?? version + 1;
        var added = AddAction(staff, current.CaseId!, version, "예시 조치요구", "개선요구", "예시조직",
            "2024-03-15", "2024-06-30", "예시 조치 내용", Text.NewId());
        version = added.Version ?? version + 1;
        using var empty = new MemoryStream([]);
        AddFile(staff, current.CaseId!, version, "증빙", "예시 빈 파일", empty, Text.NewId());
        _seedNow = null;
    }

    void SettleAbsentJobs(DateTimeOffset now)
    {
        List<(string JobId, string Kind, string? AttachmentId)> open;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, """
            SELECT JobId, Kind, TargetAttachmentId FROM DeleteJobs WHERE Status<>'Complete';
            """))
        using (var reader = cmd.ExecuteReader())
        {
            open = [];
            while (reader.Read())
                open.Add((reader.GetString(0), reader.GetString(1), reader.IsDBNull(2) ? null : reader.GetString(2)));
        }
        foreach (var job in open)
        {
            if (job.Kind == "Expiry" || string.IsNullOrEmpty(job.AttachmentId)) continue;
            string? key = null;
            var state = "";
            using (var conn = _db.Open())
            using (var cmd = Database.Cmd(conn, "SELECT StorageKey, StorageState FROM Attachments WHERE AttachmentId=$id;", ("$id", job.AttachmentId)))
            using (var reader = cmd.ExecuteReader())
            {
                if (!reader.Read()) continue;
                key = reader.IsDBNull(0) ? null : reader.GetString(0);
                state = reader.GetString(1);
            }
            if (state == "Deleted" || string.IsNullOrEmpty(key) || FileAlreadyAbsent(key))
                RunSingleFileJob(job.JobId, now);
        }
    }

    static string? JobReason(SqliteConnection conn, string jobId)
    {
        using var cmd = Database.Cmd(conn, "SELECT ReasonSafe FROM DeleteJobs WHERE JobId=$id;", ("$id", jobId));
        return cmd.ExecuteScalar() as string;
    }

    int EnqueueDue(DateTimeOffset now)
    {
        using var shared = _gate.EnterShared();
        var count = 0;
        Transact(conn =>
        {
            using var cmd = Database.Cmd(conn, """
                SELECT CaseId, FirstConfirmedOn, RetentionYears
                FROM Cases
                WHERE PurgedAt IS NULL AND Hold=0 AND FirstConfirmedOn IS NOT NULL AND RetentionYears IS NOT NULL;
                """);
            var due = new List<string>();
            using (var reader = cmd.ExecuteReader())
            {
                while (reader.Read())
                {
                    var id = reader.GetString(0);
                    var anchor = DateOnly.Parse(reader.GetString(1), CultureInfo.InvariantCulture);
                    var years = reader.GetInt32(2);
                    if (Kst.IsDue(anchor, years, now)) due.Add(id);
                }
            }
            foreach (var id in due)
            {
                if (ScalarInt(conn, "SELECT COUNT(*) FROM DeleteJobs WHERE CaseId=$id AND Kind='Expiry' AND Status<>'Complete';", ("$id", id)) > 0)
                    continue;
                using var insert = Database.Cmd(conn, """
                    INSERT INTO DeleteJobs(JobId, CaseId, Kind, Generation, Status, Attempt, CreatedAt)
                    VALUES ($job,$case,'Expiry',1,'Pending',0,$at);
                    """, ("$job", Text.NewId()), ("$case", id), ("$at", Kst.Canon(now)));
                insert.ExecuteNonQuery();
                count++;
            }
            return OpResult.Success("enqueued");
        });
        return count;
    }

    (int Completed, int Waiting) RunJobs(DateTimeOffset now)
    {
        List<string> ids;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, """
            SELECT JobId, Kind FROM DeleteJobs
            WHERE Status='Pending'
               OR (Status='RetryWait' AND (NextAttemptAt IS NULL OR NextAttemptAt<=$now))
               OR (Status='Running' AND (LeaseUntil IS NULL OR LeaseUntil<=$now))
               OR Status='Paused'
            ORDER BY CreatedAt, JobId;
            """, ("$now", Kst.Canon(now))))
        using (var reader = cmd.ExecuteReader())
        {
            ids = [];
            while (reader.Read()) ids.Add(reader.GetString(0));
        }
        var completed = 0;
        var waiting = 0;
        foreach (var id in ids)
        {
            var outcome = DispatchJob(id, now);
            if (outcome == "done") completed++;
            else if (outcome == "wait") waiting++;
        }
        return (completed, waiting);
    }

    string DispatchJob(string jobId, DateTimeOffset now)
    {
        string kind;
        string caseId;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, "SELECT Kind, CaseId, Status FROM DeleteJobs WHERE JobId=$id;", ("$id", jobId)))
        using (var reader = cmd.ExecuteReader())
        {
            if (!reader.Read()) return "skip";
            if (reader.GetString(2) == "Complete") return "skip";
            kind = reader.GetString(0);
            caseId = reader.GetString(1);
        }
        if (kind == "Expiry") return ProcessExpiry(jobId, caseId, now);
        var single = RunSingleFileJob(jobId, now);
        return single.Ok ? "done" : "wait";
    }

    string ProcessExpiry(string jobId, string caseId, DateTimeOffset now)
    {
        var step = ExpiryPrepare(jobId, caseId, now);
        if (step != "go") return step;
        List<(string ItemId, string AttachmentId, string? Key)> items;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, """
            SELECT AttachmentId, StorageKey, Status FROM DeleteJobItems
            WHERE JobId=$id AND Status IN ('Pending','RetryWait');
            """, ("$id", jobId)))
        using (var reader = cmd.ExecuteReader())
        {
            items = [];
            while (reader.Read())
                items.Add((reader.GetString(0), reader.GetString(0), reader.IsDBNull(1) ? null : reader.GetString(1)));
        }
        foreach (var item in items)
        {
            var file = ExpiryOneFile(jobId, caseId, item.AttachmentId, item.Key, now);
            if (file == "hold") return "wait";
            if (file == "wait") return "wait";
        }
        return ExpiryFinish(jobId, caseId, now);
    }

    string ExpiryPrepare(string jobId, string caseId, DateTimeOffset now)
    {
        using var shared = _gate.EnterShared();
        using var caseLock = _locks.Enter(caseId);
        var status = "go";
        Transact(conn =>
        {
            var row = LoadCase(conn, caseId);
            if (row is null) { status = "skip"; return OpResult.Success("x"); }
            if (row.PurgedAt != null)
            {
                CompleteJob(conn, jobId, now);
                status = "done";
                return OpResult.Success("x");
            }
            if (row.Hold)
            {
                PauseJob(conn, jobId, "HOLD_ACTIVE");
                status = "wait";
                return OpResult.Success("x");
            }
            if (row.FirstOn is not { } anchor || row.Retention is not int years || !Kst.IsDue(anchor, years, now))
            {
                PauseJob(conn, jobId, "NOT_DUE");
                status = "wait";
                return OpResult.Success("x");
            }
            TouchJobRunning(conn, jobId, now);
            if (row.BodiesClearedAt == null)
            {
                using (var body = Database.Cmd(conn, "UPDATE Results SET Body=NULL WHERE CaseId=$id;", ("$id", caseId)))
                    body.ExecuteNonQuery();
                using (var actions = Database.Cmd(conn, "UPDATE Actions SET Content=NULL WHERE CaseId=$id;", ("$id", caseId)))
                    actions.ExecuteNonQuery();
                using (var entries = Database.Cmd(conn, """
                    UPDATE Fulfillments SET ReplyBody=NULL, CheckBody=NULL
                    WHERE ActionId IN (SELECT ActionId FROM Actions WHERE CaseId=$id);
                    """, ("$id", caseId)))
                    entries.ExecuteNonQuery();
                using var mark = Database.Cmd(conn, """
                    UPDATE Cases SET BodiesClearedAt=$at, PurgeIncomplete=1, Version=Version+1 WHERE CaseId=$id;
                    """, ("$at", Kst.Canon(now)), ("$id", caseId));
                mark.ExecuteNonQuery();
                AppendHistory(conn, caseId, now, null, "자료삭제", "결과", caseId, "본문 삭제", jobId + ":bodies");
            }
            EnsureExpiryItems(conn, jobId, caseId);
            return OpResult.Success("x");
        });
        if (status == "go")
        {
            var events = _ledger.ReadAll();
            if (!events.Any(e => e.Kind == "bodies-cleared" && e.CaseId == caseId))
                _ledger.Append("bodies-cleared", caseId, null, Kst.Canon(now), "본문 삭제");
        }
        return status;
    }

    string ExpiryOneFile(string jobId, string caseId, string attachmentId, string? key, DateTimeOffset now)
    {
        using var shared = _gate.EnterShared();
        using var caseLock = _locks.Enter(caseId);
        var status = "ok";
        Transact(conn =>
        {
            var row = LoadCase(conn, caseId);
            if (row is null) { status = "skip"; return OpResult.Success("x"); }
            if (row.Hold)
            {
                PauseJob(conn, jobId, "HOLD_ACTIVE");
                status = "hold";
                return OpResult.Success("x");
            }
            var finished = FinishItem(conn, jobId, caseId, attachmentId, key, now, null, "Expiry");
            status = finished.Status;
            return OpResult.Success("commit");
        });
        return status;
    }

    string ExpiryFinish(string jobId, string caseId, DateTimeOffset now)
    {
        using var shared = _gate.EnterShared();
        using var caseLock = _locks.Enter(caseId);
        var status = "wait";
        var purged = false;
        Transact(conn =>
        {
            var row = LoadCase(conn, caseId);
            if (row is null) { status = "skip"; return OpResult.Success("x"); }
            if (row.Hold)
            {
                PauseJob(conn, jobId, "HOLD_ACTIVE");
                status = "wait";
                return OpResult.Success("x");
            }
            var remaining = ScalarInt(conn, """
                SELECT COUNT(*) FROM Attachments
                WHERE CaseId=$id AND StorageState IN ('Present','DeletePending','Missing');
                """, ("$id", caseId));
            var pendingItems = ScalarInt(conn, """
                SELECT COUNT(*) FROM DeleteJobItems WHERE JobId=$id AND Status IN ('Pending','RetryWait');
                """, ("$id", jobId));
            if (remaining == 0 && pendingItems == 0 && row.BodiesClearedAt != null)
            {
                using var upd = Database.Cmd(conn, """
                    UPDATE Cases SET PurgedAt=$at, PurgeIncomplete=0, Version=Version+1 WHERE CaseId=$id;
                    """, ("$at", Kst.Canon(now)), ("$id", caseId));
                upd.ExecuteNonQuery();
                CompleteJob(conn, jobId, now);
                AppendHistory(conn, caseId, now, null, "자료삭제", "건", caseId, "자료 삭제 완료", jobId + ":done");
                status = "done";
                purged = true;
            }
            else
            {
                MarkJobRetry(conn, jobId, now, "FILE_DELETE_FAILED");
                using var flag = Database.Cmd(conn, "UPDATE Cases SET PurgeIncomplete=1 WHERE CaseId=$id;", ("$id", caseId));
                flag.ExecuteNonQuery();
                status = "wait";
            }
            return OpResult.Success("x");
        });
        if (purged)
            _ledger.Append("expiry-done", caseId, null, Kst.Canon(now), "자료 삭제 완료");
        return status;
    }

    OpResult RunSingleFileJob(string jobId, DateTimeOffset now)
    {
        string caseId;
        string kind;
        string? attachmentId;
        string? actorId;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, """
            SELECT CaseId, Kind, TargetAttachmentId, ApprovedActorId, Status FROM DeleteJobs WHERE JobId=$id;
            """, ("$id", jobId)))
        using (var reader = cmd.ExecuteReader())
        {
            if (!reader.Read()) return OpResult.Fail(404, "NOT_FOUND", "삭제 작업을 찾을 수 없음");
            if (reader.GetString(4) == "Complete")
                return OpResult.Success("이미 삭제된 파일입니다.", reader.GetString(0));
            caseId = reader.GetString(0);
            kind = reader.GetString(1);
            attachmentId = reader.IsDBNull(2) ? null : reader.GetString(2);
            actorId = reader.IsDBNull(3) ? null : reader.GetString(3);
        }
        if (attachmentId == null) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        string? key;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, "SELECT StorageKey FROM DeleteJobItems WHERE JobId=$j AND AttachmentId=$a;", ("$j", jobId), ("$a", attachmentId)))
        {
            var value = cmd.ExecuteScalar();
            key = value is string s ? s : null;
        }
        using var shared = _gate.EnterShared();
        using var caseLock = _locks.Enter(caseId);
        OpResult? stopped = null;
        var result = Transact(conn =>
        {
            var row = LoadCase(conn, caseId);
            if (row is null) return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
            if (kind == "RemoveDraft" && row.Ever)
            {
                RevertPending(conn, attachmentId);
                PauseJob(conn, jobId, "EVER_CONFIRMED");
                stopped = OpResult.Fail(403, "FORBIDDEN", "확정 이력이 있어 첨부 빼기를 중단했습니다.");
                return OpResult.Success("commit-stop");
            }
            if (row.Hold)
            {
                RevertPending(conn, attachmentId);
                PauseJob(conn, jobId, "HOLD_ACTIVE");
                stopped = OpResult.Fail(409, "HOLD_ACTIVE", Msg.HoldActive);
                return OpResult.Success("commit-stop");
            }
            TouchJobRunning(conn, jobId, now);
            var finished = FinishItem(conn, jobId, caseId, attachmentId, key, now, actorId, kind);
            if (finished.Status != "ok")
            {
                stopped = finished.Result;
                return OpResult.Success("commit-retry");
            }
            CompleteJob(conn, jobId, now);
            var message = kind == "EarlyFile"
                ? "파일을 삭제했습니다. 본문은 지우지 않았고, 자료 행은 남습니다."
                : "확정 전 첨부를 뺐습니다. 자료 행과 이력은 남습니다.";
            return OpResult.Success(message, caseId, LoadVersion(conn, caseId));
        });
        if (stopped != null) return stopped;
        if (result.Ok && kind == "EarlyFile")
            _ledger.Append("early-file", caseId, attachmentId, Kst.Canon(now), "파일 삭제");
        return result;
    }

    (OpResult Result, string Status) FinishItem(SqliteConnection conn, string jobId, string caseId, string attachmentId, string? key, DateTimeOffset now, string? actorId, string kind)
    {
        var current = AttachmentState(conn, attachmentId);
        if (current == "Deleted")
        {
            using var item = Database.Cmd(conn, """
                UPDATE DeleteJobItems SET Status='Deleted', StorageKey=NULL, ActualDeletedAt=$at WHERE JobId=$j AND AttachmentId=$a;
                """, ("$at", Kst.Canon(now)), ("$j", jobId), ("$a", attachmentId));
            item.ExecuteNonQuery();
            return (OpResult.Success("deleted"), "ok");
        }
        var delete = string.IsNullOrEmpty(key) ? FileDeleteResult.Missing : _files.DeleteKey(key);
        if (delete is FileDeleteResult.Busy or FileDeleteResult.Failed or FileDeleteResult.Unsafe)
        {
            var code = delete == FileDeleteResult.Unsafe ? "UNSAFE_STORAGE_KEY" : "FILE_DELETE_FAILED";
            MarkJobRetry(conn, jobId, now, code);
            using var item = Database.Cmd(conn, """
                UPDATE DeleteJobItems SET Status='RetryWait', ErrorCode=$c WHERE JobId=$j AND AttachmentId=$a;
                """, ("$c", code), ("$j", jobId), ("$a", attachmentId));
            item.ExecuteNonQuery();
            return (OpResult.Fail(503, code, delete == FileDeleteResult.Unsafe ? Msg.UnsafePath : Msg.FileFail), "wait");
        }
        var summary = delete == FileDeleteResult.Missing ? "이미 없음 확인" : kind switch
        {
            "EarlyFile" => "파일 삭제",
            "RemoveDraft" => "첨부 삭제",
            _ => "자료 파일 삭제"
        };
        var why = JobReason(conn, jobId);
        if (!string.IsNullOrWhiteSpace(why)) summary += ". 사유: " + why;
        var itemStatus = delete == FileDeleteResult.Missing ? "AlreadyMissing" : "Deleted";
        using (var att = Database.Cmd(conn, """
            UPDATE Attachments
            SET StorageState='Deleted', DeletedAt=$at, StorageKey=NULL
            WHERE AttachmentId=$id;
            """, ("$at", Kst.Canon(now)), ("$id", attachmentId)))
            att.ExecuteNonQuery();
        using (var item = Database.Cmd(conn, """
            UPDATE DeleteJobItems
            SET Status=$st, StorageKey=NULL, ActualDeletedAt=$at, ErrorCode=NULL
            WHERE JobId=$j AND AttachmentId=$a;
            """, ("$st", itemStatus), ("$at", Kst.Canon(now)), ("$j", jobId), ("$a", attachmentId)))
            item.ExecuteNonQuery();
        using (var ver = Database.Cmd(conn, "UPDATE Cases SET Version=Version+1 WHERE CaseId=$id;", ("$id", caseId)))
            ver.ExecuteNonQuery();
        AppendHistory(conn, caseId, now, actorId, "자료삭제", "자료", attachmentId, summary, jobId + ":" + attachmentId + ":file");
        return (OpResult.Success("deleted"), "ok");
    }

    bool ApplyLedger(LedgerEvent ev, string? actorId = null)
    {
        switch (ev.Kind)
        {
            case "hold-set":
                Transact(conn =>
                {
                    using var cmd = Database.Cmd(conn, """
                        UPDATE Cases SET Hold=1, HoldSetAt=COALESCE(HoldSetAt,$at)
                        WHERE CaseId=$id;
                        """, ("$at", ev.At), ("$id", ev.CaseId));
                    cmd.ExecuteNonQuery();
                    AppendHistory(conn, ev.CaseId, ParseStamp(ev.At), actorId, "보류", "건", ev.CaseId, "복구 대조: 삭제 보류", "ledger:" + ev.EventId);
                    return OpResult.Success("x");
                });
                return true;
            case "hold-clear":
                Transact(conn =>
                {
                    using var cmd = Database.Cmd(conn, """
                        UPDATE Cases SET Hold=0, HoldReleasedAt=COALESCE(HoldReleasedAt,$at) WHERE CaseId=$id;
                        """, ("$at", ev.At), ("$id", ev.CaseId));
                    cmd.ExecuteNonQuery();
                    AppendHistory(conn, ev.CaseId, ParseStamp(ev.At), actorId, "보류해제", "건", ev.CaseId, "복구 대조: 보류 해제", "ledger:" + ev.EventId);
                    return OpResult.Success("x");
                });
                return true;
            case "bodies-cleared":
                Transact(conn =>
                {
                    NullBodies(conn, ev.CaseId, ev.At);
                    AppendHistory(conn, ev.CaseId, ParseStamp(ev.At), actorId, "자료삭제", "결과", ev.CaseId, "복구 대조: 본문 삭제", "ledger:" + ev.EventId);
                    return OpResult.Success("x");
                });
                return true;
            case "early-file":
                return ApplyFileDelete(ev, ev.AttachmentId, actorId);
            case "expiry-done":
                Transact(conn =>
                {
                    NullBodies(conn, ev.CaseId, ev.At);
                    return OpResult.Success("x");
                });
                var keys = AttachmentKeys(ev.CaseId);
                foreach (var pair in keys)
                {
                    if (!ApplyPhysical(pair.Key, pair.Storage, ev, actorId))
                        return false;
                }
                var done = Transact(conn =>
                {
                    var left = ScalarInt(conn, """
                        SELECT COUNT(*) FROM Attachments
                        WHERE CaseId=$id AND StorageState IN ('Present','DeletePending','Missing');
                        """, ("$id", ev.CaseId));
                    if (left > 0) return OpResult.Fail(503, "FILE_DELETE_FAILED", Msg.FileFail);
                    using var cmd = Database.Cmd(conn, """
                        UPDATE Cases SET PurgedAt=COALESCE(PurgedAt,$at), BodiesClearedAt=COALESCE(BodiesClearedAt,$at), PurgeIncomplete=0
                        WHERE CaseId=$id;
                        """, ("$at", ev.At), ("$id", ev.CaseId));
                    cmd.ExecuteNonQuery();
                    AppendHistory(conn, ev.CaseId, ParseStamp(ev.At), actorId, "자료삭제", "건", ev.CaseId, "복구 대조: 자료 삭제 재적용", "ledger:" + ev.EventId);
                    return OpResult.Success("x");
                });
                return done.Ok;
            default:
                return false;
        }
    }

    bool ApplyFileDelete(LedgerEvent ev, string? attachmentId, string? actorId)
    {
        if (string.IsNullOrEmpty(attachmentId)) return false;
        string? key = null;
        string state = "";
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, "SELECT StorageKey, StorageState FROM Attachments WHERE AttachmentId=$id AND CaseId=$case;",
            ("$id", attachmentId), ("$case", ev.CaseId)))
        using (var reader = cmd.ExecuteReader())
        {
            if (!reader.Read()) return true;
            key = reader.IsDBNull(0) ? null : reader.GetString(0);
            state = reader.GetString(1);
        }
        if (state == "Deleted") return true;
        return ApplyPhysical(attachmentId, key, ev, actorId);
    }

    bool ApplyPhysical(string attachmentId, string? key, LedgerEvent ev, string? actorId)
    {
        var delete = string.IsNullOrEmpty(key) ? FileDeleteResult.Missing : _files.DeleteKey(key);
        if (delete is FileDeleteResult.Busy or FileDeleteResult.Failed or FileDeleteResult.Unsafe)
            return false;
        var result = Transact(conn =>
        {
            using var cmd = Database.Cmd(conn, """
                UPDATE Attachments SET StorageState='Deleted', DeletedAt=COALESCE(DeletedAt,$at), StorageKey=NULL
                WHERE AttachmentId=$id;
                """, ("$at", ev.At), ("$id", attachmentId));
            cmd.ExecuteNonQuery();
            AppendHistory(conn, ev.CaseId, ParseStamp(ev.At), actorId, "자료삭제", "자료", attachmentId, "복구 대조: 파일 삭제", "ledger:" + ev.EventId + ":" + attachmentId);
            return OpResult.Success("x");
        });
        return result.Ok;
    }

    static void NullBodies(SqliteConnection conn, string caseId, string at)
    {
        using (var body = Database.Cmd(conn, "UPDATE Results SET Body=NULL WHERE CaseId=$id;", ("$id", caseId)))
            body.ExecuteNonQuery();
        using (var actions = Database.Cmd(conn, "UPDATE Actions SET Content=NULL WHERE CaseId=$id;", ("$id", caseId)))
            actions.ExecuteNonQuery();
        using (var entries = Database.Cmd(conn, """
            UPDATE Fulfillments SET ReplyBody=NULL, CheckBody=NULL
            WHERE ActionId IN (SELECT ActionId FROM Actions WHERE CaseId=$id);
            """, ("$id", caseId)))
            entries.ExecuteNonQuery();
        using var mark = Database.Cmd(conn, """
            UPDATE Cases SET BodiesClearedAt=COALESCE(BodiesClearedAt,$at), PurgeIncomplete=1 WHERE CaseId=$id;
            """, ("$at", at), ("$id", caseId));
        mark.ExecuteNonQuery();
    }

    List<(string Key, string? Storage)> AttachmentKeys(string caseId)
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT AttachmentId, StorageKey FROM Attachments
            WHERE CaseId=$id AND StorageState IN ('Present','DeletePending','Missing');
            """, ("$id", caseId));
        using var reader = cmd.ExecuteReader();
        var list = new List<(string, string?)>();
        while (reader.Read())
            list.Add((reader.GetString(0), reader.IsDBNull(1) ? null : reader.GetString(1)));
        return list;
    }

    void EnsureExpiryItems(SqliteConnection conn, string jobId, string caseId)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT AttachmentId, StorageKey FROM Attachments
            WHERE CaseId=$id AND StorageState IN ('Present','DeletePending','Missing');
            """, ("$id", caseId));
        var rows = new List<(string Id, string? Key)>();
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
                rows.Add((reader.GetString(0), reader.IsDBNull(1) ? null : reader.GetString(1)));
        }
        foreach (var row in rows)
        {
            if (ScalarInt(conn, "SELECT COUNT(*) FROM DeleteJobItems WHERE JobId=$j AND AttachmentId=$a;", ("$j", jobId), ("$a", row.Id)) > 0)
                continue;
            using var insert = Database.Cmd(conn, """
                INSERT INTO DeleteJobItems(JobId, AttachmentId, StorageKey, Status) VALUES ($j,$a,$k,'Pending');
                """, ("$j", jobId), ("$a", row.Id), ("$k", row.Key));
            insert.ExecuteNonQuery();
        }
    }

    static void TouchJobRunning(SqliteConnection conn, string jobId, DateTimeOffset now)
    {
        using var cmd = Database.Cmd(conn, """
            UPDATE DeleteJobs
            SET Status='Running', LeaseUntil=$lease, Attempt=Attempt+1
            WHERE JobId=$id AND Status<>'Complete';
            """, ("$lease", Kst.Canon(now.AddMinutes(2))), ("$id", jobId));
        cmd.ExecuteNonQuery();
    }

    static void PauseJob(SqliteConnection conn, string jobId, string code)
    {
        using var cmd = Database.Cmd(conn, """
            UPDATE DeleteJobs SET Status='Paused', ErrorCode=$c, LeaseUntil=NULL WHERE JobId=$id AND Status<>'Complete';
            """, ("$c", code), ("$id", jobId));
        cmd.ExecuteNonQuery();
    }

    static void CompleteJob(SqliteConnection conn, string jobId, DateTimeOffset now)
    {
        using var cmd = Database.Cmd(conn, """
            UPDATE DeleteJobs SET Status='Complete', CompletedAt=$at, ErrorCode=NULL, LeaseUntil=NULL WHERE JobId=$id;
            """, ("$at", Kst.Canon(now)), ("$id", jobId));
        cmd.ExecuteNonQuery();
    }

    static void MarkJobRetry(SqliteConnection conn, string jobId, DateTimeOffset now, string code)
    {
        int attempt;
        using (var read = Database.Cmd(conn, "SELECT Attempt FROM DeleteJobs WHERE JobId=$id;", ("$id", jobId)))
            attempt = Convert.ToInt32(read.ExecuteScalar(), CultureInfo.InvariantCulture);
        var minutes = attempt <= 1 ? 1 : attempt == 2 ? 5 : 30;
        using var cmd = Database.Cmd(conn, """
            UPDATE DeleteJobs
            SET Status='RetryWait', ErrorCode=$c, NextAttemptAt=$next, LeaseUntil=NULL
            WHERE JobId=$id AND Status<>'Complete';
            """, ("$c", code), ("$next", Kst.Canon(now.AddMinutes(minutes))), ("$id", jobId));
        cmd.ExecuteNonQuery();
    }

    static void RevertPending(SqliteConnection conn, string attachmentId)
    {
        using var cmd = Database.Cmd(conn, """
            UPDATE Attachments SET StorageState='Present'
            WHERE AttachmentId=$id AND StorageState='DeletePending' AND StorageKey IS NOT NULL;
            """, ("$id", attachmentId));
        cmd.ExecuteNonQuery();
    }

    static string AttachmentState(SqliteConnection conn, string attachmentId)
    {
        using var cmd = Database.Cmd(conn, "SELECT StorageState FROM Attachments WHERE AttachmentId=$id;", ("$id", attachmentId));
        return cmd.ExecuteScalar() as string ?? "";
    }

    static int LoadVersion(SqliteConnection conn, string caseId)
    {
        using var cmd = Database.Cmd(conn, "SELECT Version FROM Cases WHERE CaseId=$id;", ("$id", caseId));
        return Convert.ToInt32(cmd.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    static DateTimeOffset ParseStamp(string text) =>
        DateTimeOffset.Parse(text, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);

    void CopySnapshotFiles(SqliteConnection snapshot, string filesRoot)
    {
        using var cmd = snapshot.CreateCommand();
        cmd.CommandText = """
            SELECT StorageKey FROM Attachments
            WHERE StorageKey IS NOT NULL AND StorageState IN ('Present','DeletePending');
            """;
        using var reader = cmd.ExecuteReader();
        while (reader.Read())
        {
            var key = reader.GetString(0);
            var dest = Path.Combine(filesRoot, key.Replace('/', Path.DirectorySeparatorChar));
            if (!_files.CopyTo(key, dest))
                throw new IOException("snapshot file missing");
        }
    }

    static string WriteManifest(string dir, string backupId, DateTimeOffset now)
    {
        var files = new List<object>();
        foreach (var file in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories).OrderBy(path => path, StringComparer.Ordinal))
        {
            if (file.EndsWith("manifest.json", StringComparison.Ordinal)) continue;
            var info = new FileInfo(file);
            if (info.Attributes.HasFlag(FileAttributes.ReparsePoint))
                throw new IOException("reparse");
            files.Add(new
            {
                relative = Path.GetRelativePath(dir, file).Replace('\\', '/'),
                bytes = info.Length,
                sha256 = Sha256Of(file)
            });
        }
        var manifest = new
        {
            appId = AppId,
            ownership = "this-app-created",
            backupId,
            createdAt = Kst.Canon(now),
            files
        };
        var json = JsonSerializer.Serialize(manifest);
        var path = Path.Combine(dir, "manifest.json");
        File.WriteAllText(path, json);
        return Sha256Of(path);
    }

    static string Sha256Of(string path)
    {
        using var stream = File.OpenRead(path);
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(stream)).ToLowerInvariant();
    }

    void Housekeep(string parent, DateTimeOffset now)
    {
        int days;
        int generations;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, "SELECT BackupRetentionDays, BackupRetentionGenerations FROM SystemMeta WHERE Id=1;"))
        using (var reader = cmd.ExecuteReader())
        {
            reader.Read();
            days = reader.GetInt32(0);
            generations = reader.GetInt32(1);
        }
        if (!Directory.Exists(parent)) return;
        var owned = new List<(string Path, DateTimeOffset Created, string Id)>();
        foreach (var dir in Directory.EnumerateDirectories(parent))
        {
            var info = new DirectoryInfo(dir);
            if (info.Attributes.HasFlag(FileAttributes.ReparsePoint)) continue;
            var name = info.Name;
            if (!name.StartsWith("audit-backup-", StringComparison.Ordinal)) continue;
            var manifestPath = Path.Combine(dir, "manifest.json");
            if (!File.Exists(manifestPath)) continue;
            try
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(manifestPath));
                var root = doc.RootElement;
                if (root.GetProperty("appId").GetString() != AppId) continue;
                if (root.GetProperty("ownership").GetString() != "this-app-created") continue;
                var id = root.GetProperty("backupId").GetString() ?? "";
                if (name != "audit-backup-" + id) continue;
                if (!DateTimeOffset.TryParse(root.GetProperty("createdAt").GetString(), CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var created))
                    continue;
                owned.Add((dir, created, id));
            }
            catch (Exception ex) when (ex is JsonException or KeyNotFoundException or IOException)
            {
                continue;
            }
        }
        var ordered = owned.OrderByDescending(x => x.Created).ThenBy(x => x.Id).ToList();
        for (var i = 0; i < ordered.Count; i++)
        {
            var age = now - ordered[i].Created;
            if (i >= generations || age > TimeSpan.FromDays(days))
                TryDeleteTree(ordered[i].Path);
        }
    }

    bool TryBackupDirectory(string? subfolder, out string parent, out OpResult? error)
    {
        parent = "";
        error = null;
        var root = Path.GetFullPath(_paths.BackupAllowRoot);
        if (!string.IsNullOrWhiteSpace(subfolder))
        {
            if (subfolder.Length > 40)
            {
                error = OpResult.Fail(422, "BAD_INPUT", "하위 폴더 이름은 40자 이하입니다.");
                return false;
            }
            if (subfolder.Contains('/') || subfolder.Contains('\\') || subfolder.Contains("..", StringComparison.Ordinal))
            {
                error = OpResult.Fail(422, "UNSAFE_STORAGE_KEY", Msg.UnsafePath);
                return false;
            }
            if (subfolder.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '_' or '-' || c is >= '가' and <= '힣')))
            {
                error = OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
                return false;
            }
            parent = Path.GetFullPath(Path.Combine(root, subfolder));
        }
        else
        {
            parent = root;
        }
        var prefix = root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!parent.Equals(root, StringComparison.Ordinal) && !parent.StartsWith(prefix, StringComparison.Ordinal))
        {
            error = OpResult.Fail(422, "UNSAFE_STORAGE_KEY", Msg.UnsafePath);
            return false;
        }
        if (parent.StartsWith(Path.GetFullPath(_paths.FilesDir), StringComparison.Ordinal)
            || parent.StartsWith(Path.GetFullPath(_paths.DataDir), StringComparison.Ordinal))
        {
            error = OpResult.Fail(422, "UNSAFE_STORAGE_KEY", Msg.UnsafePath);
            return false;
        }
        try
        {
            Directory.CreateDirectory(parent);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException or NotSupportedException)
        {
            error = OpResult.Fail(422, "BAD_INPUT", "하위 폴더를 만들 수 없습니다. 이름은 40자 이하의 글자, 숫자, 하이픈만 사용하세요.");
            return false;
        }
        return true;
    }

    public OpResult VerifyBackup(Actor actor, string? backupId, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId) || !Text.IsHexId(backupId))
            return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!TryFindBackup(backupId!, out var dir))
            return OpResult.Fail(404, "NOT_FOUND", "그 식별자의 완료 백업을 찾지 못했습니다.");
        string message;
        var ok = false;
        try
        {
            var manifestPath = Path.Combine(dir, "manifest.json");
            using var doc = JsonDocument.Parse(File.ReadAllText(manifestPath));
            var root = doc.RootElement;
            if (root.GetProperty("appId").GetString() != AppId || root.GetProperty("ownership").GetString() != "this-app-created")
                return OpResult.Fail(422, "BACKUP_INCOMPLETE", "이 앱이 만든 백업이 아닙니다.");
            var dbPath = Path.Combine(dir, "audit.db");
            using var conn = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = dbPath, Mode = SqliteOpenMode.ReadOnly, Pooling = false }.ToString());
            conn.Open();
            using var check = conn.CreateCommand();
            check.CommandText = "PRAGMA integrity_check;";
            var integrity = Convert.ToString(check.ExecuteScalar(), CultureInfo.InvariantCulture);
            if (!string.Equals(integrity, "ok", StringComparison.OrdinalIgnoreCase))
            {
                message = "백업 데이터베이스 검사가 이상이 있다고 보고했습니다. 이 복사본으로 되돌리지 마세요.";
            }
            else if (!CheckChain(conn, out _))
            {
                message = "백업 데이터베이스의 이력 체인이 맞지 않습니다. 이 복사본으로 되돌리지 마세요.";
            }
            else if (!ManifestFilesMatch(dir, root, out var hashMismatch))
            {
                message = hashMismatch;
            }
            else if (!ManifestSealMatches(backupId!, Sha256Of(manifestPath)))
            {
                message = "백업 매니페스트 해시가 이력과 다릅니다. 이 복사본으로 되돌리지 마세요.";
            }
            else
            {
                ok = true;
                message = "백업 검사를 마쳤습니다. 파일 해시, 매니페스트, 이력 체인이 맞습니다. 이상 없음. 복구는 실행하지 않았습니다.";
            }
        }
        catch (Exception ex) when (ex is IOException or JsonException or SqliteException or KeyNotFoundException or InvalidOperationException)
        {
            _log.LogWarning("verify {Type}", ex.GetType().Name);
            message = "백업 검사를 마치지 못했습니다. 이 복사본으로 되돌리지 마세요.";
        }
        var recorded = Transact(conn =>
        {
            AppendHistory(conn, null, Now, actor.UserId, "수정", "백업", backupId, ok ? "백업 무결성 확인: 이상 없음" : "백업 무결성 확인: 사용하지 않음", "verify:" + requestId);
            return OpResult.Success("기록");
        });
        if (!recorded.Ok) return recorded;
        return ok
            ? OpResult.Success(message)
            : OpResult.Fail(422, "BACKUP_INCOMPLETE", message);
    }

    static bool ManifestFilesMatch(string dir, JsonElement root, out string message)
    {
        message = "";
        if (!root.TryGetProperty("files", out var files))
        {
            message = "백업 매니페스트에 파일 목록이 없습니다. 이 복사본으로 되돌리지 마세요.";
            return false;
        }
        var rootPath = Path.GetFullPath(dir);
        var prefix = rootPath.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        foreach (var file in files.EnumerateArray())
        {
            var relative = file.GetProperty("relative").GetString() ?? "";
            var expected = file.TryGetProperty("sha256", out var hash) ? hash.GetString() : null;
            var path = Path.GetFullPath(Path.Combine(rootPath, relative.Replace('/', Path.DirectorySeparatorChar)));
            if (!path.StartsWith(prefix, StringComparison.Ordinal) || !File.Exists(path))
            {
                message = "백업 파일이 없습니다. 이 복사본으로 되돌리지 마세요.";
                return false;
            }
            if (string.IsNullOrEmpty(expected) || !string.Equals(Sha256Of(path), expected, StringComparison.OrdinalIgnoreCase))
            {
                message = "백업 파일 해시가 매니페스트와 다릅니다. 이 복사본으로 되돌리지 마세요.";
                return false;
            }
        }
        return true;
    }

    bool ManifestSealMatches(string backupId, string manifestHash)
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT SummarySafe FROM History
            WHERE TargetId=$id AND SummarySafe LIKE '내부 백업 완료. 매니페스트 %'
            ORDER BY Seq DESC LIMIT 1;
            """, ("$id", backupId));
        var summary = cmd.ExecuteScalar() as string ?? "";
        const string prefix = "내부 백업 완료. 매니페스트 ";
        if (!summary.StartsWith(prefix, StringComparison.Ordinal)) return false;
        return string.Equals(summary[prefix.Length..].Trim(), manifestHash, StringComparison.OrdinalIgnoreCase);
    }

    bool TryFindBackup(string backupId, out string dir)
    {
        dir = "";
        var root = Path.GetFullPath(_paths.BackupAllowRoot);
        if (!Directory.Exists(root)) return false;
        foreach (var candidate in Directory.EnumerateDirectories(root, "audit-backup-" + backupId, SearchOption.AllDirectories))
        {
            var full = Path.GetFullPath(candidate);
            var prefix = root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            if (!full.StartsWith(prefix, StringComparison.Ordinal)) continue;
            if (Directory.Exists(full) && File.GetAttributes(full).HasFlag(FileAttributes.ReparsePoint)) continue;
            if (!File.Exists(Path.Combine(full, "manifest.json")) || !File.Exists(Path.Combine(full, "audit.db"))) continue;
            dir = full;
            return true;
        }
        return false;
    }

    static void TryDeleteTree(string dir)
    {
        if (!Directory.Exists(dir)) return;
        try
        {
            DeleteTree(dir);
        }
        catch (Exception)
        {
            // leave the directory; it is not marked complete if manifest write failed
        }
    }

    static void DeleteTree(string dir)
    {
        var info = new DirectoryInfo(dir);
        if (info.Attributes.HasFlag(FileAttributes.ReparsePoint))
            throw new IOException("reparse");
        foreach (var file in info.EnumerateFiles())
        {
            if (file.Attributes.HasFlag(FileAttributes.ReparsePoint))
                throw new IOException("reparse");
            file.Attributes = FileAttributes.Normal;
            file.Delete();
        }
        foreach (var sub in info.EnumerateDirectories())
            DeleteTree(sub.FullName);
        info.Delete();
    }

    Actor ActorByLogin(string login)
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT UserId, Login, DisplayName, Role, Stamp FROM Users WHERE Login=$l;
            """, ("$l", login));
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) throw new InvalidOperationException("seed user missing");
        return new Actor(reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetString(3), reader.GetString(4));
    }

    static string DisplayStamp(string canon)
    {
        if (DateTimeOffset.TryParse(canon, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var instant))
            return Kst.Display(instant);
        return canon;
    }
}
