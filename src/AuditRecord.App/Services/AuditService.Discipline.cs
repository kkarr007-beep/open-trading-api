using System.Globalization;
using System.Text;
using AuditRecord.Data;
using AuditRecord.Domain;

namespace AuditRecord.Services;

/// <summary>
/// 징계기록 등재 한 줄. 화면과 CSV의 머리글 순서와 같다.
/// 순번·사번·성명·부서·직위·위반일자·위반내용·관련규정·징계종류·징계일자·징계기간·결정기관·근거문서번호·상태·비고·등록일자·담당자.
/// </summary>
public sealed class DisciplineRow
{
    public string Id { get; init; } = "";
    public int Seq { get; init; }
    public string? CaseId { get; init; }
    public string? CaseTitle { get; init; }
    public string? CaseDocumentNo { get; init; }
    public int Year { get; init; }
    public string EmployeeNo { get; init; } = "";
    public string PersonName { get; init; } = "";
    public string PersonMasked { get; init; } = "";
    public string Department { get; init; } = "";
    public string Position { get; init; } = "";
    public string ViolationDate { get; init; } = "";
    public string ViolationText { get; init; } = "";
    public string RuleText { get; init; } = "";
    public string SanctionType { get; init; } = "";
    public string SanctionDate { get; init; } = "";
    public string SanctionTerm { get; init; } = "";
    public string DecisionBody { get; init; } = "";
    public string DocNo { get; init; } = "";
    public string Status { get; init; } = "";
    public string Remark { get; init; } = "";
    public string CreatedDate { get; init; } = "";
    public string OwnerName { get; init; } = "";
    public bool Hold { get; init; }
    public int Version { get; init; }
    public List<HistoryRow> History { get; init; } = [];
}

/// <summary>감사 건을 감사번호로 고를 때 쓰는 한 줄.</summary>
public sealed record CaseRefOption(string CaseId, string DocumentNo, string Title, int Year, string Category);

public sealed partial class AuditService
{
    public static readonly string[] SanctionTypes = ["견책", "감봉", "정직", "강등", "해임", "파면", "경고", "주의"];

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

    const string DisciplineSelect = """
        SELECT d.DisciplineId, d.Seq, d.CaseId, c.Title, r.DocumentNo, d.Year, d.EmployeeNo, d.PersonName,
               d.Department, d.Position, d.ViolationDate, d.ViolationText, d.RuleText, d.SanctionType,
               d.SanctionDate, d.SanctionTerm, d.DecisionBody, d.DocNo, d.Status, d.Remark,
               d.Hold, d.Version, d.CreatedAt, IFNULL(u.DisplayName, '')
        FROM Discipline d
        LEFT JOIN Cases c ON c.CaseId = d.CaseId
        LEFT JOIN Results r ON r.CaseId = d.CaseId
        LEFT JOIN Users u ON u.UserId = d.CreatedBy
        """;

    static DisciplineRow ReadRow(Microsoft.Data.Sqlite.SqliteDataReader reader)
    {
        var person = reader.GetString(7);
        return new DisciplineRow
        {
            Id = reader.GetString(0),
            Seq = reader.GetInt32(1),
            CaseId = reader.IsDBNull(2) ? null : reader.GetString(2),
            CaseTitle = reader.IsDBNull(3) ? null : reader.GetString(3),
            CaseDocumentNo = reader.IsDBNull(4) ? null : reader.GetString(4),
            Year = reader.GetInt32(5),
            EmployeeNo = reader.GetString(6),
            PersonName = person,
            PersonMasked = MaskPerson(person),
            Department = reader.GetString(8),
            Position = reader.GetString(9),
            ViolationDate = reader.GetString(10),
            ViolationText = reader.GetString(11),
            RuleText = reader.GetString(12),
            SanctionType = reader.GetString(13),
            SanctionDate = reader.GetString(14),
            SanctionTerm = reader.GetString(15),
            DecisionBody = reader.GetString(16),
            DocNo = reader.GetString(17),
            Status = reader.GetString(18),
            Remark = reader.GetString(19),
            Hold = reader.GetInt32(20) == 1,
            Version = reader.GetInt32(21),
            CreatedDate = DisplayStamp(reader.GetString(22))[..Math.Min(10, DisplayStamp(reader.GetString(22)).Length)],
            OwnerName = reader.GetString(23)
        };
    }

    public List<DisciplineRow> ListDiscipline(string? text, string? sanction, int? year)
    {
        using var conn = _db.Open();
        var sql = DisciplineSelect + " WHERE 1=1";
        var args = new List<(string, object?)>();
        if (!string.IsNullOrWhiteSpace(sanction))
        {
            sql += " AND d.SanctionType=$s";
            args.Add(("$s", sanction.Trim()));
        }
        if (year is int y)
        {
            sql += " AND d.Year=$y";
            args.Add(("$y", y));
        }
        if (!string.IsNullOrWhiteSpace(text))
        {
            sql += """
                 AND (d.PersonName LIKE $q ESCAPE '\' OR d.EmployeeNo LIKE $q ESCAPE '\'
                   OR d.Department LIKE $q ESCAPE '\' OR d.Position LIKE $q ESCAPE '\'
                   OR d.ViolationText LIKE $q ESCAPE '\' OR d.RuleText LIKE $q ESCAPE '\'
                   OR d.DecisionBody LIKE $q ESCAPE '\' OR d.DocNo LIKE $q ESCAPE '\'
                   OR d.Remark LIKE $q ESCAPE '\'
                   OR IFNULL(c.Title,'') LIKE $q ESCAPE '\' OR IFNULL(r.DocumentNo,'') LIKE $q ESCAPE '\')
                """;
            args.Add(("$q", Text.LikeContains(text.Trim())));
        }
        sql += " ORDER BY d.Seq DESC;";
        using var cmd = Database.Cmd(conn, sql, args.ToArray());
        using var reader = cmd.ExecuteReader();
        var list = new List<DisciplineRow>();
        while (reader.Read()) list.Add(ReadRow(reader));
        return list;
    }

    /// <summary>한 감사 건에 연결된 징계기록. 건 상세에서 이름을 가린 채 보여 준다.</summary>
    public List<DisciplineRow> DisciplineForCase(string caseId)
    {
        if (!Text.IsHexId(caseId)) return [];
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, DisciplineSelect + " WHERE d.CaseId=$id ORDER BY d.Seq;", ("$id", caseId));
        using var reader = cmd.ExecuteReader();
        var list = new List<DisciplineRow>();
        while (reader.Read()) list.Add(ReadRow(reader));
        return list;
    }

    public IReadOnlyList<int> DisciplineYears()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, "SELECT DISTINCT Year FROM Discipline ORDER BY Year DESC;");
        using var reader = cmd.ExecuteReader();
        var list = new List<int>();
        while (reader.Read()) list.Add(reader.GetInt32(0));
        return list;
    }

    /// <summary>감사번호로 건을 고르는 목록. 번호가 아직 없는 건은 제목으로 찾는다.</summary>
    public IReadOnlyList<CaseRefOption> ListCaseRefs()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, """
            SELECT c.CaseId, IFNULL(r.DocumentNo,''), c.Title, c.Year, c.Category
            FROM Cases c LEFT JOIN Results r ON r.CaseId=c.CaseId
            ORDER BY c.Year DESC, IFNULL(r.DocumentNo,'') DESC, c.Title;
            """);
        using var reader = cmd.ExecuteReader();
        var list = new List<CaseRefOption>();
        while (reader.Read())
            list.Add(new CaseRefOption(reader.GetString(0), reader.GetString(1), reader.GetString(2), reader.GetInt32(3), reader.GetString(4)));
        return list;
    }

    /// <summary>
    /// 사용자가 적은 값을 감사 건으로 바꾼다. 감사번호(2026-01-01), 건 식별자, 제목 모두 받는다.
    /// 찾지 못하면 null을 돌려주고 found를 false로 둔다.
    /// </summary>
    static string? ResolveCaseRef(Microsoft.Data.Sqlite.SqliteConnection conn, string? reference, out bool found)
    {
        found = true;
        var text = (reference ?? "").Trim();
        if (text.Length == 0) return null;
        if (Text.IsHexId(text))
        {
            using var byId = Database.Cmd(conn, "SELECT CaseId FROM Cases WHERE CaseId=$id;", ("$id", text));
            if (byId.ExecuteScalar() is string hit) return hit;
            found = false;
            return null;
        }
        using (var byDoc = Database.Cmd(conn, "SELECT CaseId FROM Results WHERE DocumentNo=$no;", ("$no", text)))
        {
            if (byDoc.ExecuteScalar() is string hit) return hit;
        }
        using (var byTitle = Database.Cmd(conn, "SELECT CaseId FROM Cases WHERE Title=$t ORDER BY Year DESC LIMIT 1;", ("$t", text)))
        {
            if (byTitle.ExecuteScalar() is string hit) return hit;
        }
        found = false;
        return null;
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
                AppendHistory(conn, existing.CaseId, Now, actor.UserId, "열람", "징계", id, "징계기록 열람: " + existing.PersonMasked, null);
                return OpResult.Success("기록");
            });
            if (!recorded.Ok) return null;
        }
        using var conn = _db.Open();
        return ReadDiscipline(conn, id!);
    }

    public OpResult SaveDiscipline(Actor actor, DisciplineInput input, string? requestId)
    {
        if (!CanReadDiscipline(actor)) return OpResult.Fail(403, "FORBIDDEN", "징계기록은 파트장과 관리자만 다룹니다.");
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);

        var person = Text.Clean(input.PersonName, 40, false);
        var employeeNo = Text.Clean(input.EmployeeNo, 40, false) ?? "";
        var department = Text.Clean(input.Department, 80, false) ?? "";
        var position = Text.Clean(input.Position, 40, false) ?? "";
        var violationDate = Text.Clean(input.ViolationDate, 40, false);
        var violationText = Text.Clean(input.ViolationText, 500, false);
        var rule = Text.Clean(input.RuleText, 240, false) ?? "";
        var sanctionType = Text.Clean(input.SanctionType, 40, false);
        var sanctionDate = Text.Clean(input.SanctionDate, 40, false) ?? "";
        var sanctionTerm = Text.Clean(input.SanctionTerm, 80, false) ?? "";
        var decisionBody = Text.Clean(input.DecisionBody, 80, false) ?? "";
        var docNo = Text.Clean(input.DocNo, 80, false) ?? "";
        var remark = Text.Clean(input.Remark, 500, false) ?? "";
        var status = input.Status is "초안" or "확정" ? input.Status : "초안";

        if (string.IsNullOrWhiteSpace(person) || string.IsNullOrWhiteSpace(violationDate)
            || string.IsNullOrWhiteSpace(violationText) || string.IsNullOrWhiteSpace(sanctionType))
            return OpResult.Fail(422, "BAD_INPUT", "성명, 위반일자, 위반내용, 징계종류는 반드시 입력합니다.");
        if (input.Year is < 1990 or > 2100) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!DateLike(violationDate)) return OpResult.Fail(422, "BAD_INPUT", "위반일자는 2026-01-31 모양으로 적습니다.");
        if (sanctionDate.Length > 0 && !DateLike(sanctionDate))
            return OpResult.Fail(422, "BAD_INPUT", "징계일자는 2026-01-31 모양으로 적습니다.");

        return Transact(conn =>
        {
            var linked = ResolveCaseRef(conn, input.CaseRef, out var found);
            if (!found) return OpResult.Fail(422, "BAD_INPUT", "그 감사번호의 건이 없습니다. 목록에서 고르거나 비워 두세요.");
            var confirmedOn = status == "확정" ? (sanctionDate.Length > 0 ? sanctionDate : Kst.DateOf(Now).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)) : null;
            if (string.IsNullOrEmpty(input.Id))
            {
                var newId = Text.NewId();
                var seq = Database.NextDisciplineSeq(conn);
                using var insert = Database.Cmd(conn, """
                    INSERT INTO Discipline(
                      DisciplineId, Seq, CaseId, Year, EmployeeNo, PersonName, Department, Position,
                      ViolationDate, ViolationText, RuleText, SanctionType, SanctionDate, SanctionTerm,
                      DecisionBody, DocNo, Status, Remark, Hold, RetentionYears, ConfirmedOn, Version, CreatedAt, CreatedBy)
                    VALUES ($id,$seq,$case,$year,$emp,$name,$dept,$pos,$vdate,$vtext,$rule,$stype,$sdate,$sterm,
                            $body,$doc,$status,$remark,0,5,$conf,1,$at,$by);
                    """,
                    ("$id", newId), ("$seq", seq), ("$case", linked), ("$year", input.Year), ("$emp", employeeNo),
                    ("$name", person), ("$dept", department), ("$pos", position), ("$vdate", violationDate),
                    ("$vtext", violationText), ("$rule", rule), ("$stype", sanctionType), ("$sdate", sanctionDate),
                    ("$sterm", sanctionTerm), ("$body", decisionBody), ("$doc", docNo), ("$status", status),
                    ("$remark", remark), ("$conf", confirmedOn), ("$at", Kst.Canon(Now)), ("$by", actor.UserId));
                insert.ExecuteNonQuery();
                AppendHistory(conn, linked, Now, actor.UserId, "등록", "징계", newId,
                    "징계기록 등재 순번 " + seq.ToString(CultureInfo.InvariantCulture), "disc:" + requestId);
                return OpResult.Success("징계기록을 저장했습니다.", linked, 1, extra: newId);
            }
            if (!Text.IsHexId(input.Id)) return OpResult.Fail(404, "NOT_FOUND", "징계기록을 찾을 수 없습니다.");
            var current = ReadDiscipline(conn, input.Id!);
            if (current is null) return OpResult.Fail(404, "NOT_FOUND", "징계기록을 찾을 수 없습니다.");
            if (current.Status == "삭제됨") return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", "삭제된 징계기록은 고칠 수 없습니다.");
            if (current.Version != input.Version) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var upd = Database.Cmd(conn, """
                UPDATE Discipline SET CaseId=$case, Year=$year, EmployeeNo=$emp, PersonName=$name, Department=$dept,
                  Position=$pos, ViolationDate=$vdate, ViolationText=$vtext, RuleText=$rule, SanctionType=$stype,
                  SanctionDate=$sdate, SanctionTerm=$sterm, DecisionBody=$body, DocNo=$doc, Status=$status,
                  Remark=$remark, ConfirmedOn=$conf, Version=Version+1
                WHERE DisciplineId=$id AND Version=$ver;
                """,
                ("$case", linked), ("$year", input.Year), ("$emp", employeeNo), ("$name", person), ("$dept", department),
                ("$pos", position), ("$vdate", violationDate), ("$vtext", violationText), ("$rule", rule),
                ("$stype", sanctionType), ("$sdate", sanctionDate), ("$sterm", sanctionTerm), ("$body", decisionBody),
                ("$doc", docNo), ("$status", status), ("$remark", remark), ("$conf", confirmedOn),
                ("$id", input.Id), ("$ver", input.Version));
            if (upd.ExecuteNonQuery() != 1) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, linked, Now, actor.UserId, "수정", "징계", input.Id, "징계기록 수정", null);
            return OpResult.Success("징계기록을 저장했습니다.", linked, input.Version + 1, extra: input.Id);
        });
    }

    static bool DateLike(string value) =>
        DateOnly.TryParseExact(value, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _);

    public OpResult SetDisciplineHold(Actor actor, string id, int version, bool hold, string? reason, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var why = Text.Clean(reason, 240, false);
        if (string.IsNullOrWhiteSpace(why)) return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
        var gate = GateReady();
        if (gate != null) return gate;
        _ = requestId;
        return Transact(conn =>
        {
            var row = ReadDiscipline(conn, id);
            if (row is null) return OpResult.Fail(404, "NOT_FOUND", "징계기록을 찾을 수 없습니다.");
            if (row.Version != version) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var upd = Database.Cmd(conn, "UPDATE Discipline SET Hold=$h, Version=Version+1 WHERE DisciplineId=$id AND Version=$ver;",
                ("$h", hold ? 1 : 0), ("$id", id), ("$ver", version));
            if (upd.ExecuteNonQuery() != 1) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, row.CaseId, Now, actor.UserId, hold ? "보류" : "보류해제", "징계", id,
                (hold ? "삭제 보류. 사유: " : "보류 해제. 사유: ") + why, null);
            return OpResult.Success(hold ? "삭제를 보류했습니다." : "보류를 해제했습니다.", extra: id);
        });
    }

    /// <summary>등재 항목 머리글 그대로의 CSV. 본문 파일은 넣지 않는다.</summary>
    public string ExportDisciplineCsv(Actor actor, string? requestId)
    {
        if (!actor.IsAdmin) return "";
        var rows = ListDiscipline(null, null, null);
        Transact(conn =>
        {
            AppendHistory(conn, null, Now, actor.UserId, "내보내기", "징계", null,
                "징계기록 CSV " + rows.Count.ToString(CultureInfo.InvariantCulture) + "건", "disc-csv:" + requestId);
            return OpResult.Success("기록");
        });
        var sb = new StringBuilder();
        sb.Append('﻿');
        sb.AppendLine("순번,사번,성명,부서,직위,위반일자,위반내용,관련규정,징계종류,징계일자,징계기간,결정기관,근거문서번호,상태,비고,등록일자,담당자,감사번호");
        foreach (var row in rows)
        {
            sb.Append(row.Seq.ToString(CultureInfo.InvariantCulture)).Append(',')
              .Append(Csv(row.EmployeeNo)).Append(',').Append(Csv(row.PersonName)).Append(',')
              .Append(Csv(row.Department)).Append(',').Append(Csv(row.Position)).Append(',')
              .Append(Csv(row.ViolationDate)).Append(',').Append(Csv(row.ViolationText)).Append(',')
              .Append(Csv(row.RuleText)).Append(',').Append(Csv(row.SanctionType)).Append(',')
              .Append(Csv(row.SanctionDate)).Append(',').Append(Csv(row.SanctionTerm)).Append(',')
              .Append(Csv(row.DecisionBody)).Append(',').Append(Csv(row.DocNo)).Append(',')
              .Append(Csv(row.Status)).Append(',').Append(Csv(row.Remark)).Append(',')
              .Append(Csv(row.CreatedDate)).Append(',').Append(Csv(row.OwnerName)).Append(',')
              .Append(Csv(row.CaseDocumentNo ?? "")).Append('\n');
        }
        return sb.ToString();
    }

    public int PurgeDueDiscipline(DateTimeOffset now)
    {
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
                if (!DateOnly.TryParse(reader.GetString(1), CultureInfo.InvariantCulture, out var anchor)) continue;
                if (!Kst.IsDue(anchor, reader.GetInt32(2), now)) continue;
                due.Add((reader.GetString(0), reader.IsDBNull(4) ? null : reader.GetString(4)));
            }
            reader.Close();
            foreach (var item in due)
            {
                using var upd = Database.Cmd(conn, """
                    UPDATE Discipline SET EmployeeNo='', PersonName='삭제됨', Department='', Position='',
                      ViolationText='', RuleText='', SanctionTerm='', DecisionBody='', DocNo='', Remark='',
                      Status='삭제됨', Version=Version+1
                    WHERE DisciplineId=$id AND Status='확정' AND Hold=0;
                    """, ("$id", item.Id));
                upd.ExecuteNonQuery();
                AppendHistory(conn, item.CaseId, now, null, "자료삭제", "징계", item.Id, "징계기록 보존 만료", null);
            }
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
        SaveDiscipline(lead, new DisciplineInput
        {
            Year = 2024, EmployeeNo = "A-1001", PersonName = "가상갑", Department = "예시부서", Position = "대리",
            ViolationDate = "2024-04-02", ViolationText = "허위 출장 보고", RuleText = "예시 내부 기준 제3호",
            SanctionType = "견책", SanctionDate = "2024-05-10", SanctionTerm = "해당 없음",
            DecisionBody = "예시 인사위원회", DocNo = "예시-인사-2024-011", Status = "확정",
            Remark = "경위: 보고서를 두 번 제출"
        }, Text.NewId());
        SaveDiscipline(lead, new DisciplineInput
        {
            Year = 2025, EmployeeNo = "A-2043", PersonName = "가상을", Department = "예시물류팀", Position = "과장",
            ViolationDate = "2025-06-18", ViolationText = "물품 반출 기록 누락", RuleText = "예시 내부 기준 제5호",
            SanctionType = "감봉", SanctionDate = "2025-07-22", SanctionTerm = "1개월",
            DecisionBody = "예시 인사위원회", DocNo = "예시-인사-2025-004", Status = "확정",
            Remark = "경위: 출고 장부를 나중에 고침"
        }, Text.NewId());
        SaveDiscipline(lead, new DisciplineInput
        {
            Year = 2023, EmployeeNo = "A-0788", PersonName = "가상병", Department = "예시현장팀", Position = "사원",
            ViolationDate = "2023-11-09", ViolationText = "안전 수칙 미준수", RuleText = "예시 내부 기준 제1호",
            SanctionType = "경고", SanctionDate = "2023-12-01", SanctionTerm = "재교육 8시간",
            DecisionBody = "예시 안전관리위원회", DocNo = "예시-안전-2023-029", Status = "초안",
            Remark = "경위: 보호구 없이 작업"
        }, Text.NewId());
    }

    DisciplineRow? ReadDiscipline(Microsoft.Data.Sqlite.SqliteConnection conn, string id)
    {
        DisciplineRow row;
        using (var cmd = Database.Cmd(conn, DisciplineSelect + " WHERE d.DisciplineId=$id;", ("$id", id)))
        using (var reader = cmd.ExecuteReader())
        {
            if (!reader.Read()) return null;
            row = ReadRow(reader);
        }
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
        if (neutralize || text.Contains('"', StringComparison.Ordinal) || text.Contains(',', StringComparison.Ordinal)
            || text.Contains('\n', StringComparison.Ordinal) || text.Contains('\r', StringComparison.Ordinal))
            return "\"" + text + "\"";
        return text;
    }
}

/// <summary>화면에서 받은 징계기록 입력 한 벌.</summary>
public sealed class DisciplineInput
{
    public string? Id { get; init; }
    public int Version { get; init; }
    public string? CaseRef { get; init; }
    public int Year { get; init; }
    public string? EmployeeNo { get; init; }
    public string? PersonName { get; init; }
    public string? Department { get; init; }
    public string? Position { get; init; }
    public string? ViolationDate { get; init; }
    public string? ViolationText { get; init; }
    public string? RuleText { get; init; }
    public string? SanctionType { get; init; }
    public string? SanctionDate { get; init; }
    public string? SanctionTerm { get; init; }
    public string? DecisionBody { get; init; }
    public string? DocNo { get; init; }
    public string? Status { get; init; }
    public string? Remark { get; init; }
}
