using System.Globalization;
using System.Text;
using AuditRecord.Data;
using AuditRecord.Domain;

namespace AuditRecord.Services;

public sealed class DisciplineRow
{
    public string Id { get; init; } = "";
    public string? CaseId { get; init; }
    public string? CaseTitle { get; init; }
    public int Year { get; init; }
    public string WhoName { get; init; } = "";
    public string WhoMasked { get; init; } = "";
    public string WhenText { get; init; } = "";
    public string WhereText { get; init; } = "";
    public string WhatText { get; init; } = "";
    public string HowText { get; init; } = "";
    public string WhyText { get; init; } = "";
    public string Sanction { get; init; } = "";
    public string Outcome { get; init; } = "";
    public string RuleText { get; init; } = "";
    public string Status { get; init; } = "";
    public bool Hold { get; init; }
    public int Version { get; init; }
    public List<HistoryRow> History { get; init; } = [];
}

public sealed partial class AuditService
{
    public static string MaskPerson(string name)
    {
        var text = name.Trim();
        if (text.Length <= 1) return "*";
        var chars = text.ToCharArray();
        if (chars.Length == 2) { chars[1] = '*'; return new string(chars); }
        for (var i = 1; i < chars.Length - 1; i++) chars[i] = '*';
        return new string(chars);
    }

    public bool CanReadDiscipline(Actor actor) => actor.IsLead;

    public List<DisciplineRow> ListDiscipline(string? text, string? sanction)
    {
        using var conn = _db.Open();
        var sql = """
            SELECT d.DisciplineId, d.CaseId, c.Title, d.Year, d.WhoName, d.WhenText, d.WhereText,
                   d.WhatText, d.HowText, d.WhyText, d.Sanction, d.Outcome, d.Status, d.Hold, d.Version
            FROM Discipline d LEFT JOIN Cases c ON c.CaseId=d.CaseId
            WHERE 1=1
            """;
        var args = new List<(string, object?)>();
        if (!string.IsNullOrWhiteSpace(sanction))
        {
            sql += " AND d.Sanction=$s";
            args.Add(("$s", sanction.Trim()));
        }
        if (!string.IsNullOrWhiteSpace(text))
        {
            sql += " AND (d.WhoName LIKE $q ESCAPE '\\' OR d.WhatText LIKE $q ESCAPE '\\' OR d.WhereText LIKE $q ESCAPE '\\' OR IFNULL(c.Title,'') LIKE $q ESCAPE '\\')";
            args.Add(("$q", Text.LikeContains(text.Trim())));
        }
        sql += " ORDER BY d.WhenText DESC, d.DisciplineId;";
        using var cmd = Database.Cmd(conn, sql, args.ToArray());
        using var reader = cmd.ExecuteReader();
        var list = new List<DisciplineRow>();
        while (reader.Read())
        {
            var who = reader.GetString(4);
            list.Add(new DisciplineRow
            {
                Id = reader.GetString(0),
                CaseId = reader.IsDBNull(1) ? null : reader.GetString(1),
                CaseTitle = reader.IsDBNull(2) ? null : reader.GetString(2),
                Year = reader.GetInt32(3),
                WhoName = who,
                WhoMasked = MaskPerson(who),
                WhenText = reader.GetString(5),
                WhereText = reader.GetString(6),
                WhatText = reader.GetString(7),
                HowText = reader.GetString(8),
                WhyText = reader.GetString(9),
                Sanction = reader.GetString(10),
                Outcome = reader.GetString(11),
                Status = reader.GetString(12),
                Hold = reader.GetInt32(13) == 1,
                Version = reader.GetInt32(14)
            });
        }
        return list;
    }

    public DisciplineRow? OpenDiscipline(Actor actor, string? id, bool recordView)
    {
        if (!CanReadDiscipline(actor) || !Text.IsHexId(id)) return null;
        if (recordView)
        {
            var recorded = Transact(conn =>
            {
                var existing = ReadDiscipline(conn, id!);
                if (existing is null) return OpResult.Fail(404, "NOT_FOUND", "징계기록을 찾을 수 없습니다.");
                AppendHistory(conn, existing.CaseId, Now, actor.UserId, "열람", "징계", id, "징계기록 열람: " + existing.WhoMasked, null);
                return OpResult.Success("기록");
            });
            if (!recorded.Ok) return null;
        }
        using var conn = _db.Open();
        return ReadDiscipline(conn, id!);
    }

    public OpResult SaveDiscipline(
        Actor actor, string? id, int version, string? caseId, int year,
        string? who, string? when, string? where, string? what, string? how, string? why,
        string? sanction, string? outcome, string? rule, string? requestId)
    {
        if (!CanReadDiscipline(actor)) return OpResult.Fail(403, "FORBIDDEN", "징계기록은 파트장과 관리자만 다룹니다.");
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var person = Text.Clean(who, 40, false);
        var whenText = Text.Clean(when, 40, false);
        var whereText = Text.Clean(where, 80, false);
        var whatText = Text.Clean(what, 500, false);
        var howText = Text.Clean(how, 500, false);
        var whyText = Text.Clean(why, 500, false);
        var sanctionText = Text.Clean(sanction, 40, false);
        var outcomeText = Text.Clean(outcome, 80, false) ?? "";
        var ruleText = Text.Clean(rule, 240, false) ?? "";
        if (string.IsNullOrWhiteSpace(person) || string.IsNullOrWhiteSpace(whenText) || string.IsNullOrWhiteSpace(whereText)
            || string.IsNullOrWhiteSpace(whatText) || string.IsNullOrWhiteSpace(howText) || string.IsNullOrWhiteSpace(whyText)
            || string.IsNullOrWhiteSpace(sanctionText))
            return OpResult.Fail(422, "BAD_INPUT", "누가, 언제, 어디서, 무엇을, 어떻게, 왜, 처분 종류를 입력하세요.");
        if (year is < 1990 or > 2100) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var linked = string.IsNullOrWhiteSpace(caseId) ? null : caseId.Trim();
        if (linked != null && !Text.IsHexId(linked)) return OpResult.Fail(422, "BAD_INPUT", "연결할 감사 건을 확인하세요.");
        return Transact(conn =>
        {
            if (linked != null && LoadCase(conn, linked) is null)
                return OpResult.Fail(422, "BAD_INPUT", "연결할 감사 건이 없습니다.");
            if (string.IsNullOrEmpty(id))
            {
                var newId = Text.NewId();
                var at = Kst.Canon(Now);
                using var insert = Database.Cmd(conn, """
                    INSERT INTO Discipline(
                      DisciplineId, CaseId, Year, WhoName, WhenText, WhereText, WhatText, HowText, WhyText,
                      Sanction, Outcome, RuleText, Status, Hold, RetentionYears, Version, CreatedAt, CreatedBy)
                    VALUES ($id,$case,$year,$who,$when,$where,$what,$how,$why,$sanction,$outcome,$rule,'초안',0,5,1,$at,$by);
                    """,
                    ("$id", newId), ("$case", linked), ("$year", year), ("$who", person), ("$when", whenText),
                    ("$where", whereText), ("$what", whatText), ("$how", howText), ("$why", whyText),
                    ("$sanction", sanctionText), ("$outcome", outcomeText), ("$rule", ruleText),
                    ("$at", at), ("$by", actor.UserId));
                insert.ExecuteNonQuery();
                AppendHistory(conn, linked, Now, actor.UserId, "등록", "징계", newId, "징계기록 등록", "disc:" + requestId);
                return OpResult.Success("징계기록을 저장했습니다.", linked, 1, extra: newId);
            }
            if (!Text.IsHexId(id)) return OpResult.Fail(404, "NOT_FOUND", "징계기록을 찾을 수 없습니다.");
            var current = ReadDiscipline(conn, id);
            if (current is null) return OpResult.Fail(404, "NOT_FOUND", "징계기록을 찾을 수 없습니다.");
            if (current.Status == "삭제됨") return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", "삭제된 징계기록은 고칠 수 없습니다.");
            if (current.Version != version) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var upd = Database.Cmd(conn, """
                UPDATE Discipline SET CaseId=$case, Year=$year, WhoName=$who, WhenText=$when, WhereText=$where,
                  WhatText=$what, HowText=$how, WhyText=$why, Sanction=$sanction, Outcome=$outcome, RuleText=$rule,
                  Version=Version+1
                WHERE DisciplineId=$id AND Version=$ver;
                """,
                ("$case", linked), ("$year", year), ("$who", person), ("$when", whenText), ("$where", whereText),
                ("$what", whatText), ("$how", howText), ("$why", whyText), ("$sanction", sanctionText),
                ("$outcome", outcomeText), ("$rule", ruleText), ("$id", id), ("$ver", version));
            if (upd.ExecuteNonQuery() != 1) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, linked, Now, actor.UserId, "수정", "징계", id, "징계기록 수정", null);
            return OpResult.Success("징계기록을 저장했습니다.", linked, version + 1, extra: id);
        });
    }

    public OpResult SetDisciplineHold(Actor actor, string id, int version, bool hold, string? reason, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var why = Text.Clean(reason, 240, false);
        if (string.IsNullOrWhiteSpace(why)) return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
        var gate = GateReady();
        if (gate != null) return gate;
        return Transact(conn =>
        {
            var row = ReadDiscipline(conn, id);
            if (row is null) return OpResult.Fail(404, "NOT_FOUND", "징계기록을 찾을 수 없습니다.");
            if (row.Version != version) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var upd = Database.Cmd(conn, "UPDATE Discipline SET Hold=$h, Version=Version+1 WHERE DisciplineId=$id AND Version=$ver;",
                ("$h", hold ? 1 : 0), ("$id", id), ("$ver", version));
            if (upd.ExecuteNonQuery() != 1) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, row.CaseId, Now, actor.UserId, hold ? "보류" : "보류해제", "징계", id, (hold ? "삭제 보류. 사유: " : "보류 해제. 사유: ") + why, null);
            return OpResult.Success(hold ? "삭제를 보류했습니다." : "보류를 해제했습니다.", extra: id);
        });
    }

    public string ExportDisciplineCsv(Actor actor, string? requestId)
    {
        if (!actor.IsAdmin) return "";
        var rows = ListDiscipline(null, null);
        Transact(conn =>
        {
            AppendHistory(conn, null, Now, actor.UserId, "내보내기", "징계", null, "징계기록 CSV " + rows.Count.ToString(CultureInfo.InvariantCulture) + "건", "disc-csv:" + requestId);
            return OpResult.Success("기록");
        });
        var sb = new StringBuilder();
        sb.Append('\uFEFF');
        sb.AppendLine("연도,누가,언제,어디서,무엇을,어떻게,왜,처분,결과,상태,관련건");
        foreach (var row in rows)
        {
            sb.Append(row.Year).Append(',').Append(Csv(row.WhoName)).Append(',').Append(Csv(row.WhenText)).Append(',')
                .Append(Csv(row.WhereText)).Append(',').Append(Csv(row.WhatText)).Append(',').Append(Csv(row.HowText)).Append(',')
                .Append(Csv(row.WhyText)).Append(',').Append(Csv(row.Sanction)).Append(',').Append(Csv(row.Outcome)).Append(',')
                .Append(Csv(row.Status)).Append(',').Append(Csv(row.CaseTitle ?? "")).Append('\n');
        }
        return sb.ToString();
    }

    public int PurgeDueDiscipline(DateTimeOffset now)
    {
        var today = Kst.DateOf(now);
        return Transact(conn =>
        {
            using var cmd = Database.Cmd(conn, """
                SELECT DisciplineId, ConfirmedOn, RetentionYears, Hold, CaseId
                FROM Discipline WHERE Status='확정' AND ConfirmedOn IS NOT NULL;
                """);
            using var reader = cmd.ExecuteReader();
            var due = new List<(string Id, string? CaseId)>();
            while (reader.Read())
            {
                if (reader.GetInt32(3) == 1) continue;
                var anchor = DateOnly.Parse(reader.GetString(1), CultureInfo.InvariantCulture);
                if (!Kst.IsDue(anchor, reader.GetInt32(2), now)) continue;
                due.Add((reader.GetString(0), reader.IsDBNull(4) ? null : reader.GetString(4)));
            }
            reader.Close();
            foreach (var item in due)
            {
                using var upd = Database.Cmd(conn, """
                    UPDATE Discipline SET WhoName='삭제됨', WhatText='', HowText='', WhyText='', Outcome='',
                      RuleText='', Status='삭제됨', Version=Version+1
                    WHERE DisciplineId=$id AND Status='확정' AND Hold=0;
                    """, ("$id", item.Id));
                upd.ExecuteNonQuery();
                AppendHistory(conn, item.CaseId, now, null, "자료삭제", "징계", item.Id, "징계기록 보존 만료", null);
            }
            _ = today;
            return OpResult.Success("기록", extra: due.Count.ToString(CultureInfo.InvariantCulture));
        }).Ok ? 1 : 0;
    }

    public void SeedDisciplineExamples()
    {
        using (var conn = _db.Open())
        using (var count = Database.Cmd(conn, "SELECT COUNT(*) FROM Discipline;"))
        {
            if (Convert.ToInt32(count.ExecuteScalar(), CultureInfo.InvariantCulture) > 0) return;
        }
        var lead = ActorByLogin("demo-lead");
        if (string.IsNullOrEmpty(lead.UserId)) return;
        SaveDiscipline(lead, null, 0, null, 2024, "가상갑", "2024-04-02", "예시부서 회의실", "허위 출장 보고", "보고서를 두 번 제출", "점검 기준을 따르지 않음", "견책", "서면 경고", "예시 내부 기준 제3호", Text.NewId());
        SaveDiscipline(lead, null, 0, null, 2025, "가상을", "2025-06-18", "예시창고", "물품 반출 기록 누락", "출고 장부를 나중에 고침", "재고 담당 확인을 빠뜨림", "감봉", "1개월", "예시 내부 기준 제5호", Text.NewId());
        SaveDiscipline(lead, null, 0, null, 2023, "가상병", "2023-11-09", "예시현장", "안전 수칙 미준수", "보호구 없이 작업", "작업 전 확인을 생략", "경고", "재교육", "예시 내부 기준 제1호", Text.NewId());
    }

    DisciplineRow? ReadDiscipline(Microsoft.Data.Sqlite.SqliteConnection conn, string id)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT d.DisciplineId, d.CaseId, c.Title, d.Year, d.WhoName, d.WhenText, d.WhereText,
                   d.WhatText, d.HowText, d.WhyText, d.Sanction, d.Outcome, d.RuleText, d.Status, d.Hold, d.Version
            FROM Discipline d LEFT JOIN Cases c ON c.CaseId=d.CaseId
            WHERE d.DisciplineId=$id;
            """, ("$id", id));
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return null;
        var who = reader.GetString(4);
        var row = new DisciplineRow
        {
            Id = reader.GetString(0),
            CaseId = reader.IsDBNull(1) ? null : reader.GetString(1),
            CaseTitle = reader.IsDBNull(2) ? null : reader.GetString(2),
            Year = reader.GetInt32(3),
            WhoName = who,
            WhoMasked = MaskPerson(who),
            WhenText = reader.GetString(5),
            WhereText = reader.GetString(6),
            WhatText = reader.GetString(7),
            HowText = reader.GetString(8),
            WhyText = reader.GetString(9),
            Sanction = reader.GetString(10),
            Outcome = reader.GetString(11),
            RuleText = reader.GetString(12),
            Status = reader.GetString(13),
            Hold = reader.GetInt32(14) == 1,
            Version = reader.GetInt32(15)
        };
        reader.Close();
        row.History.AddRange(LoadTargetHistory(conn, row.Id));
        return row;
    }

    List<HistoryRow> LoadTargetHistory(Microsoft.Data.Sqlite.SqliteConnection conn, string targetId)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT h.Seq, h.At, h.Action, h.SummarySafe, u.DisplayName
            FROM History h LEFT JOIN Users u ON u.UserId=h.ActorId
            WHERE h.TargetId=$id ORDER BY h.Seq;
            """, ("$id", targetId));
        using var reader = cmd.ExecuteReader();
        var list = new List<HistoryRow>();
        while (reader.Read())
        {
            list.Add(new HistoryRow
            {
                Seq = reader.GetInt64(0),
                AtDisplay = DisplayStamp(reader.GetString(1)),
                Action = reader.GetString(2),
                Summary = reader.GetString(3),
                ActorName = reader.IsDBNull(4) ? "" : reader.GetString(4)
            });
        }
        return list;
    }

    static string Csv(string? value)
    {
        var text = value ?? "";
        var neutralize = text.Length > 0 && text[0] is '=' or '+' or '-' or '@' or '\t' or '\r';
        if (neutralize) text = "'" + text;
        text = text.Replace("\"", "\"\"", StringComparison.Ordinal);
        if (neutralize || text.Contains('"', StringComparison.Ordinal) || text.Contains(',', StringComparison.Ordinal) || text.Contains('\n', StringComparison.Ordinal) || text.Contains('\r', StringComparison.Ordinal))
            return "\"" + text + "\"";
        return text;
    }
}
