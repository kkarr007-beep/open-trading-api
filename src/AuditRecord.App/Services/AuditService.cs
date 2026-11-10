using System.Globalization;
using AuditRecord.Data;
using AuditRecord.Domain;
using AuditRecord.Storage;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Services;

public sealed partial class AuditService
{
    public const string AppId = "audit-record-local";

    readonly Database _db;
    readonly FileStore _files;
    readonly MaintenanceGate _gate;
    readonly CaseLocks _locks = new();
    readonly IClock _clock;
    readonly RetentionLedger _ledger;
    readonly Passwords _passwords;
    readonly AppPaths _paths;
    readonly ILogger<AuditService> _log;
    readonly string _dummyHash;
    int _restoreLocked;
    int _dueFlag;
    string _restoreMessage = "";
    readonly object _writer = new();
    DateTimeOffset? _seedNow;

    DateTimeOffset Now => _seedNow ?? _clock.UtcNow;
    public const string NascaLine = "이 위치의 파일은 사용자 컴퓨터의 나스카로 연다.";

    public AuditService(
        Database db,
        FileStore files,
        MaintenanceGate gate,
        IClock clock,
        RetentionLedger ledger,
        Passwords passwords,
        AppPaths paths,
        ILogger<AuditService> log)
    {
        _db = db;
        _files = files;
        _gate = gate;
        _clock = clock;
        _ledger = ledger;
        _passwords = passwords;
        _paths = paths;
        _log = log;
        _dummyHash = passwords.Hash("none", "not-a-real-password-value");
    }

    public bool RestoreLocked => Volatile.Read(ref _restoreLocked) == 1;
    public string RestoreMessage => _restoreMessage;

    public bool IntegrityIntact() => EvaluateIntegritySnapshot();

    bool RememberIntegrity(SqliteConnection conn)
    {
        _ = conn;
        return EvaluateIntegritySnapshot();
    }

    bool EvaluateIntegritySnapshot()
    {
        lock (_writer)
        {
            using var conn = _db.Open();
            using (var begin = conn.CreateCommand())
            {
                begin.CommandText = "BEGIN;";
                begin.ExecuteNonQuery();
            }
            var storedFailed = ReadIntegrityState(conn) == "failed";
            var chainOk = CheckChain(conn, out var reason);
            using (var end = conn.CreateCommand())
            {
                end.CommandText = "ROLLBACK;";
                end.ExecuteNonQuery();
            }
            if (storedFailed) return false;
            if (chainOk) return true;
            using (var begin = conn.CreateCommand())
            {
                begin.CommandText = "BEGIN IMMEDIATE;";
                begin.ExecuteNonQuery();
            }
            if (ReadIntegrityState(conn) == "failed")
            {
                using var done = conn.CreateCommand();
                done.CommandText = "ROLLBACK;";
                done.ExecuteNonQuery();
                return false;
            }
            if (CheckChain(conn, out reason))
            {
                using var done = conn.CreateCommand();
                done.CommandText = "ROLLBACK;";
                done.ExecuteNonQuery();
                return true;
            }
            LatchIntegrity(conn, reason);
            using var commit = conn.CreateCommand();
            commit.CommandText = "COMMIT;";
            commit.ExecuteNonQuery();
            return false;
        }
    }

    static string ReadIntegrityState(SqliteConnection conn)
    {
        using var cmd = Database.Cmd(conn, "SELECT IntegrityState FROM SystemMeta WHERE Id=1;");
        return cmd.ExecuteScalar() as string ?? "ok";
    }

    void LatchIntegrity(SqliteConnection conn, string reason)
    {
        var code = string.IsNullOrWhiteSpace(reason) ? "unknown" : reason;
        using (var upd = Database.Cmd(conn, "UPDATE SystemMeta SET IntegrityState='failed' WHERE Id=1 AND IntegrityState<>'failed';"))
            upd.ExecuteNonQuery();
        var existing = ScalarInt(conn, "SELECT COUNT(*) FROM IntegrityIncidents;");
        if (existing == 0)
        {
            using var insert = Database.Cmd(conn, """
                INSERT INTO IntegrityIncidents(IncidentId, DetectedAt, ReasonCode, SummarySafe)
                VALUES ($id,$at,$code,$sum);
                """,
                ("$id", Text.NewId()),
                ("$at", Kst.Canon(Now)),
                ("$code", code),
                ("$sum", "이력 해시가 맞지 않습니다. 코드 " + code));
            insert.ExecuteNonQuery();
        }
        _log.LogError("integrity latched {ReasonCode}", code);
    }

    public OpResult AcknowledgeIntegrity(Actor actor, string? reason, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var why = Text.Clean(reason, 240, false);
        if (string.IsNullOrEmpty(why)) return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var now = Now;
        var stillBroken = false;
        var result = Transact(conn =>
        {
            if (ReadIntegrityState(conn) != "failed")
                return OpResult.Success("해제할 이력 잠금이 없습니다.");
            AppendHistory(conn, null, now, actor.UserId, "수정", "이력", null, "이력 훼손 조사 확인. 사유: " + why, "integrity-ack:" + requestId);
            if (!CheckChain(conn, out _))
            {
                stillBroken = true;
                return OpResult.Success("조사 사유는 이력에 남겼습니다. 체인이 아직 맞지 않아 읽기 전용을 유지합니다.");
            }
            using var upd = Database.Cmd(conn, "UPDATE SystemMeta SET IntegrityState='ok' WHERE Id=1;");
            upd.ExecuteNonQuery();
            return OpResult.Success("조사를 기록하고 읽기 전용을 해제했습니다.");
        });
        if (stillBroken)
            _log.LogWarning("integrity acknowledgement kept the latch");
        else if (result.Ok && result.Message.Contains("해제했습니다", StringComparison.Ordinal))
            _log.LogWarning("integrity latch cleared after acknowledgement");
        return result;
    }

    public bool AnyUsers()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, "SELECT COUNT(*) FROM Users;");
        return Convert.ToInt32(cmd.ExecuteScalar(), CultureInfo.InvariantCulture) > 0;
    }

    public SystemState GetState()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT Mode, PolicyAcknowledged, PolicyAcknowledgedAt, PolicyAcknowledgedBy,
                   BackupRetentionDays, BackupRetentionGenerations, ChainRowCount
            FROM SystemMeta WHERE Id=1;
            """);
        string mode;
        var acked = false;
        string? ackedAt = null;
        string? byId = null;
        var days = 7;
        var generations = 7;
        var chainRows = 0;
        using (var reader = cmd.ExecuteReader())
        {
            reader.Read();
            mode = reader.GetString(0);
            acked = reader.GetInt32(1) == 1;
            ackedAt = reader.IsDBNull(2) ? null : reader.GetString(2);
            byId = reader.IsDBNull(3) ? null : reader.GetString(3);
            days = reader.GetInt32(4);
            generations = reader.GetInt32(5);
            chainRows = reader.GetInt32(6);
        }
        return new SystemState
        {
            Mode = mode,
            PolicyAcknowledged = acked,
            PolicyAcknowledgedAt = ackedAt,
            PolicyAcknowledgedBy = byId is null ? null : DisplayName(conn, byId),
            BackupDays = days,
            BackupGenerations = generations,
            ChainEmpty = chainRows == 0,
            IntegrityOk = RememberIntegrity(conn),
            RestoreLocked = RestoreLocked,
            RestoreMessage = _restoreMessage
        };
    }

    public LoginAttempt Login(string? login, string? password)
    {
        var name = Text.NormalizeLogin(login);
        password ??= "";
        using var shared = _gate.EnterShared();
        using var conn = _db.Open();
        using var begin = conn.CreateCommand();
        begin.CommandText = "BEGIN IMMEDIATE;";
        begin.ExecuteNonQuery();
        try
        {
            UserAccount? user = null;
            string? hash = null;
            string? lockout = null;
            var failed = 0;
            using (var cmd = Database.Cmd(conn, """
                SELECT UserId, Login, DisplayName, Role, PasswordHash, Enabled, Stamp, MustChange, LockoutUntil, FailedCount, Version
                FROM Users WHERE Login=$l;
                """, ("$l", name)))
            using (var reader = cmd.ExecuteReader())
            {
                if (reader.Read())
                {
                    user = ReadUser(reader, includeHash: false);
                    hash = reader.GetString(4);
                    lockout = reader.IsDBNull(8) ? null : reader.GetString(8);
                    failed = reader.GetInt32(9);
                }
            }
            if (user is null || !user.Enabled)
            {
                _passwords.Verify("none", _dummyHash, password);
                Commit(conn);
                return new LoginAttempt { Ok = false, Message = Msg.LoginFailed };
            }
            if (lockout != null && DateTimeOffset.TryParse(lockout, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var until) && until > _clock.UtcNow)
            {
                Commit(conn);
                return new LoginAttempt { Ok = false, Message = Msg.LoginFailed };
            }
            if (!_passwords.Verify(user.UserId, hash!, password))
            {
                failed++;
                string? untilText = null;
                if (failed >= 5)
                {
                    untilText = Kst.Canon(_clock.UtcNow.AddMinutes(15));
                    failed = 0;
                }
                using var upd = Database.Cmd(conn,
                    "UPDATE Users SET FailedCount=$f, LockoutUntil=$u WHERE UserId=$id;",
                    ("$f", failed), ("$u", untilText), ("$id", user.UserId));
                upd.ExecuteNonQuery();
                Commit(conn);
                return new LoginAttempt { Ok = false, Message = Msg.LoginFailed };
            }
            using var ok = Database.Cmd(conn,
                "UPDATE Users SET FailedCount=0, LockoutUntil=NULL WHERE UserId=$id;",
                ("$id", user.UserId));
            ok.ExecuteNonQuery();
            Commit(conn);
            return new LoginAttempt { Ok = true, Message = "", User = FindUser(user.UserId) };
        }
        catch
        {
            TryRollback(conn);
            throw;
        }
    }

    public UserAccount? FindUser(string? userId)
    {
        if (string.IsNullOrWhiteSpace(userId)) return null;
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT UserId, Login, DisplayName, Role, PasswordHash, Enabled, Stamp, MustChange, LockoutUntil, FailedCount, Version
            FROM Users WHERE UserId=$id;
            """, ("$id", userId));
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return null;
        var user = ReadUser(reader, includeHash: false);
        using var count = Database.Cmd(conn, "SELECT COUNT(*) FROM Users WHERE Role='관리자' AND Enabled=1;");
        var admins = Convert.ToInt32(count.ExecuteScalar(), CultureInfo.InvariantCulture);
        return new UserAccount
        {
            UserId = user.UserId,
            Login = user.Login,
            DisplayName = user.DisplayName,
            Role = user.Role,
            Enabled = user.Enabled,
            Stamp = user.Stamp,
            MustChange = user.MustChange,
            Version = user.Version,
            IsLastAdmin = user.Role == "관리자" && user.Enabled && admins == 1
        };
    }

    public IReadOnlyList<UserAccount> ListUsers()
    {
        using var conn = _db.Open();
        using var count = Database.Cmd(conn, "SELECT COUNT(*) FROM Users WHERE Role='관리자' AND Enabled=1;");
        var admins = Convert.ToInt32(count.ExecuteScalar(), CultureInfo.InvariantCulture);
        using var cmd = Database.Cmd(conn, """
            SELECT UserId, Login, DisplayName, Role, PasswordHash, Enabled, Stamp, MustChange, LockoutUntil, FailedCount, Version
            FROM Users ORDER BY DisplayName, Login;
            """);
        using var reader = cmd.ExecuteReader();
        var list = new List<UserAccount>();
        while (reader.Read())
        {
            var user = ReadUser(reader, false);
            list.Add(new UserAccount
            {
                UserId = user.UserId,
                Login = user.Login,
                DisplayName = user.DisplayName,
                Role = user.Role,
                Enabled = user.Enabled,
                Stamp = user.Stamp,
                MustChange = user.MustChange,
                Version = user.Version,
                IsLastAdmin = user.Role == "관리자" && user.Enabled && admins == 1
            });
        }
        return list;
    }

    public OpResult SetupAdmin(string? login, string? display, string? password)
    {
        if (AnyUsers())
            return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
        var normalized = Text.NormalizeLogin(login);
        var name = Text.Clean(display, 40, false);
        if (!Text.LoginOk(normalized) || string.IsNullOrEmpty(name))
            return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!Passwords.Acceptable(password, normalized))
            return OpResult.Fail(422, "BAD_INPUT", Msg.PasswordRule);
        using var shared = _gate.EnterShared();
        if (AnyUsers())
            return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
        var now = _clock.UtcNow;
        return Transact(conn =>
        {
            if (ScalarInt(conn, "SELECT COUNT(*) FROM Users;") > 0)
                return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
            var id = InsertUser(conn, normalized, name!, "관리자", password!, mustChange: false);
            AppendHistory(conn, null, now, id, "수정", "계정", id, "최초 관리자 등록", null);
            using var done = Database.Cmd(conn, "UPDATE SystemMeta SET SetupComplete=1 WHERE Id=1;");
            done.ExecuteNonQuery();
            return OpResult.Success(Msg.Saved, userId: id);
        });
    }

    public OpResult CreateAccount(Actor actor, string? login, string? display, string? role, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        var normalized = Text.NormalizeLogin(login);
        var name = Text.Clean(display, 40, false);
        if (!Text.LoginOk(normalized) || string.IsNullOrEmpty(name) || role is not ("담당" or "파트장" or "관리자"))
            return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var password = Text.Secret();
        var now = _clock.UtcNow;
        using var shared = _gate.EnterShared();
        var result = Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "create-user", null);
            if (replay != null) return replay;
            if (ScalarInt(conn, "SELECT COUNT(*) FROM Users WHERE Login=$l;", ("$l", normalized)) > 0)
                return OpResult.Fail(422, "BAD_INPUT", "같은 로그인 이름이 있습니다.");
            var id = InsertUser(conn, normalized, name!, role, password, mustChange: true);
            AppendHistory(conn, null, now, actor.UserId, "수정", "계정", id, "계정 만들기", null);
            var done = OpResult.Success("계정을 만들었습니다. 임시 비밀번호는 이 화면에만 표시됩니다.", userId: id).WithIssued(password);
            StoreReceipt(conn, actor, requestId!, "create-user", null, null, done, now);
            return done;
        });
        if (!result.Ok)
            result.TakeIssuedPassword();
        Log(result.Code, null);
        return result;
    }

    public OpResult DisableAccount(Actor actor, string userId, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId) || !Text.IsHexId(userId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (actor.UserId == userId) return OpResult.Fail(422, "BAD_INPUT", "자기 계정은 끌 수 없습니다.");
        var now = _clock.UtcNow;
        using var shared = _gate.EnterShared();
        return Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "disable-user", userId);
            if (replay != null) return replay;
            var target = FindUserIn(conn, userId);
            if (target is null) return OpResult.Fail(404, "NOT_FOUND", "계정을 찾을 수 없음");
            if (IsLastAdmin(conn, target)) return OpResult.Fail(422, "LAST_ADMIN", Msg.LastAdmin);
            using var upd = Database.Cmd(conn,
                "UPDATE Users SET Enabled=0, Stamp=$s, Version=Version+1 WHERE UserId=$id;",
                ("$s", Text.NewId()), ("$id", userId));
            upd.ExecuteNonQuery();
            AppendHistory(conn, null, now, actor.UserId, "수정", "계정", userId, "계정 끄기", null);
            var done = OpResult.Success("계정을 껐습니다. 기존 세션은 더 이상 쓸 수 없습니다.", userId: userId);
            StoreReceipt(conn, actor, requestId!, "disable-user", userId, null, done, now);
            return done;
        });
    }

    public OpResult ResetAccountPassword(Actor actor, string userId, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId) || !Text.IsHexId(userId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var password = Text.Secret();
        var now = _clock.UtcNow;
        using var shared = _gate.EnterShared();
        var result = Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "reset-password", userId);
            if (replay != null) return replay;
            var target = FindUserIn(conn, userId);
            if (target is null) return OpResult.Fail(404, "NOT_FOUND", "계정을 찾을 수 없음");
            var hash = _passwords.Hash(userId, password);
            using var upd = Database.Cmd(conn, """
                UPDATE Users SET PasswordHash=$h, Stamp=$s, MustChange=1, FailedCount=0, LockoutUntil=NULL, Version=Version+1
                WHERE UserId=$id;
                """, ("$h", hash), ("$s", Text.NewId()), ("$id", userId));
            upd.ExecuteNonQuery();
            AppendHistory(conn, null, now, actor.UserId, "수정", "계정", userId, "비밀번호 초기화", null);
            var done = OpResult.Success("임시 비밀번호를 발급했습니다. 이 화면에만 표시됩니다.", userId: userId).WithIssued(password);
            StoreReceipt(conn, actor, requestId!, "reset-password", userId, null, done, now);
            return done;
        });
        if (!result.Ok) result.TakeIssuedPassword();
        return result;
    }

    public OpResult ChangeAccountRole(Actor actor, string userId, string? role, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (role is not ("담당" or "파트장" or "관리자")) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!RequestOk(requestId) || !Text.IsHexId(userId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var now = _clock.UtcNow;
        using var shared = _gate.EnterShared();
        return Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "role-user", userId);
            if (replay != null) return replay;
            var target = FindUserIn(conn, userId);
            if (target is null) return OpResult.Fail(404, "NOT_FOUND", "계정을 찾을 수 없음");
            if (target.Role == role) return OpResult.Success(Msg.Saved, userId: userId);
            if (target.Role == "관리자" && role != "관리자" && IsLastAdmin(conn, target))
                return OpResult.Fail(422, "LAST_ADMIN", Msg.LastAdmin);
            using var upd = Database.Cmd(conn,
                "UPDATE Users SET Role=$r, Stamp=$s, Version=Version+1 WHERE UserId=$id;",
                ("$r", role), ("$s", Text.NewId()), ("$id", userId));
            upd.ExecuteNonQuery();
            AppendHistory(conn, null, now, actor.UserId, "수정", "계정", userId, "역할 변경", null);
            var done = OpResult.Success("역할을 바꿨습니다. 해당 계정은 다시 로그인해야 합니다.", userId: userId);
            StoreReceipt(conn, actor, requestId!, "role-user", userId, null, done, now);
            return done;
        });
    }

    public OpResult ChangePassword(Actor actor, string? currentPassword, string? newPassword, string? requestId)
    {
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!Passwords.Acceptable(newPassword, actor.Login))
            return OpResult.Fail(422, "BAD_INPUT", Msg.PasswordRule);
        var now = _clock.UtcNow;
        using var shared = _gate.EnterShared();
        return Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "change-password", actor.UserId);
            if (replay != null) return replay;
            using var cmd = Database.Cmd(conn, "SELECT PasswordHash FROM Users WHERE UserId=$id AND Enabled=1;", ("$id", actor.UserId));
            var hash = cmd.ExecuteScalar() as string;
            if (hash is null) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
            if (!_passwords.Verify(actor.UserId, hash, currentPassword ?? ""))
                return OpResult.Fail(422, "BAD_INPUT", Msg.CurrentPassword);
            using var upd = Database.Cmd(conn, """
                UPDATE Users SET PasswordHash=$h, Stamp=$s, MustChange=0, FailedCount=0, LockoutUntil=NULL, Version=Version+1
                WHERE UserId=$id;
                """, ("$h", _passwords.Hash(actor.UserId, newPassword!)), ("$s", Text.NewId()), ("$id", actor.UserId));
            upd.ExecuteNonQuery();
            AppendHistory(conn, null, now, actor.UserId, "수정", "계정", actor.UserId, "비밀번호 변경", null);
            var done = OpResult.Success("비밀번호를 변경했습니다. 다시 로그인하세요.", userId: actor.UserId);
            StoreReceipt(conn, actor, requestId!, "change-password", actor.UserId, null, done, now);
            return done;
        });
    }

    public OpResult AcknowledgePolicy(Actor actor, string? requestId) =>
        Acknowledge(actor, requestId, _clock.UtcNow);

    public OpResult UpdateBackupLimits(Actor actor, int days, int generations, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (days is < 1 or > 3650 || generations is < 1 or > 100)
            return OpResult.Fail(422, "BAD_INPUT", "정리 기준은 1일 이상, 1세대 이상으로 지정합니다. 이 값은 법령상 보존기간이 아닙니다.");
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var now = _clock.UtcNow;
        using var shared = _gate.EnterShared();
        return Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "backup-limits", null);
            if (replay != null) return replay;
            int oldDays, oldGen;
            using (var read = Database.Cmd(conn, "SELECT BackupRetentionDays, BackupRetentionGenerations FROM SystemMeta WHERE Id=1;"))
            using (var reader = read.ExecuteReader())
            {
                reader.Read();
                oldDays = reader.GetInt32(0);
                oldGen = reader.GetInt32(1);
            }
            using var upd = Database.Cmd(conn,
                "UPDATE SystemMeta SET BackupRetentionDays=$d, BackupRetentionGenerations=$g WHERE Id=1;",
                ("$d", days), ("$g", generations));
            upd.ExecuteNonQuery();
            if (oldDays != days || oldGen != generations)
            {
                AppendHistory(conn, null, now, actor.UserId, "수정", "백업", null,
                    string.Create(CultureInfo.InvariantCulture, $"{oldDays}일/{oldGen}개 → {days}일/{generations}개"), null);
            }
            var done = OpResult.Success("백업 정리 기준을 저장했습니다. 회사나 법령의 보존기간이 아니라 이 프로그램이 만든 백업에만 적용됩니다.");
            StoreReceipt(conn, actor, requestId!, "backup-limits", null, null, done, now);
            return done;
        });
    }

    OpResult Acknowledge(Actor actor, string? requestId, DateTimeOffset now)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        using var shared = _gate.EnterShared();
        return Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "ack-policy", null);
            if (replay != null) return replay;
            using var upd = Database.Cmd(conn, """
                UPDATE SystemMeta
                SET PolicyAcknowledged=1, PolicyAcknowledgedAt=$at, PolicyAcknowledgedBy=$by
                WHERE Id=1;
                """, ("$at", Kst.Canon(now)), ("$by", actor.UserId));
            upd.ExecuteNonQuery();
            AppendHistory(conn, null, now, actor.UserId, "수정", "정책", null, "보존 삭제 정책 확인", null);
            var done = OpResult.Success("보존 삭제와 백업 정리 기준을 확인했습니다. 자동 삭제는 이 확인 뒤에만 실행됩니다.");
            StoreReceipt(conn, actor, requestId!, "ack-policy", null, null, done, now);
            return done;
        });
    }

    OpResult? GateReady()
    {
        if (RestoreLocked) return OpResult.Fail(503, "RESTORE_LOCKED", Msg.Restore);
        if (!IntegrityIntact()) return OpResult.Fail(503, "INTEGRITY_FAILED", Msg.Integrity);
        return null;
    }

    string InsertUser(SqliteConnection conn, string login, string display, string role, string password, bool mustChange)
    {
        var id = Text.NewId();
        using var cmd = Database.Cmd(conn, """
            INSERT INTO Users(UserId, Login, DisplayName, Role, PasswordHash, Enabled, Stamp, MustChange, FailedCount, Version)
            VALUES ($id,$login,$name,$role,$hash,1,$stamp,$must,0,1);
            """,
            ("$id", id), ("$login", login), ("$name", display), ("$role", role),
            ("$hash", _passwords.Hash(id, password)), ("$stamp", Text.NewId()), ("$must", mustChange ? 1 : 0));
        cmd.ExecuteNonQuery();
        return id;
    }

    static UserAccount ReadUser(SqliteDataReader reader, bool includeHash) => new()
    {
        UserId = reader.GetString(0),
        Login = reader.GetString(1),
        DisplayName = reader.GetString(2),
        Role = reader.GetString(3),
        Enabled = reader.GetInt32(5) == 1,
        Stamp = reader.GetString(6),
        MustChange = reader.GetInt32(7) == 1,
        Version = reader.GetInt32(10)
    };

    UserAccount? FindUserIn(SqliteConnection conn, string userId)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT UserId, Login, DisplayName, Role, PasswordHash, Enabled, Stamp, MustChange, LockoutUntil, FailedCount, Version
            FROM Users WHERE UserId=$id;
            """, ("$id", userId));
        using var reader = cmd.ExecuteReader();
        return reader.Read() ? ReadUser(reader, false) : null;
    }

    static bool IsLastAdmin(SqliteConnection conn, UserAccount target)
    {
        if (target.Role != "관리자" || !target.Enabled) return false;
        using var cmd = Database.Cmd(conn, "SELECT COUNT(*) FROM Users WHERE Role='관리자' AND Enabled=1;");
        return Convert.ToInt32(cmd.ExecuteScalar(), CultureInfo.InvariantCulture) <= 1;
    }

    string DisplayName(SqliteConnection conn, string userId)
    {
        using var cmd = Database.Cmd(conn, "SELECT DisplayName FROM Users WHERE UserId=$id;", ("$id", userId));
        return cmd.ExecuteScalar() as string ?? "시스템";
    }

    void Log(string code, string? caseId) => _log.LogInformation("event {Code} {CaseId}", code, caseId ?? "-");
}
