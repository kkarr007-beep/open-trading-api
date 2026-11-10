using System.Globalization;
using AuditRecord.Data;
using AuditRecord.Domain;
using AuditRecord.Storage;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Services;

public sealed partial class AuditService
{
    public CaseDetail? GetCase(Actor actor, string? caseId)
    {
        if (!Text.IsHexId(caseId)) return null;
        var hidden = RestoreLocked;
        var integrity = IntegrityIntact();
        using var conn = _db.Open();
        var row = LoadCase(conn, caseId!);
        if (row is null) return null;
        var now = Now;
        var today = Kst.DateOf(now);
        var owner = DisplayName(conn, row.OwnerId);
        var confirmer = row.ConfirmerId is null ? null : DisplayName(conn, row.ConfirmerId);
        string? expiryDate = null;
        string? expiryDisplay = null;
        int? days = null;
        var expired = false;
        if (row.FirstOn is { } anchor && row.Retention is int years)
        {
            var due = Kst.AddYears(anchor, years);
            expiryDate = due.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            expiryDisplay = expiryDate + " 00:00 KST";
            days = due.DayNumber - today.DayNumber;
            expired = Kst.IsDue(anchor, years, now);
        }
        var actions = LoadActions(conn, caseId!, today, row.BodiesClearedAt != null, hidden);
        var files = LoadFiles(conn, caseId!, hidden);
        var history = LoadHistory(conn, caseId!, actions);
        var open = actions.Count(a => a.State != "이행완료");
        var done = actions.Count - open;
        var display = row.PurgedAt != null ? "자료삭제됨" : row.Phase;
        var note = "";
        if (hidden)
            note = "복구 대조 전 — 본문과 자료 위치를 표시하지 않습니다.";
        else if (row.Incomplete)
            note = "삭제 미완료: 본문 또는 파일 일부가 남아 재처리 대기입니다. 전체를 자료삭제됨으로 표시하지 않습니다.";
        else if (row.PurgedAt != null)
            note = "자료삭제됨. 목록, 결과 한 줄, 자료 이름, 삭제일, 이력은 남습니다. 디스크의 물리적 완전 삭제는 증명되지 않습니다.";
        else if (row.Hold)
            note = "삭제 보류 — 자동 삭제 안 함. 만료일은 연장되지 않고, 이미 지운 자료는 돌아오지 않습니다.";
        else if (expired)
            note = "보존 만료 시각이 지났습니다. 켜져 있는 동안 삭제 처리가 이어집니다.";
        var caps = BuildCaps(actor, row, now, integrity && !hidden, conn);
        var queued = false;
        var attempts = 0;
        string? jobError = null;
        using (var job = Database.Cmd(conn, """
            SELECT Attempt, ErrorCode FROM DeleteJobs
            WHERE CaseId=$id AND Status<>'Complete'
            ORDER BY CreatedAt DESC LIMIT 1;
            """, ("$id", caseId)))
        using (var jobReader = job.ExecuteReader())
        {
            if (jobReader.Read())
            {
                queued = true;
                attempts = jobReader.GetInt32(0);
                jobError = jobReader.IsDBNull(1) ? null : jobReader.GetString(1);
            }
        }
        return new CaseDetail
        {
            CaseId = row.Id,
            Year = row.Year,
            Category = row.Category,
            Title = row.Title,
            Target = row.Target,
            OwnerUserId = row.OwnerId,
            OwnerName = owner,
            Shelf = row.Shelf,
            BinderNo = row.Binder,
            RetentionYears = row.Retention,
            FirstConfirmedOn = row.FirstOn?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            ConfirmedOn = row.ConfirmedOn?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
            EverConfirmed = row.Ever,
            Hold = row.Hold,
            HoldReason = row.HoldReason,
            HoldSetAt = DisplayMaybe(row.HoldSetAt),
            HoldReleasedAt = DisplayMaybe(row.HoldReleasedAt),
            Phase = row.Phase,
            DisplayState = display,
            PurgedAt = DisplayMaybe(row.PurgedAt),
            BodiesCleared = row.BodiesClearedAt != null,
            PurgeIncomplete = row.Incomplete,
            Version = row.Version,
            DocumentNo = row.Doc,
            Summary = row.Summary,
            Body = hidden ? null : row.Body,
            ConfirmerName = confirmer,
            ExpiryDate = expiryDate,
            ExpiryDisplay = expiryDisplay,
            DaysUntilExpiry = days,
            Expired = expired,
            OpenActions = open,
            DoneActions = done,
            ActionCount = actions.Count,
            PurgeNote = note,
            DeletionQueued = queued,
            DeletionAttempts = attempts,
            DeletionError = jobError,
            BodiesHidden = hidden,
            Caps = caps,
            Actions = actions,
            Files = files,
            History = history
        };
    }

    public CaseListPage ListCases(CaseQuery query)
    {
        var now = Now;
        var today = Kst.DateOf(now);
        using var conn = _db.Open();
        var sql = """
            SELECT c.CaseId, c.Year, c.Category, c.Title, c.Target, u.DisplayName,
                   c.Phase, c.PurgedAt, c.Hold, c.RetentionYears, c.FirstConfirmedOn,
                   c.Shelf, c.BinderNo, r.DocumentNo, c.PurgeIncomplete, c.Version,
                   (SELECT MIN(a.ReplyDueOn) FROM Actions a WHERE a.CaseId=c.CaseId AND a.ActionState<>'이행완료'),
                   (SELECT COUNT(*) FROM Actions a WHERE a.CaseId=c.CaseId AND a.ActionState<>'이행완료')
            FROM Cases c
            JOIN Users u ON u.UserId=c.OwnerUserId
            JOIN Results r ON r.CaseId=c.CaseId
            WHERE 1=1
            """;
        var args = new List<(string, object?)>();
        if (query.Year is int year)
        {
            sql += " AND c.Year=$year";
            args.Add(("$year", year));
        }
        if (query.Category is "업무진단" or "준법감사")
        {
            sql += " AND c.Category=$cat";
            args.Add(("$cat", query.Category));
        }
        if (query.Status == "자료삭제됨")
            sql += " AND c.PurgedAt IS NOT NULL";
        else if (query.Status is "초안" or "확정" or "조치중" or "종결")
        {
            sql += " AND c.PurgedAt IS NULL AND c.Phase=$phase";
            args.Add(("$phase", query.Status));
        }
        if (!string.IsNullOrWhiteSpace(query.Text))
        {
            sql += " AND (c.Title LIKE $q ESCAPE '\\' OR c.Target LIKE $q ESCAPE '\\' OR r.DocumentNo LIKE $q ESCAPE '\\' OR c.BinderNo LIKE $q ESCAPE '\\' OR EXISTS (SELECT 1 FROM Attachments af WHERE af.CaseId=c.CaseId AND af.DisplayName LIKE $q ESCAPE '\\'))";
            args.Add(("$q", Text.LikeContains(query.Text.Trim())));
        }
        using var cmd = Database.Cmd(conn, sql, args.ToArray());
        var items = new List<CaseListItem>();
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                var purged = !reader.IsDBNull(7);
                var hold = reader.GetInt32(8) == 1;
                int? retention = reader.IsDBNull(9) ? null : reader.GetInt32(9);
                DateOnly? first = reader.IsDBNull(10) ? null : DateOnly.Parse(reader.GetString(10), CultureInfo.InvariantCulture);
                string? expiry = null;
                var delayed = false;
                var soon = false;
                if (first is { } anchor && retention is int years)
                {
                    var due = Kst.AddYears(anchor, years);
                    expiry = due.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
                    var left = due.DayNumber - today.DayNumber;
                    var isDue = Kst.IsDue(anchor, years, now);
                    delayed = isDue && !purged;
                    soon = !isDue && !purged && left is >= 0 and <= 30;
                }
                var nextDue = reader.IsDBNull(16) ? null : reader.GetString(16);
                var open = reader.GetInt32(17);
                var overdue = false;
                if (nextDue != null && DateOnly.TryParse(nextDue, CultureInfo.InvariantCulture, out var dueOn))
                    overdue = open > 0 && dueOn < today;
                items.Add(new CaseListItem
                {
                    CaseId = reader.GetString(0),
                    Year = reader.GetInt32(1),
                    Category = reader.GetString(2),
                    Title = reader.GetString(3),
                    Target = reader.GetString(4),
                    OwnerName = reader.GetString(5),
                    DisplayState = purged ? "자료삭제됨" : reader.GetString(6),
                    NextDue = nextDue,
                    Overdue = overdue,
                    RetentionYears = retention,
                    ExpiryDate = expiry,
                    FirstConfirmedOn = first?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                    Hold = hold,
                    DueSoon = soon,
                    DeletionDelayed = delayed,
                    DocumentNo = reader.GetString(13),
                    Shelf = reader.GetString(11),
                    BinderNo = reader.GetString(12),
                    OpenActions = open,
                    PurgeIncomplete = reader.GetInt32(14) == 1,
                    Version = reader.GetInt32(15)
                });
            }
        }
        IEnumerable<CaseListItem> filtered = items;
        if (query.Overdue) filtered = filtered.Where(x => x.Overdue);
        if (query.DueSoon) filtered = filtered.Where(x => x.DueSoon || x.DeletionDelayed);
        var sort = query.Sort;
        var desc = !string.Equals(query.Dir, "asc", StringComparison.OrdinalIgnoreCase);
        filtered = sort switch
        {
            "title" => desc ? filtered.OrderByDescending(x => x.Title).ThenBy(x => x.CaseId) : filtered.OrderBy(x => x.Title).ThenBy(x => x.CaseId),
            "due" => desc
                ? filtered.OrderBy(x => x.NextDue == null).ThenByDescending(x => x.NextDue).ThenBy(x => x.CaseId)
                : filtered.OrderBy(x => x.NextDue == null).ThenBy(x => x.NextDue).ThenBy(x => x.CaseId),
            "expiry" => desc
                ? filtered.OrderBy(x => x.ExpiryDate == null).ThenByDescending(x => x.ExpiryDate).ThenBy(x => x.CaseId)
                : filtered.OrderBy(x => x.ExpiryDate == null).ThenBy(x => x.ExpiryDate).ThenBy(x => x.CaseId),
            _ => desc ? filtered.OrderByDescending(x => x.Year).ThenByDescending(x => x.CaseId) : filtered.OrderBy(x => x.Year).ThenBy(x => x.CaseId)
        };
        var list = filtered.ToList();
        var size = query.PageSize is 25 or 100 ? query.PageSize : 50;
        var pages = Math.Max(1, (int)Math.Ceiling(list.Count / (double)size));
        var page = Math.Clamp(query.Page < 1 ? 1 : query.Page, 1, pages);
        return new CaseListPage
        {
            Items = list.Skip((page - 1) * size).Take(size).ToList(),
            Total = list.Count,
            Page = page,
            PageSize = size,
            Pages = pages
        };
    }

    public TodoPage Todos(Actor actor)
    {
        var page = ListCases(new CaseQuery { Page = 1, PageSize = 100 });
        var all = new List<CaseListItem>();
        for (var i = 1; i <= Math.Max(page.Pages, 1) && page.Total > 0; i++)
        {
            var chunk = i == 1 ? page : ListCases(new CaseQuery { Page = i, PageSize = 100 });
            all.AddRange(chunk.Items);
            if (i == 1 && chunk.Pages == 1) break;
        }
        var manager = actor.IsLead;
        var today = Kst.DateOf(Now);
        var overdue = new List<TodoAction>();
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, """
            SELECT c.CaseId, c.Title, c.Target, u.DisplayName, a.ActionId, a.Sequence, a.Title, a.ReplyDueOn, a.ActionState
            FROM Actions a
            JOIN Cases c ON c.CaseId=a.CaseId
            JOIN Users u ON u.UserId=c.OwnerUserId
            WHERE a.ActionState<>'이행완료'
            ORDER BY a.ReplyDueOn, c.CaseId, a.Sequence;
            """))
        using (var reader = cmd.ExecuteReader())
        {
            while (reader.Read())
            {
                var dueText = reader.GetString(7);
                if (!DateOnly.TryParse(dueText, CultureInfo.InvariantCulture, out var due) || due >= today)
                    continue;
                overdue.Add(new TodoAction
                {
                    CaseId = reader.GetString(0),
                    CaseTitle = reader.GetString(1),
                    Target = reader.GetString(2),
                    OwnerName = reader.GetString(3),
                    ActionId = reader.GetString(4),
                    Sequence = reader.GetInt32(5),
                    Title = reader.GetString(6),
                    ReplyDueOn = dueText,
                    DaysOver = today.DayNumber - due.DayNumber,
                    State = reader.GetString(8)
                });
            }
        }
        var waiting = manager
            ? all.Where(x => x.DisplayState == "초안").Select(x => new TodoDraft
            {
                CaseId = x.CaseId,
                Year = x.Year,
                Category = x.Category,
                Title = x.Title,
                Target = x.Target,
                OwnerName = x.OwnerName,
                RetentionYears = x.RetentionYears,
                DisplayState = x.DisplayState
            }).Where(x => x.RetentionYears is > 0).ToList()
            : [];
        if (manager)
        {
            using var conn = _db.Open();
            waiting = waiting.Where(x =>
            {
                using var cmd = Database.Cmd(conn, "SELECT SummaryLine FROM Results WHERE CaseId=$id;", ("$id", x.CaseId));
                var summary = cmd.ExecuteScalar() as string;
                return !string.IsNullOrWhiteSpace(summary);
            }).ToList();
        }
        var expiring = manager
            ? all.Where(x => x.DueSoon || x.DeletionDelayed).Select(x => new TodoExpiry
            {
                CaseId = x.CaseId,
                Title = x.Title,
                ConfirmedOn = x.FirstConfirmedOn,
                ExpiryDate = x.ExpiryDate ?? "",
                DaysLeft = DaysLeft(x, today),
                OpenActions = x.OpenActions,
                Hold = x.Hold,
                Delayed = x.DeletionDelayed
            }).OrderBy(x => x.Delayed ? 0 : 1).ThenBy(x => x.ExpiryDate).ThenBy(x => x.CaseId).ToList()
            : [];
        return new TodoPage
        {
            ManagerView = manager,
            Overdue = overdue,
            WaitingConfirm = waiting.OrderByDescending(x => x.Year).ThenBy(x => x.CaseId).ToList(),
            Expiring = expiring
        };
    }

    public IReadOnlyList<int> Years()
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, "SELECT DISTINCT Year FROM Cases ORDER BY Year DESC;");
        using var reader = cmd.ExecuteReader();
        var list = new List<int>();
        while (reader.Read()) list.Add(reader.GetInt32(0));
        return list;
    }

    static int DaysLeft(CaseListItem item, DateOnly today)
    {
        if (item.ExpiryDate != null && DateOnly.TryParse(item.ExpiryDate, CultureInfo.InvariantCulture, out var due))
            return due.DayNumber - today.DayNumber;
        return 0;
    }

    CaseCaps BuildCaps(Actor actor, CaseSnap row, DateTimeOffset now, bool writable, SqliteConnection conn)
    {
        if (!writable)
            return new CaseCaps();
        var locked = ContentLocked(conn, row, now);
        var expired = row.FirstOn is { } anchor && row.Retention is int years && Kst.IsDue(anchor, years, now);
        return new CaseCaps
        {
            EditList = row.PurgedAt == null,
            EditResult = !locked && row.Phase == "초안",
            SetRetention = row.PurgedAt == null && row.BodiesClearedAt == null && (row.Ever ? actor.IsLead : true),
            RetentionNeedsReason = row.Ever,
            Confirm = actor.IsLead && row.Phase == "초안" && !locked,
            Cancel = actor.IsLead && row.Phase != "초안",
            Close = actor.IsLead && row.PurgedAt == null && row.Phase is "확정" or "조치중",
            AddAction = !locked && row.Phase is "초안" or "확정" or "조치중",
            Reply = !locked && row.Phase != "종결",
            Verify = !locked && row.Phase != "종결",
            AddFile = !locked && row.Phase != "종결",
            RemoveDraftFile = !row.Ever && row.Phase == "초안" && !locked,
            EarlyDelete = actor.IsAdmin && row.Ever && !row.Hold && !expired && row.PurgedAt == null && row.BodiesClearedAt == null,
            Hold = actor.IsAdmin
        };
    }

    List<ActionRow> LoadActions(SqliteConnection conn, string caseId, DateOnly today, bool cleared, bool hidden)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT ActionId, Sequence, Title, Type, TargetDept, RequestedOn, ReplyDueOn, Content, ActionState
            FROM Actions WHERE CaseId=$id ORDER BY Sequence;
            """, ("$id", caseId));
        var list = new List<ActionRow>();
        using var reader = cmd.ExecuteReader();
        var raw = new List<(string Id, int Seq, string Title, string Type, string Dept, string Req, string Due, string? Content, string State)>();
        while (reader.Read())
        {
            raw.Add((reader.GetString(0), reader.GetInt32(1), reader.GetString(2), reader.GetString(3), reader.GetString(4),
                reader.GetString(5), reader.GetString(6), reader.IsDBNull(7) ? null : reader.GetString(7), reader.GetString(8)));
        }
        reader.Close();
        foreach (var item in raw)
        {
            var overdue = DateOnly.TryParse(item.Due, CultureInfo.InvariantCulture, out var due) && due < today && item.State != "이행완료";
            list.Add(new ActionRow
            {
                ActionId = item.Id,
                Sequence = item.Seq,
                Title = item.Title,
                Type = item.Type,
                TargetDept = item.Dept,
                RequestedOn = item.Req,
                ReplyDueOn = item.Due,
                Content = hidden ? null : item.Content,
                State = item.State,
                ContentCleared = cleared && item.Content == null,
                Overdue = overdue,
                Entries = LoadEntries(conn, item.Id, cleared, hidden)
            });
        }
        return list;
    }

    List<FulfillmentRow> LoadEntries(SqliteConnection conn, string actionId, bool cleared, bool hidden)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT f.FulfillmentId, f.At, u.DisplayName, f.Kind, f.FromState, f.ToState, f.ReplyBody, f.CheckBody
            FROM Fulfillments f JOIN Users u ON u.UserId=f.ActorId
            WHERE f.ActionId=$id ORDER BY f.At, f.FulfillmentId;
            """, ("$id", actionId));
        using var reader = cmd.ExecuteReader();
        var list = new List<FulfillmentRow>();
        while (reader.Read())
        {
            list.Add(new FulfillmentRow
            {
                Id = reader.GetString(0),
                AtDisplay = DisplayStamp(reader.GetString(1)),
                ActorName = reader.GetString(2),
                Kind = reader.GetString(3),
                FromState = reader.GetString(4),
                ToState = reader.GetString(5),
                ReplyBody = hidden ? null : reader.IsDBNull(6) ? null : reader.GetString(6),
                CheckBody = hidden ? null : reader.IsDBNull(7) ? null : reader.GetString(7),
                BodiesCleared = cleared
            });
        }
        return list;
    }

    List<FileRow> LoadFiles(SqliteConnection conn, string caseId, bool hidden)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT a.AttachmentId, a.Kind, a.DisplayName, a.StorageState, a.StorageKey, a.RegisteredAt, u.DisplayName, a.DeletedAt, a.Sha256
            FROM Attachments a JOIN Users u ON u.UserId=a.RegisteredBy
            WHERE a.CaseId=$id ORDER BY a.RegisteredAt, a.AttachmentId;
            """, ("$id", caseId));
        using var reader = cmd.ExecuteReader();
        var list = new List<FileRow>();
        while (reader.Read())
        {
            var state = reader.GetString(3);
            var key = reader.IsDBNull(4) ? null : reader.GetString(4);
            string note;
            string? location = null;
            if (hidden || state is "Deleted")
            {
                note = state == "Deleted" ? "삭제됨—복구되지 않음" : "복구 대조 전";
            }
            else if (state == "Missing")
            {
                note = "파일 없음—확인 필요";
            }
            else if (state == "DeletePending")
            {
                note = "삭제 재처리 대기";
                location = _files.DisplayLocation(key);
            }
            else
            {
                note = string.IsNullOrEmpty(_files.DisplayLocation(key))
                    ? "서버 파일 공유 경로가 아직 설정되지 않았습니다."
                    : "표시 위치";
                location = _files.DisplayLocation(key);
            }
            list.Add(new FileRow
            {
                AttachmentId = reader.GetString(0),
                Kind = reader.GetString(1),
                DisplayName = reader.GetString(2),
                State = state,
                RegisteredAtDisplay = DisplayStamp(reader.GetString(5)),
                RegisteredBy = reader.GetString(6),
                DeletedAtDisplay = reader.IsDBNull(7) ? null : DisplayStamp(reader.GetString(7)),
                DisplayLocation = hidden ? null : location,
                LocationNote = note,
                SizeBytes = hidden || state != "Present" ? 0 : _files.SizeOf(key),
                OpenKind = hidden || state != "Present" ? "none" : FilePreview.KindForName(reader.GetString(2)),
                Sha256 = reader.IsDBNull(8) ? null : reader.GetString(8)
            });
        }
        return list;
    }

    List<HistoryRow> LoadHistory(SqliteConnection conn, string caseId, List<ActionRow> actions)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT h.Seq, h.At, h.Action, h.TargetKind, h.TargetId, h.SummarySafe, u.DisplayName
            FROM History h LEFT JOIN Users u ON u.UserId=h.ActorId
            WHERE h.CaseId=$id ORDER BY h.Seq;
            """, ("$id", caseId));
        using var reader = cmd.ExecuteReader();
        var list = new List<HistoryRow>();
        while (reader.Read())
        {
            var targetId = reader.IsDBNull(4) ? null : reader.GetString(4);
            int? seq = actions.FirstOrDefault(a => a.ActionId == targetId)?.Sequence;
            list.Add(new HistoryRow
            {
                Seq = reader.GetInt64(0),
                AtDisplay = DisplayStamp(reader.GetString(1)),
                Action = reader.GetString(2),
                TargetKind = reader.GetString(3),
                TargetId = targetId,
                Summary = reader.GetString(5),
                ActorName = reader.IsDBNull(6) ? "시스템" : reader.GetString(6),
                ActionSequence = seq == 0 ? null : seq
            });
        }
        return list;
    }

    static string? DisplayMaybe(string? canon) =>
        string.IsNullOrEmpty(canon) ? null : DisplayStamp(canon);
}
