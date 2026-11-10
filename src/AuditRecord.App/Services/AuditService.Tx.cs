using System.Globalization;
using AuditRecord.Data;
using AuditRecord.Domain;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Services;

public sealed partial class AuditService
{
    OpResult Transact(Func<SqliteConnection, OpResult> body)
    {
        lock (_writer)
        {
            using var conn = _db.Open();
            try
            {
                using (var begin = conn.CreateCommand())
                {
                    begin.CommandText = "BEGIN IMMEDIATE;";
                    begin.ExecuteNonQuery();
                }
                var result = body(conn);
                using var end = conn.CreateCommand();
                end.CommandText = result.Ok ? "COMMIT;" : "ROLLBACK;";
                end.ExecuteNonQuery();
                return result;
            }
            catch (Exception ex)
            {
                TryRollback(conn);
                if (ex is SqliteException sqlite && sqlite.SqliteErrorCode is 5 or 6)
                    return OpResult.Fail(503, "BUSY_RETRY", Msg.Busy);
                _log.LogWarning("tx {Type}", ex.GetType().Name);
                throw;
            }
        }
    }

    static void Commit(SqliteConnection conn)
    {
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "COMMIT;";
        cmd.ExecuteNonQuery();
    }

    static void TryRollback(SqliteConnection conn)
    {
        try
        {
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "ROLLBACK;";
            cmd.ExecuteNonQuery();
        }
        catch (SqliteException)
        {
            // already closed
        }
    }

    static bool RequestOk(string? requestId) =>
        requestId is { Length: >= 16 and <= 80 } && requestId.All(c => char.IsAsciiLetterOrDigit(c) || c == '-');

    OpResult? Replay(SqliteConnection conn, Actor actor, string requestId, string command, string? caseId)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT ActorId, CommandKind, CaseId, OutcomeCode, HttpStatus, ResultVersion, MessageKey
            FROM CommandReceipts WHERE RequestId=$id;
            """, ("$id", requestId));
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return null;
        var storedActor = reader.GetString(0);
        var storedCommand = reader.GetString(1);
        var storedCase = reader.IsDBNull(2) ? null : reader.GetString(2);
        if (storedActor != actor.UserId || storedCommand != command)
            return OpResult.Fail(409, "REQUEST_REUSED", Msg.RequestReused);
        if (caseId != null && storedCase != null && !string.Equals(storedCase, caseId, StringComparison.Ordinal))
            return OpResult.Fail(409, "REQUEST_REUSED", Msg.RequestReused);
        var code = reader.GetString(3);
        var status = reader.GetInt32(4);
        int? version = reader.IsDBNull(5) ? null : reader.GetInt32(5);
        var message = reader.GetString(6);
        return code == "OK"
            ? OpResult.Success(string.IsNullOrWhiteSpace(message) ? Msg.AlreadyDone : message, storedCase, version)
            : OpResult.Fail(status, code, string.IsNullOrWhiteSpace(message) ? Msg.BadInput : message);
    }

    static void StoreReceipt(SqliteConnection conn, Actor actor, string requestId, string command, string? caseId, int? expectedVersion, OpResult result, DateTimeOffset now)
    {
        using var cmd = Database.Cmd(conn, """
            INSERT INTO CommandReceipts(
              RequestId, ActorId, CaseId, CommandKind, ExpectedVersion, OutcomeCode, HttpStatus, ResultVersion, MessageKey, CompletedAt)
            VALUES ($id,$actor,$case,$cmd,$exp,$code,$status,$ver,$msg,$at);
            """,
            ("$id", requestId),
            ("$actor", actor.UserId),
            ("$case", caseId ?? result.CaseId),
            ("$cmd", command),
            ("$exp", expectedVersion),
            ("$code", result.Code),
            ("$status", result.Status),
            ("$ver", result.Version),
            ("$msg", result.Ok ? result.Message : result.Message),
            ("$at", Kst.Canon(now)));
        cmd.ExecuteNonQuery();
    }

    long AppendHistory(SqliteConnection conn, string? caseId, DateTimeOffset now, string? actorId, string action, string targetKind, string? targetId, string summary, string? workKey)
    {
        if (workKey != null)
        {
            using var existing = Database.Cmd(conn, "SELECT Seq FROM History WHERE WorkKey=$w;", ("$w", workKey));
            var found = existing.ExecuteScalar();
            if (found != null && found != DBNull.Value)
                return Convert.ToInt64(found, CultureInfo.InvariantCulture);
        }
        long seq;
        byte[] prev;
        using (var meta = Database.Cmd(conn, "SELECT ChainTailSeq, ChainTailHash FROM SystemMeta WHERE Id=1;"))
        using (var reader = meta.ExecuteReader())
        {
            reader.Read();
            seq = reader.GetInt64(0) + 1;
            prev = (byte[])reader.GetValue(1);
        }
        var at = Kst.Canon(now);
        var hash = HashChain.Compute(seq, caseId, at, actorId, action, targetKind, targetId, summary, prev);
        using var insert = Database.Cmd(conn, """
            INSERT INTO History(Seq, CaseId, At, ActorId, Action, TargetKind, TargetId, SummarySafe, CanonicalVersion, PrevHash, RowHash, WorkKey)
            VALUES ($seq,$case,$at,$actor,$action,$kind,$tid,$sum,1,$prev,$hash,$wk);
            """,
            ("$seq", seq), ("$case", caseId), ("$at", at), ("$actor", actorId), ("$action", action),
            ("$kind", targetKind), ("$tid", targetId), ("$sum", summary), ("$prev", prev), ("$hash", hash), ("$wk", workKey));
        insert.ExecuteNonQuery();
        using var update = Database.Cmd(conn,
            "UPDATE SystemMeta SET ChainTailSeq=$seq, ChainTailHash=$hash, ChainRowCount=ChainRowCount+1 WHERE Id=1;",
            ("$seq", seq), ("$hash", hash));
        update.ExecuteNonQuery();
        return seq;
    }

    bool CheckChain(SqliteConnection conn, out string reason)
    {
        reason = "";
        var rows = new List<HistRow>();
        using (var cmd = Database.Cmd(conn, """
            SELECT Seq, CaseId, At, ActorId, Action, TargetKind, TargetId, SummarySafe, PrevHash, RowHash
            FROM History ORDER BY Seq;
            """))
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                rows.Add(new HistRow(
                    reader.GetInt64(0),
                    reader.IsDBNull(1) ? null : reader.GetString(1),
                    reader.GetString(2),
                    reader.IsDBNull(3) ? null : reader.GetString(3),
                    reader.GetString(4),
                    reader.GetString(5),
                    reader.IsDBNull(6) ? null : reader.GetString(6),
                    reader.GetString(7),
                    (byte[])reader.GetValue(8),
                    (byte[])reader.GetValue(9)));
            }
        }
        using var meta = Database.Cmd(conn, "SELECT ChainTailSeq, ChainTailHash, ChainRowCount FROM SystemMeta WHERE Id=1;");
        using var metaReader = meta.ExecuteReader();
        if (!metaReader.Read())
        {
            reason = "meta";
            return false;
        }
        var tailSeq = metaReader.GetInt64(0);
        var tailHash = (byte[])metaReader.GetValue(1);
        var count = metaReader.GetInt32(2);
        if (rows.Count == 0)
        {
            if (tailSeq != 0 || count != 0 || !HashChain.FixedEquals(tailHash, HashChain.Genesis()))
            {
                reason = "empty";
                return false;
            }
            return true;
        }
        var prev = HashChain.Genesis();
        long expected = 1;
        foreach (var row in rows)
        {
            if (row.Seq != expected)
            {
                reason = "gap";
                return false;
            }
            if (!HashChain.FixedEquals(row.Prev, prev))
            {
                reason = "prev";
                return false;
            }
            var calc = HashChain.Compute(row.Seq, row.CaseId, row.At, row.ActorId, row.Action, row.Kind, row.TargetId, row.Summary, row.Prev);
            if (!HashChain.FixedEquals(calc, row.Hash))
            {
                reason = "row";
                return false;
            }
            prev = row.Hash;
            expected++;
        }
        var last = rows[^1];
        if (tailSeq != last.Seq || count != rows.Count || !HashChain.FixedEquals(tailHash, last.Hash))
        {
            reason = "tail";
            return false;
        }
        return true;
    }

    static int ScalarInt(SqliteConnection conn, string sql, params (string Name, object? Value)[] args)
    {
        using var cmd = Database.Cmd(conn, sql, args);
        return Convert.ToInt32(cmd.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    sealed record HistRow(long Seq, string? CaseId, string At, string? ActorId, string Action, string Kind, string? TargetId, string Summary, byte[] Prev, byte[] Hash);
}
