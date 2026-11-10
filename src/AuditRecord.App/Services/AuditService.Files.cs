using System.Text;
using AuditRecord.Data;
using AuditRecord.Domain;
using AuditRecord.Storage;

namespace AuditRecord.Services;

public sealed class OpenedFile : IDisposable
{
    public required FileStream Content { get; init; }
    public required string ContentType { get; init; }
    public required string DownloadName { get; init; }
    public required bool Inline { get; init; }
    public void Dispose() => Content.Dispose();
}

public sealed class FileHit
{
    public string AttachmentId { get; init; } = "";
    public string CaseId { get; init; } = "";
    public string CaseTitle { get; init; } = "";
    public string Kind { get; init; } = "";
    public string DisplayName { get; init; } = "";
    public string OpenKind { get; init; } = "download";
    public string RegisteredBy { get; init; } = "";
    public string RegisteredAt { get; init; } = "";
    public long SizeBytes { get; init; }
    public string? Sha256 { get; init; }
}

public sealed partial class AuditService
{
    public OpResult OpenAttachment(Actor actor, string caseId, string attachmentId, bool forceDownload, out OpenedFile? opened)
    {
        opened = null;
        _ = actor;
        if (!Text.IsHexId(caseId) || !Text.IsHexId(attachmentId))
            return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
        var gate = GateReady();
        if (gate != null) return gate;
        if (RestoreLocked)
            return OpResult.Fail(503, "RESTORE_LOCKED", "복구 대조 중에는 파일 내용을 열지 않습니다.");
        string? key;
        string name;
        string state;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, """
            SELECT a.StorageKey, a.DisplayName, a.StorageState, c.PurgedAt
            FROM Attachments a JOIN Cases c ON c.CaseId=a.CaseId
            WHERE a.AttachmentId=$id AND a.CaseId=$case;
            """, ("$id", attachmentId), ("$case", caseId)))
        using (var reader = cmd.ExecuteReader())
        {
            if (!reader.Read()) return OpResult.Fail(404, "NOT_FOUND", "자료를 찾을 수 없습니다.");
            key = reader.IsDBNull(0) ? null : reader.GetString(0);
            name = reader.GetString(1);
            state = reader.GetString(2);
            var purged = !reader.IsDBNull(3);
            if (purged || state is "Deleted" or "Missing" or "DeletePending" || string.IsNullOrEmpty(key))
                return OpResult.Fail(404, "NOT_FOUND", "열 수 있는 파일이 없습니다. 삭제되었거나 아직 공유 전입니다.");
        }
        var stream = _files.OpenRead(key);
        if (stream is null)
            return OpResult.Fail(404, "NOT_FOUND", "파일을 찾을 수 없습니다.");
        var head = new byte[512];
        var read = stream.Read(head, 0, head.Length);
        stream.Position = 0;
        var decision = FilePreview.Decide(name, head.AsSpan(0, read));
        var inline = decision.Inline && !forceDownload;
        var safe = FilePreview.SafeName(name);
        var summary = (inline ? "파일 미리보기: " : "파일 내려받기: ") + safe;
        var recorded = Transact(conn =>
        {
            AppendHistory(conn, caseId, Now, actor.UserId, "열람", "자료", attachmentId, summary, null);
            return OpResult.Success("기록");
        });
        if (!recorded.Ok)
        {
            stream.Dispose();
            return recorded;
        }
        opened = new OpenedFile
        {
            Content = stream,
            ContentType = decision.ContentType,
            DownloadName = safe,
            Inline = inline
        };
        return OpResult.Success(inline ? "미리보기" : "내려받기");
    }

    public List<FileHit> SearchFiles(string? name, string? kind, string? person, string? caseTitle)
    {
        using var conn = _db.Open();
        var sql = """
            SELECT a.AttachmentId, a.CaseId, c.Title, a.Kind, a.DisplayName, a.RegisteredAt, u.DisplayName, a.StorageKey, a.Sha256
            FROM Attachments a
            JOIN Cases c ON c.CaseId=a.CaseId
            JOIN Users u ON u.UserId=a.RegisteredBy
            WHERE a.StorageState='Present'
            """;
        var args = new List<(string, object?)>();
        if (!string.IsNullOrWhiteSpace(name)) { sql += " AND a.DisplayName LIKE $n ESCAPE '\\'"; args.Add(("$n", Text.LikeContains(name.Trim()))); }
        if (kind is "결과원문" or "증빙" or "회신첨부") { sql += " AND a.Kind=$k"; args.Add(("$k", kind)); }
        if (!string.IsNullOrWhiteSpace(person)) { sql += " AND u.DisplayName LIKE $p ESCAPE '\\'"; args.Add(("$p", Text.LikeContains(person.Trim()))); }
        if (!string.IsNullOrWhiteSpace(caseTitle)) { sql += " AND c.Title LIKE $t ESCAPE '\\'"; args.Add(("$t", Text.LikeContains(caseTitle.Trim()))); }
        sql += " ORDER BY a.RegisteredAt DESC, a.AttachmentId;";
        using var cmd = Database.Cmd(conn, sql, args.ToArray());
        using var reader = cmd.ExecuteReader();
        var list = new List<FileHit>();
        while (reader.Read())
        {
            var key = reader.IsDBNull(7) ? null : reader.GetString(7);
            var display = reader.GetString(4);
            list.Add(new FileHit
            {
                AttachmentId = reader.GetString(0),
                CaseId = reader.GetString(1),
                CaseTitle = reader.GetString(2),
                Kind = reader.GetString(3),
                DisplayName = display,
                RegisteredAt = DisplayStamp(reader.GetString(5)),
                RegisteredBy = reader.GetString(6),
                Sha256 = reader.IsDBNull(8) ? null : reader.GetString(8),
                SizeBytes = _files.SizeOf(key),
                OpenKind = FilePreview.KindForName(display)
            });
        }
        return list;
    }

    public OpResult VerifyFileHash(Actor actor, string caseId, string attachmentId, string? requestId)
    {
        if (!Text.IsHexId(caseId) || !Text.IsHexId(attachmentId)) return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
        var gate = GateReady();
        if (gate != null) return gate;
        string? key = null;
        string? stored = null;
        using (var conn = _db.Open())
        using (var cmd = Database.Cmd(conn, "SELECT StorageKey, Sha256, StorageState FROM Attachments WHERE AttachmentId=$id AND CaseId=$case;", ("$id", attachmentId), ("$case", caseId)))
        using (var reader = cmd.ExecuteReader())
        {
            if (!reader.Read() || reader.GetString(2) != "Present") return OpResult.Fail(404, "NOT_FOUND", "확인할 파일이 없습니다.");
            key = reader.IsDBNull(0) ? null : reader.GetString(0);
            stored = reader.IsDBNull(1) ? null : reader.GetString(1);
        }
        var now = _files.Sha256Of(key);
        if (now is null) return OpResult.Fail(404, "NOT_FOUND", "파일을 찾을 수 없습니다.");
        var match = stored != null && string.Equals(stored, now, StringComparison.OrdinalIgnoreCase);
        var summary = stored is null ? "등록 시 해시가 없습니다. 현재 " + now : match ? "파일 해시가 같습니다." : "파일 해시가 다릅니다.";
        Transact(conn =>
        {
            AppendHistory(conn, caseId, Now, actor.UserId, "열람", "자료", attachmentId, summary, null);
            return OpResult.Success("기록");
        });
        return match || stored is null
            ? OpResult.Success(summary)
            : OpResult.Fail(422, "HASH_MISMATCH", summary);
    }

    public string ExportCasesCsv(Actor actor, CaseQuery query, string? requestId)
    {
        var first = ListCases(new CaseQuery
        {
            Year = query.Year, Category = query.Category, Status = query.Status,
            Overdue = query.Overdue, DueSoon = query.DueSoon, Text = query.Text,
            Sort = query.Sort, Dir = query.Dir, Page = 1, PageSize = 100
        });
        var items = new List<CaseListItem>(first.Items);
        for (var i = 2; i <= first.Pages; i++)
        {
            items.AddRange(ListCases(new CaseQuery
            {
                Year = query.Year, Category = query.Category, Status = query.Status,
                Overdue = query.Overdue, DueSoon = query.DueSoon, Text = query.Text,
                Sort = query.Sort, Dir = query.Dir, Page = i, PageSize = 100
            }).Items);
        }
        Transact(conn =>
        {
            AppendHistory(conn, null, Now, actor.UserId, "내보내기", "목록", null, "건 목록 CSV " + first.Total.ToString(System.Globalization.CultureInfo.InvariantCulture) + "건", "case-csv:" + requestId);
            return OpResult.Success("기록");
        });
        var sb = new StringBuilder();
        sb.Append('\uFEFF');
        sb.AppendLine("연도,구분,제목,대상,담당,상태,문서번호,서가,철번호");
        foreach (var row in items)
        {
            sb.Append(row.Year).Append(',').Append(Csv(row.Category)).Append(',').Append(Csv(row.Title)).Append(',')
                .Append(Csv(row.Target)).Append(',').Append(Csv(row.OwnerName)).Append(',').Append(Csv(row.DisplayState)).Append(',')
                .Append(Csv(row.DocumentNo)).Append(',').Append(Csv(row.Shelf)).Append(',').Append(Csv(row.BinderNo)).Append('\n');
        }
        return sb.ToString();
    }
}
