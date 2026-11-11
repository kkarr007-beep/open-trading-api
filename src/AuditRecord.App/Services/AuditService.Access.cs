using System.Globalization;
using AuditRecord.Data;
using AuditRecord.Domain;

namespace AuditRecord.Services;

/// <summary>설치 상태. 접속 비밀번호가 이미 있는지, 누구로 들어가는지.</summary>
public sealed class AccessState
{
    public bool Installed { get; init; }
    public bool AccessPasswordSet { get; init; }
    public string? AccessSetAt { get; init; }
    public string TeamName { get; init; } = "";
}

public sealed partial class AuditService
{
    /// <summary>
    /// 간편 설치. 서버의 127.0.0.1 브라우저에서 한 번만 돈다.
    /// 관리자 계정 하나와 공용 접속 비밀번호를 같은 값으로 둔다. 다른 컴퓨터는 그 비밀번호만 눌러 들어온다.
    /// </summary>
    public OpResult InstallSimple(string? teamName, string? password, string? confirm)
    {
        if (AnyUsers()) return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
        var name = Text.Clean(teamName, 40, false);
        if (string.IsNullOrEmpty(name)) name = "감사파트";
        if (!string.Equals(password, confirm, StringComparison.Ordinal))
            return OpResult.Fail(422, "BAD_INPUT", "비밀번호 확인이 일치하지 않습니다.");
        if (!Passwords.Acceptable(password, "admin"))
            return OpResult.Fail(422, "BAD_INPUT", Msg.PasswordRule);
        using var shared = _gate.EnterShared();
        if (AnyUsers()) return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
        var now = _clock.UtcNow;
        return Transact(conn =>
        {
            if (ScalarInt(conn, "SELECT COUNT(*) FROM Users;") > 0)
                return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
            var id = InsertUser(conn, "admin", name!, "관리자", password!, mustChange: false);
            using (var done = Database.Cmd(conn, """
                UPDATE SystemMeta SET SetupComplete=1, AccessHash=$h, AccessSetAt=$at, AccessUserId=$u,
                  AccessFailed=0, AccessLockUntil=NULL
                WHERE Id=1;
                """, ("$h", _passwords.Hash("access", password!)), ("$at", Kst.Canon(now)), ("$u", id)))
                done.ExecuteNonQuery();
            AppendHistory(conn, null, now, id, "수정", "계정", id, "간편 설치: 관리자와 접속 비밀번호 등록", null);
            return OpResult.Success("설치를 마쳤습니다.", userId: id);
        });
    }

    public AccessState GetAccessState()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT m.AccessHash, m.AccessSetAt, IFNULL(u.DisplayName, '')
            FROM SystemMeta m LEFT JOIN Users u ON u.UserId = m.AccessUserId
            WHERE m.Id=1;
            """);
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return new AccessState();
        var hash = reader.IsDBNull(0) ? null : reader.GetString(0);
        return new AccessState
        {
            Installed = AnyUsers(),
            AccessPasswordSet = !string.IsNullOrEmpty(hash),
            AccessSetAt = reader.IsDBNull(1) ? null : DisplayStamp(reader.GetString(1)),
            TeamName = reader.GetString(2)
        };
    }

    /// <summary>
    /// 접속 비밀번호 하나로 들어온다. 로그인 이름은 묻지 않는다.
    /// 다섯 번 틀리면 15분 쉰다. 그 셈은 계정이 아니라 이 서버 단위로 둔다.
    /// </summary>
    public LoginAttempt LoginWithAccessPassword(string? password)
    {
        password ??= "";
        using var shared = _gate.EnterShared();
        using var conn = _db.Open();
        using (var begin = conn.CreateCommand())
        {
            begin.CommandText = "BEGIN IMMEDIATE;";
            begin.ExecuteNonQuery();
        }
        try
        {
            string? hash = null;
            string? userId = null;
            string? lockUntil = null;
            var failed = 0;
            using (var cmd = Database.Cmd(conn, "SELECT AccessHash, AccessUserId, AccessLockUntil, AccessFailed FROM SystemMeta WHERE Id=1;"))
            using (var reader = cmd.ExecuteReader())
            {
                if (reader.Read())
                {
                    hash = reader.IsDBNull(0) ? null : reader.GetString(0);
                    userId = reader.IsDBNull(1) ? null : reader.GetString(1);
                    lockUntil = reader.IsDBNull(2) ? null : reader.GetString(2);
                    failed = reader.GetInt32(3);
                }
            }
            if (string.IsNullOrEmpty(hash) || string.IsNullOrEmpty(userId))
            {
                _passwords.Verify("access", _dummyHash, password);
                Commit(conn);
                return new LoginAttempt { Ok = false, Message = "접속 비밀번호가 아직 없습니다. 서버에서 설치를 먼저 마치세요." };
            }
            if (lockUntil != null
                && DateTimeOffset.TryParse(lockUntil, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var until)
                && until > _clock.UtcNow)
            {
                Commit(conn);
                return new LoginAttempt { Ok = false, Message = Msg.LoginFailed };
            }
            if (!_passwords.Verify("access", hash, password))
            {
                failed++;
                string? untilText = null;
                if (failed >= 5)
                {
                    untilText = Kst.Canon(_clock.UtcNow.AddMinutes(15));
                    failed = 0;
                }
                using var bad = Database.Cmd(conn, "UPDATE SystemMeta SET AccessFailed=$f, AccessLockUntil=$u WHERE Id=1;",
                    ("$f", failed), ("$u", untilText));
                bad.ExecuteNonQuery();
                Commit(conn);
                return new LoginAttempt { Ok = false, Message = Msg.LoginFailed };
            }
            using (var ok = Database.Cmd(conn, "UPDATE SystemMeta SET AccessFailed=0, AccessLockUntil=NULL WHERE Id=1;"))
                ok.ExecuteNonQuery();
            Commit(conn);
            var user = FindUser(userId);
            if (user is null || !user.Enabled)
                return new LoginAttempt { Ok = false, Message = Msg.LoginFailed };
            return new LoginAttempt { Ok = true, Message = "", User = user };
        }
        catch
        {
            TryRollback(conn);
            throw;
        }
    }

    /// <summary>접속 비밀번호를 바꾼다. 관리자만. 기존 세션은 그대로 두고 다음 접속부터 새 값을 쓴다.</summary>
    public OpResult ChangeAccessPassword(Actor actor, string? current, string? next, string? confirm, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!string.Equals(next, confirm, StringComparison.Ordinal))
            return OpResult.Fail(422, "BAD_INPUT", "비밀번호 확인이 일치하지 않습니다.");
        if (!Passwords.Acceptable(next, "admin"))
            return OpResult.Fail(422, "BAD_INPUT", Msg.PasswordRule);
        var now = _clock.UtcNow;
        return Transact(conn =>
        {
            string? hash;
            using (var read = Database.Cmd(conn, "SELECT AccessHash FROM SystemMeta WHERE Id=1;"))
                hash = read.ExecuteScalar() as string;
            if (!string.IsNullOrEmpty(hash) && !_passwords.Verify("access", hash, current ?? ""))
                return OpResult.Fail(422, "BAD_INPUT", "지금 쓰는 접속 비밀번호가 다릅니다.");
            using (var upd = Database.Cmd(conn, """
                UPDATE SystemMeta SET AccessHash=$h, AccessSetAt=$at, AccessFailed=0, AccessLockUntil=NULL WHERE Id=1;
                """, ("$h", _passwords.Hash("access", next!)), ("$at", Kst.Canon(now))))
                upd.ExecuteNonQuery();
            AppendHistory(conn, null, now, actor.UserId, "수정", "계정", null, "접속 비밀번호 변경", null);
            return OpResult.Success("접속 비밀번호를 바꿨습니다.");
        });
    }

    /// <summary>
    /// 예시 모드에서만 쓴다. 예시 관리자의 비밀번호를 그대로 접속 비밀번호로 둔다.
    /// 그 값은 demo-access.local에만 있고 화면이나 문서에 다시 적지 않는다.
    /// </summary>
    internal void SetDemoAccessPassword(string userId, string demoAccessPath)
    {
        if (_paths.Mode != "demo" || !File.Exists(demoAccessPath)) return;
        var line = File.ReadAllLines(demoAccessPath).FirstOrDefault(l => l.StartsWith("demo-admin ", StringComparison.Ordinal));
        if (line is null) return;
        var password = line["demo-admin ".Length..].Trim();
        if (password.Length == 0) return;
        Transact(conn =>
        {
            using var upd = Database.Cmd(conn, """
                UPDATE SystemMeta SET AccessHash=$h, AccessSetAt=$at, AccessUserId=$u, AccessFailed=0, AccessLockUntil=NULL
                WHERE Id=1;
                """, ("$h", _passwords.Hash("access", password)), ("$at", Kst.Canon(Now)), ("$u", userId));
            upd.ExecuteNonQuery();
            return OpResult.Success("기록");
        });
    }
}
