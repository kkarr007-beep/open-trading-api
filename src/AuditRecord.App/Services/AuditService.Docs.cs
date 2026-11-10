using System.Globalization;
using AuditRecord.Data;
using AuditRecord.Domain;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Services;

public sealed partial class AuditService
{
    public static string FormatDocumentNo(int year, string code, int seq)
    {
        var width = seq > 99 ? seq.ToString(CultureInfo.InvariantCulture).Length : 2;
        return string.Create(CultureInfo.InvariantCulture, $"{year}-{code}-{seq.ToString("D" + width, CultureInfo.InvariantCulture)}");
    }

    public string PeekDocumentNo(int year, string? category)
    {
        if (year is < 1990 or > 2100 || category is not ("업무진단" or "준법감사")) return "";
        using var conn = _db.Open();
        var code = CodeOf(conn, category);
        if (code is null) return "";
        int last = 0;
        using var cmd = Database.Cmd(conn, "SELECT LastSeq FROM DocCounters WHERE Year=$y AND Code=$c;", ("$y", year), ("$c", code));
        var found = cmd.ExecuteScalar();
        if (found is not null and not DBNull) last = Convert.ToInt32(found, CultureInfo.InvariantCulture);
        return FormatDocumentNo(year, code, last + 1);
    }

    public IReadOnlyList<(string Category, string Code, bool Used)> ListCategoryCodes()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, "SELECT Category, Code, Used FROM CategoryCodes ORDER BY Code;");
        using var reader = cmd.ExecuteReader();
        var list = new List<(string, string, bool)>();
        while (reader.Read())
            list.Add((reader.GetString(0), reader.GetString(1), reader.GetInt32(2) == 1));
        return list;
    }

    public OpResult SetCategoryCode(Actor actor, string? category, string? code, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (category is not ("업무진단" or "준법감사"))
            return OpResult.Fail(422, "BAD_INPUT", "구분코드는 업무진단과 준법감사만 다룹니다.");
        var next = (code ?? "").Trim();
        if (next.Length != 2 || !next.All(char.IsDigit))
            return OpResult.Fail(422, "BAD_INPUT", "구분코드는 숫자 두 자리입니다.");
        return Transact(conn =>
        {
            using var row = Database.Cmd(conn, "SELECT Code, Used FROM CategoryCodes WHERE Category=$c;", ("$c", category));
            using var reader = row.ExecuteReader();
            if (!reader.Read()) return OpResult.Fail(404, "NOT_FOUND", "구분코드를 찾을 수 없습니다.");
            var current = reader.GetString(0);
            var used = reader.GetInt32(1) == 1;
            reader.Close();
            if (current == next) return OpResult.Success("같은 코드입니다.");
            if (used) return OpResult.Fail(422, "BAD_INPUT", "이미 번호를 발급한 구분코드는 바꿀 수 없습니다.");
            try
            {
                using var upd = Database.Cmd(conn, "UPDATE CategoryCodes SET Code=$code WHERE Category=$c AND Used=0;", ("$code", next), ("$c", category));
                if (upd.ExecuteNonQuery() != 1) return OpResult.Fail(422, "BAD_INPUT", "이미 번호를 발급한 구분코드는 바꿀 수 없습니다.");
            }
            catch (SqliteException)
            {
                return OpResult.Fail(422, "BAD_INPUT", "이미 쓰는 구분코드입니다.");
            }
            AppendHistory(conn, null, Now, actor.UserId, "수정", "구분코드", category, category + " " + current + " → " + next, "code:" + requestId);
            return OpResult.Success("구분코드를 저장했습니다.");
        });
    }

    string AllocateDocumentNo(SqliteConnection conn, int year, string category)
    {
        var code = CodeOf(conn, category) ?? throw new InvalidOperationException("category code missing");
        using (var mark = Database.Cmd(conn, "UPDATE CategoryCodes SET Used=1 WHERE Category=$c;", ("$c", category)))
            mark.ExecuteNonQuery();
        int seq;
        using (var upd = Database.Cmd(conn, "UPDATE DocCounters SET LastSeq=LastSeq+1 WHERE Year=$y AND Code=$c;", ("$y", year), ("$c", code)))
        {
            if (upd.ExecuteNonQuery() == 0)
            {
                seq = 1;
                using var ins = Database.Cmd(conn, "INSERT INTO DocCounters(Year, Code, LastSeq) VALUES ($y,$c,1);", ("$y", year), ("$c", code));
                ins.ExecuteNonQuery();
            }
            else
            {
                using var read = Database.Cmd(conn, "SELECT LastSeq FROM DocCounters WHERE Year=$y AND Code=$c;", ("$y", year), ("$c", code));
                seq = Convert.ToInt32(read.ExecuteScalar(), CultureInfo.InvariantCulture);
            }
        }
        return FormatDocumentNo(year, code, seq);
    }

    static string? CodeOf(SqliteConnection conn, string category)
    {
        using var cmd = Database.Cmd(conn, "SELECT Code FROM CategoryCodes WHERE Category=$c;", ("$c", category));
        var found = cmd.ExecuteScalar();
        return found is null or DBNull ? null : (string)found;
    }
}
