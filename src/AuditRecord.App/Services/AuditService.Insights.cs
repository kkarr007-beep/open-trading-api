using System.Globalization;
using AuditRecord.Data;
using AuditRecord.Domain;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Services;

public sealed partial class AuditService
{
    /// <summary>
    /// 현황판 집계. 본문을 읽지 않고 목록 수준의 항목만 센다.
    /// </summary>
    public DashboardData GetDashboard()
    {
        var now = Now;
        var today = Kst.DateOf(now);
        using var conn = _db.Open();

        int total = 0, draft = 0, confirmed = 0, inAction = 0, closed = 0, purged = 0, hold = 0, thisYear = 0;
        int expiringSoon = 0, delayed = 0;
        var byYear = new SortedDictionary<int, int>();
        var byCategory = new Dictionary<string, int>();
        using (var cmd = Database.Cmd(conn, """
            SELECT Year, Category, Phase, PurgedAt, Hold, RetentionYears, FirstConfirmedOn FROM Cases;
            """))
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                total++;
                var year = reader.GetInt32(0);
                byYear[year] = byYear.TryGetValue(year, out var yc) ? yc + 1 : 1;
                var category = reader.GetString(1);
                byCategory[category] = byCategory.TryGetValue(category, out var cc) ? cc + 1 : 1;
                if (year == today.Year) thisYear++;
                var isPurged = !reader.IsDBNull(3);
                if (isPurged) purged++;
                else
                {
                    switch (reader.GetString(2))
                    {
                        case "초안": draft++; break;
                        case "확정": confirmed++; break;
                        case "조치중": inAction++; break;
                        case "종결": closed++; break;
                    }
                }
                if (reader.GetInt32(4) == 1) hold++;
                if (!reader.IsDBNull(5) && !reader.IsDBNull(6)
                    && DateOnly.TryParse(reader.GetString(6), CultureInfo.InvariantCulture, out var anchor))
                {
                    var years = reader.GetInt32(5);
                    var left = Kst.AddYears(anchor, years).DayNumber - today.DayNumber;
                    var isDue = Kst.IsDue(anchor, years, now);
                    if (isDue && !isPurged) delayed++;
                    else if (!isDue && !isPurged && left is >= 0 and <= 30) expiringSoon++;
                }
            }
        }

        int openActions = 0, doneActions = 0, overdueActions = 0;
        var byActionState = new Dictionary<string, int>();
        using (var cmd = Database.Cmd(conn, "SELECT ActionState, ReplyDueOn FROM Actions;"))
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                var state = reader.GetString(0);
                byActionState[state] = byActionState.TryGetValue(state, out var sc) ? sc + 1 : 1;
                if (state == "이행완료") doneActions++;
                else
                {
                    openActions++;
                    if (DateOnly.TryParse(reader.GetString(1), CultureInfo.InvariantCulture, out var due) && due < today)
                        overdueActions++;
                }
            }
        }

        var recent = new List<HistoryRow>();
        using (var cmd = Database.Cmd(conn, """
            SELECT h.Seq, h.At, h.Action, h.TargetKind, h.TargetId, h.SummarySafe, u.DisplayName, h.CaseId,
                   (SELECT c.Title FROM Cases c WHERE c.CaseId=h.CaseId)
            FROM History h LEFT JOIN Users u ON u.UserId=h.ActorId
            ORDER BY h.Seq DESC LIMIT 12;
            """))
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                recent.Add(new HistoryRow
                {
                    Seq = reader.GetInt64(0),
                    AtDisplay = DisplayStamp(reader.GetString(1)),
                    Action = reader.GetString(2),
                    TargetKind = reader.GetString(3),
                    TargetId = reader.IsDBNull(7) ? null : reader.GetString(7),
                    Summary = (reader.IsDBNull(8) ? "" : reader.GetString(8) + " — ") + reader.GetString(5),
                    ActorName = reader.IsDBNull(6) ? "시스템" : reader.GetString(6)
                });
            }
        }

        var stateOrder = new[] { "요구중", "회신수신", "이행완료" };
        return new DashboardData
        {
            TotalCases = total,
            DraftCount = draft,
            ConfirmedCount = confirmed,
            InActionCount = inAction,
            ClosedCount = closed,
            PurgedCount = purged,
            OverdueActions = overdueActions,
            OpenActions = openActions,
            DoneActions = doneActions,
            ExpiringSoon = expiringSoon,
            DeletionDelayed = delayed,
            HoldCount = hold,
            ThisYearRegistered = thisYear,
            ByYear = byYear.Select(kv => new LabelCount(kv.Key.ToString(CultureInfo.InvariantCulture), kv.Value)).ToList(),
            ByCategory = byCategory.OrderBy(kv => kv.Key, StringComparer.Ordinal)
                .Select(kv => new LabelCount(kv.Key, kv.Value)).ToList(),
            ByPhase =
            [
                new("초안", draft), new("확정", confirmed), new("조치중", inAction),
                new("종결", closed), new("자료삭제됨", purged)
            ],
            ByActionState = stateOrder
                .Select(s => new LabelCount(s, byActionState.TryGetValue(s, out var c) ? c : 0)).ToList(),
            Recent = recent
        };
    }

    /// <summary>
    /// 기록물 등록대장. 등록번호 순으로 모든 건의 대장 정보를 돌려준다. 본문은 포함하지 않는다.
    /// </summary>
    public IReadOnlyList<RegisterRow> ListRegister(int? year, string? category)
    {
        using var conn = _db.Open();
        var sql = """
            SELECT c.CaseId, c.RecordNo, c.Year, c.Category, c.Title, c.Target, u.DisplayName,
                   c.CreatedAt, c.FirstConfirmedOn, c.RetentionYears, c.Phase, c.PurgedAt, c.Hold,
                   r.DocumentNo, c.Shelf, c.BinderNo, r.SummaryLine,
                   (SELECT COUNT(*) FROM Attachments a WHERE a.CaseId=c.CaseId),
                   (SELECT COUNT(*) FROM Attachments a WHERE a.CaseId=c.CaseId AND a.StorageState='Deleted')
            FROM Cases c
            JOIN Users u ON u.UserId=c.OwnerUserId
            JOIN Results r ON r.CaseId=c.CaseId
            WHERE 1=1
            """;
        var args = new List<(string, object?)>();
        if (year is int y)
        {
            sql += " AND c.Year=$year";
            args.Add(("$year", y));
        }
        if (category is "업무진단" or "준법감사")
        {
            sql += " AND c.Category=$cat";
            args.Add(("$cat", category));
        }
        sql += " ORDER BY c.Year DESC, c.RecordNo DESC;";
        using var cmd = Database.Cmd(conn, sql, args.ToArray());
        using var reader = cmd.ExecuteReader();
        var list = new List<RegisterRow>();
        while (reader.Read())
        {
            DateOnly? first = reader.IsDBNull(8) ? null
                : DateOnly.Parse(reader.GetString(8), CultureInfo.InvariantCulture);
            int? retention = reader.IsDBNull(9) ? null : reader.GetInt32(9);
            string? expiry = null;
            if (first is { } anchor && retention is int years)
                expiry = Kst.AddYears(anchor, years).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            list.Add(new RegisterRow
            {
                CaseId = reader.GetString(0),
                RecordNo = reader.GetString(1),
                Year = reader.GetInt32(2),
                Category = reader.GetString(3),
                Title = reader.GetString(4),
                Target = reader.GetString(5),
                OwnerName = reader.GetString(6),
                CreatedDate = DisplayDateOnly(reader.GetString(7)),
                FirstConfirmedOn = first?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                RetentionYears = retention,
                ExpiryDate = expiry,
                DisplayState = reader.IsDBNull(11) ? reader.GetString(10) : "자료삭제됨",
                Hold = reader.GetInt32(12) == 1,
                DocumentNo = reader.GetString(13),
                Shelf = reader.GetString(14),
                BinderNo = reader.GetString(15),
                SummaryLine = reader.GetString(16),
                AttachmentCount = reader.GetInt32(17),
                DeletedAttachmentCount = reader.GetInt32(18)
            });
        }
        return list;
    }
}
