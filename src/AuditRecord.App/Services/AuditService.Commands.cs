using System.Globalization;
using AuditRecord.Data;
using AuditRecord.Domain;
using AuditRecord.Storage;
using Microsoft.Data.Sqlite;

namespace AuditRecord.Services;

public sealed partial class AuditService
{
    public OpResult RegisterCase(
        Actor actor, int year, string? category, string? title, string? target, string? ownerId,
        string? shelf, string? binder, string? documentNo, string? summary, string? body, int? retentionYears, string? requestId)
    {
        var gate = GateReady();
        if (gate != null) return gate;
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!TryMeta(year, category, title, target, ownerId, shelf, binder, documentNo, summary, body, retentionYears, out var input, out var error))
            return error!;
        var now = Now;
        using var shared = _gate.EnterShared();
        var result = Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "register", null);
            if (replay != null) return replay;
            if (FindUserIn(conn, input.OwnerId) is not { Enabled: true })
                return OpResult.Fail(422, "BAD_INPUT", "담당을 확인하세요.");
            var id = Text.NewId();
            var at = Kst.Canon(now);
            using (var insert = Database.Cmd(conn, """
                INSERT INTO Cases(
                  CaseId, Year, Category, Title, Target, OwnerUserId, Shelf, BinderNo, RetentionYears,
                  EverConfirmed, Hold, Phase, PurgeIncomplete, Version, CreatedAt)
                VALUES ($id,$year,$cat,$title,$target,$owner,$shelf,$binder,$ret,0,0,'초안',0,1,$at);
                """,
                ("$id", id), ("$year", input.Year), ("$cat", input.Category), ("$title", input.Title),
                ("$target", input.Target), ("$owner", input.OwnerId), ("$shelf", input.Shelf),
                ("$binder", input.Binder), ("$ret", input.Retention), ("$at", at)))
                insert.ExecuteNonQuery();
            var docNo = AllocateDocumentNo(conn, input.Year, input.Category);
            using (var resultInsert = Database.Cmd(conn, """
                INSERT INTO Results(CaseId, DocumentNo, SummaryLine, Body) VALUES ($id,$doc,$sum,$body);
                """,
                ("$id", id), ("$doc", docNo), ("$sum", input.Summary), ("$body", input.Body)))
                resultInsert.ExecuteNonQuery();
            AppendHistory(conn, id, now, actor.UserId, "등록", "건", id, "건 등록", null);
            var done = OpResult.Success(Msg.Saved, id, 1);
            StoreReceipt(conn, actor, requestId!, "register", null, null, done, now);
            return done;
        });
        Log(result.Code, result.CaseId);
        return result;
    }

    public OpResult SaveCaseFields(Actor actor, string caseId, int version, int year, string? category, string? title, string? target, string? ownerId, string? shelf, string? binder, string? requestId)
    {
        if (!TryMeta(year, category, title, target, ownerId, shelf, binder, "", "요약", null, null, out var input, out var error))
            return error!;
        return WithCase(actor, caseId, version, requestId, "save-case", (conn, row, now) =>
        {
            if (row.PurgedAt != null)
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            if (FindUserIn(conn, input.OwnerId) is not { Enabled: true } && input.OwnerId != row.OwnerId)
                return OpResult.Fail(422, "BAD_INPUT", "담당을 확인하세요.");
            var next = UpdateCase(conn, caseId, version, """
                Year=$year, Category=$cat, Title=$title, Target=$target, OwnerUserId=$owner, Shelf=$shelf, BinderNo=$binder
                """,
                ("$year", input.Year), ("$cat", input.Category), ("$title", input.Title), ("$target", input.Target),
                ("$owner", input.OwnerId), ("$shelf", input.Shelf), ("$binder", input.Binder));
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, caseId, now, actor.UserId, "수정", "건", caseId, "건 목록 정보 수정", null);
            return OpResult.Success(Msg.Saved, caseId, next);
        });
    }

    public OpResult SaveResult(Actor actor, string caseId, int version, string? documentNo, string? summary, string? body, string? requestId)
    {
        var doc = Text.Clean(documentNo, 80, false);
        var line = Text.Clean(summary, 160, false);
        var text = body is null ? null : Text.Clean(body, 20000, true);
        if (doc is null || line is null || (body != null && text is null))
            return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        return WithCase(actor, caseId, version, requestId, "save-result", (conn, row, now) =>
        {
            if (row.Phase != "초안")
                return OpResult.Fail(422, "BAD_INPUT", Msg.NotDraftResult);
            if (ContentLocked(conn, row, now))
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            var next = UpdateCase(conn, caseId, version, "", []);
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var upd = Database.Cmd(conn,
                "UPDATE Results SET SummaryLine=$s, Body=$b WHERE CaseId=$id;",
                ("$s", line), ("$b", string.IsNullOrEmpty(text) ? null : text), ("$id", caseId));
            upd.ExecuteNonQuery();
            AppendHistory(conn, caseId, now, actor.UserId, "수정", "결과", caseId, "결과 저장", null);
            return OpResult.Success(Msg.Saved, caseId, next);
        });
    }

    public OpResult ChangeRetention(Actor actor, string caseId, int version, int years, string? reason, string? requestId)
    {
        if (years is < 1 or > 100)
            return OpResult.Fail(422, "BAD_INPUT", "보존연한은 1년에서 100년 사이의 양수입니다. 법정 연한을 대신 정하지 않습니다.");
        return WithCase(actor, caseId, version, requestId, "retention", (conn, row, now) =>
        {
            if (row.PurgedAt != null || row.BodiesClearedAt != null)
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", "이미 삭제된 자료는 연한을 바꿔도 복구되지 않습니다.");
            if (row.Ever && !actor.IsLead)
                return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
            var why = Text.Clean(reason, 240, false);
            if (row.Ever && string.IsNullOrWhiteSpace(why))
                return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
            if (row.Retention == years)
                return OpResult.Success(Msg.Saved, caseId, row.Version);
            var next = UpdateCase(conn, caseId, version, "RetentionYears=$y", ("$y", years));
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            var oldText = row.Retention?.ToString(CultureInfo.InvariantCulture) ?? "없음";
            var summary = string.Create(CultureInfo.InvariantCulture, $"{oldText}년 → {years}년").Replace("없음년", "없음", StringComparison.Ordinal);
            if (!string.IsNullOrWhiteSpace(why)) summary += ". 사유: " + why;
            AppendHistory(conn, caseId, now, actor.UserId, "연한변경", "건", caseId, summary, null);
            return OpResult.Success(Msg.Saved, caseId, next);
        });
    }

    public OpResult Confirm(Actor actor, string caseId, int version, string? requestId)
    {
        if (!actor.IsLead) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        return WithCase(actor, caseId, version, requestId, "confirm", (conn, row, now) =>
        {
            if (row.Phase != "초안")
                return OpResult.Fail(422, "BAD_INPUT", "초안만 확정할 수 있습니다.");
            if (row.Retention is null or < 1)
                return OpResult.Fail(422, "RETENTION_REQUIRED", Msg.RetentionRequired);
            if (string.IsNullOrWhiteSpace(row.Summary))
                return OpResult.Fail(422, "BAD_INPUT", Msg.SummaryRequired);
            if (ContentLocked(conn, row, now))
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            var today = Kst.DateOf(now).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            var first = row.FirstOn?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) ?? today;
            var actions = ScalarInt(conn, "SELECT COUNT(*) FROM Actions WHERE CaseId=$id;", ("$id", caseId));
            var phase = actions > 0 ? "조치중" : "확정";
            var next = UpdateCase(conn, caseId, version,
                "FirstConfirmedOn=$first, ConfirmedOn=$on, EverConfirmed=1, Phase=$phase",
                ("$first", first), ("$on", today), ("$phase", phase));
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var who = Database.Cmd(conn, "UPDATE Results SET ConfirmerUserId=$u WHERE CaseId=$id;", ("$u", actor.UserId), ("$id", caseId));
            who.ExecuteNonQuery();
            var summary = row.Ever ? "재확정. 기산일은 최초 확정일" : "결과 확정";
            AppendHistory(conn, caseId, now, actor.UserId, "결과확정", "결과", caseId, summary, null);
            return OpResult.Success("결과를 확정했습니다. 확정은 징계 확정이나 전자결재가 아닙니다.", caseId, next);
        });
    }

    public OpResult CancelConfirm(Actor actor, string caseId, int version, string? reason, string? requestId)
    {
        if (!actor.IsLead) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var why = Text.Clean(reason, 240, false);
        if (string.IsNullOrWhiteSpace(why))
            return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
        return WithCase(actor, caseId, version, requestId, "cancel", (conn, row, now) =>
        {
            if (row.Phase == "초안")
                return OpResult.Fail(422, "BAD_INPUT", "확정된 건만 취소할 수 있습니다.");
            var deletedFiles = ScalarInt(conn, "SELECT COUNT(*) FROM Attachments WHERE CaseId=$id AND StorageState='Deleted';", ("$id", caseId));
            var cleared = row.BodiesClearedAt != null || deletedFiles > 0;
            var next = UpdateCase(conn, caseId, version, "ConfirmedOn=NULL, Phase='초안'", []);
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            var summary = (cleared ? "확정 취소. 이미 삭제된 자료는 복구되지 않음" : "확정 취소") + ". 사유: " + why;
            AppendHistory(conn, caseId, now, actor.UserId, "확정취소", "결과", caseId, summary, null);
            return OpResult.Success("확정을 취소했습니다. 기존 조치 기록은 유지되며 이미 지운 파일과 본문은 돌아오지 않습니다.", caseId, next);
        });
    }

    public OpResult CloseCase(Actor actor, string caseId, int version, bool noActionConfirmed, string? requestId)
    {
        if (!actor.IsLead) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        return WithCase(actor, caseId, version, requestId, "close", (conn, row, now) =>
        {
            if (row.Phase is not ("확정" or "조치중") || row.PurgedAt != null)
                return OpResult.Fail(422, "BAD_INPUT", "확정 또는 조치중 건만 종결할 수 있습니다.");
            var open = ScalarInt(conn, "SELECT COUNT(*) FROM Actions WHERE CaseId=$id AND ActionState<>'이행완료';", ("$id", caseId));
            var total = ScalarInt(conn, "SELECT COUNT(*) FROM Actions WHERE CaseId=$id;", ("$id", caseId));
            if (open > 0)
                return OpResult.Fail(422, "BAD_INPUT", Msg.CloseBlocked);
            if (total == 0 && !noActionConfirmed)
                return OpResult.Fail(422, "BAD_INPUT", Msg.NoActionNeedsConfirm);
            var next = UpdateCase(conn, caseId, version, "Phase='종결'", []);
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, caseId, now, actor.UserId, "종결", "건", caseId, "종결. 자료는 보존연한까지 남음", null);
            return OpResult.Success("종결했습니다. 종결은 자료 삭제가 아닙니다.", caseId, next);
        });
    }

    public OpResult AddAction(Actor actor, string caseId, int version, string? title, string? type, string? dept, string? requestedOn, string? dueOn, string? content, string? requestId)
    {
        var actionTitle = Text.Clean(title, 120, false);
        var department = Text.Clean(dept, 120, false);
        var text = content is null ? null : Text.Clean(content, 20000, true);
        if (string.IsNullOrEmpty(actionTitle) || string.IsNullOrEmpty(department) || type is not ("지적" or "개선요구" or "권고"))
            return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (content != null && text is null) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!DateOnly.TryParseExact(requestedOn, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
            return OpResult.Fail(422, "BAD_INPUT", "요구일을 확인하세요.");
        if (!DateOnly.TryParseExact(dueOn, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out _))
            return OpResult.Fail(422, "BAD_INPUT", "회신기한을 확인하세요.");
        return WithCase(actor, caseId, version, requestId, "add-action", (conn, row, now) =>
        {
            if (row.Phase == "종결" || ContentLocked(conn, row, now))
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            var seq = ScalarInt(conn, "SELECT COALESCE(MAX(Sequence),0) FROM Actions WHERE CaseId=$id;", ("$id", caseId)) + 1;
            var actionId = Text.NewId();
            using var insert = Database.Cmd(conn, """
                INSERT INTO Actions(ActionId, CaseId, Sequence, Title, Type, TargetDept, RequestedOn, ReplyDueOn, Content, ActionState)
                VALUES ($id,$case,$seq,$title,$type,$dept,$req,$due,$content,'요구중');
                """,
                ("$id", actionId), ("$case", caseId), ("$seq", seq), ("$title", actionTitle), ("$type", type),
                ("$dept", department), ("$req", requestedOn), ("$due", dueOn), ("$content", string.IsNullOrEmpty(text) ? null : text));
            insert.ExecuteNonQuery();
            var phaseSql = row.Phase == "확정" ? "Phase='조치중'" : "";
            var next = UpdateCase(conn, caseId, version, phaseSql, []);
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, caseId, now, actor.UserId, "조치요구", "조치", actionId,
                string.Create(CultureInfo.InvariantCulture, $"조치요구 {seq} 추가"), null);
            return OpResult.Success(Msg.Saved, caseId, next);
        });
    }

    public OpResult RecordReply(Actor actor, string caseId, int version, string actionId, string? reply, string? requestId)
    {
        var text = Text.Clean(reply, 20000, true);
        if (string.IsNullOrEmpty(text)) return OpResult.Fail(422, "BAD_INPUT", "회신 내용을 입력하세요.");
        return WithCase(actor, caseId, version, requestId, "reply:" + actionId, (conn, row, now) =>
        {
            if (ContentLocked(conn, row, now) || row.Phase == "종결")
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            var action = LoadAction(conn, caseId, actionId);
            if (action is null) return OpResult.Fail(404, "NOT_FOUND", "조치요구를 찾을 수 없음");
            if (action.Value.State == "이행완료")
                return OpResult.Fail(422, "BAD_INPUT", "이행 완료된 조치에는 회신을 추가하지 않습니다.");
            var at = Kst.Canon(now);
            var from = action.Value.State;
            using var upd = Database.Cmd(conn, "UPDATE Actions SET ActionState='회신수신' WHERE ActionId=$id;", ("$id", actionId));
            upd.ExecuteNonQuery();
            var next = UpdateCase(conn, caseId, version, "", []);
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            var hist = AppendHistory(conn, caseId, now, actor.UserId, "회신", "조치", actionId, "회신 기록. 이행 완료 아님", null);
            using var entry = Database.Cmd(conn, """
                INSERT INTO Fulfillments(FulfillmentId, ActionId, At, ActorId, FromState, ToState, Kind, ReplyBody, CheckBody, HistorySeq)
                VALUES ($id,$action,$at,$actor,$from,'회신수신','회신',$reply,NULL,$hist);
                """,
                ("$id", Text.NewId()), ("$action", actionId), ("$at", at), ("$actor", actor.UserId),
                ("$from", from), ("$reply", text), ("$hist", hist));
            entry.ExecuteNonQuery();
            return OpResult.Success("회신을 기록했습니다. 회신 수신은 이행 완료가 아닙니다.", caseId, next);
        });
    }

    public OpResult VerifyAction(Actor actor, string caseId, int version, string actionId, string? check, string? requestId)
    {
        var text = Text.Clean(check, 20000, true);
        if (string.IsNullOrEmpty(text)) return OpResult.Fail(422, "BAD_INPUT", "이행 확인 내용을 입력하세요.");
        return WithCase(actor, caseId, version, requestId, "verify:" + actionId, (conn, row, now) =>
        {
            if (ContentLocked(conn, row, now) || row.Phase == "종결")
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            var action = LoadAction(conn, caseId, actionId);
            if (action is null) return OpResult.Fail(404, "NOT_FOUND", "조치요구를 찾을 수 없음");
            if (action.Value.State == "이행완료")
                return OpResult.Fail(422, "BAD_INPUT", "이미 이행 완료된 조치입니다.");
            var at = Kst.Canon(now);
            var from = action.Value.State;
            using var upd = Database.Cmd(conn, "UPDATE Actions SET ActionState='이행완료' WHERE ActionId=$id;", ("$id", actionId));
            upd.ExecuteNonQuery();
            var next = UpdateCase(conn, caseId, version, "", []);
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            var hist = AppendHistory(conn, caseId, now, actor.UserId, "이행확인", "조치", actionId, "이행 확인", null);
            using var entry = Database.Cmd(conn, """
                INSERT INTO Fulfillments(FulfillmentId, ActionId, At, ActorId, FromState, ToState, Kind, ReplyBody, CheckBody, HistorySeq)
                VALUES ($id,$action,$at,$actor,$from,'이행완료','이행확인',NULL,$check,$hist);
                """,
                ("$id", Text.NewId()), ("$action", actionId), ("$at", at), ("$actor", actor.UserId),
                ("$from", from), ("$check", text), ("$hist", hist));
            entry.ExecuteNonQuery();
            return OpResult.Success("이행을 확인했습니다. 종결과 자료 삭제는 별도입니다.", caseId, next);
        });
    }

    public OpResult AddFile(Actor actor, string caseId, int version, string? kind, string? displayName, Stream content, string? requestId)
    {
        var name = Text.Clean(displayName, 120, false);
        if (string.IsNullOrEmpty(name) || kind is not ("결과원문" or "증빙" or "회신첨부"))
            return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        if (!RequestOk(requestId)) return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var gate = GateReady();
        if (gate != null) return gate;
        var now = Now;
        string? writtenKey = null;
        using var shared = _gate.EnterShared();
        using var caseLock = _locks.Enter(caseId);
        var result = Transact(conn =>
        {
            var replay = Replay(conn, actor, requestId!, "add-file", caseId);
            if (replay != null) return replay;
            var row = LoadCase(conn, caseId);
            if (row is null) return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
            if (row.Version != version) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            if (row.Phase == "종결" || ContentLocked(conn, row, now))
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            var attachmentId = Text.NewId();
            var key = FileStore.MakeKey(caseId, attachmentId);
            var write = _files.WriteNew(key, content);
            if (!write.Ok)
            {
                return write.Error switch
                {
                    "TOO_LARGE" => OpResult.Fail(422, "BAD_INPUT", Msg.TooLarge),
                    "UNSAFE" => OpResult.Fail(422, "UNSAFE_STORAGE_KEY", Msg.UnsafePath),
                    _ => OpResult.Fail(503, "SERVICE_UNAVAILABLE", "자료를 저장하지 못했습니다.")
                };
            }
            writtenKey = key;
            var sha = _files.Sha256Of(key) ?? "";
            var at = Kst.Canon(now);
            using var insert = Database.Cmd(conn, """
                INSERT INTO Attachments(AttachmentId, CaseId, Kind, DisplayName, StorageKey, RegisteredAt, RegisteredBy, StorageState, Sha256)
                VALUES ($id,$case,$kind,$name,$key,$at,$by,'Present',$sha);
                """,
                ("$id", attachmentId), ("$case", caseId), ("$kind", kind), ("$name", name),
                ("$key", key), ("$at", at), ("$by", actor.UserId), ("$sha", sha));
            insert.ExecuteNonQuery();
            var next = UpdateCase(conn, caseId, version, "", []);
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            AppendHistory(conn, caseId, now, actor.UserId, "수정", "자료", attachmentId, "자료 추가", null);
            var done = OpResult.Success(Msg.Saved, caseId, next, extra: attachmentId);
            StoreReceipt(conn, actor, requestId!, "add-file", caseId, version, done, now);
            return done;
        });
        if (!result.Ok && writtenKey != null)
            _files.DeleteKey(writtenKey);
        Log(result.Code, caseId);
        return result;
    }

    public OpResult RemoveDraftFile(Actor actor, string caseId, int version, string attachmentId, string? requestId)
    {
        return WithCase(actor, caseId, version, requestId, "remove:" + attachmentId, (conn, row, now) =>
        {
            if (row.Ever || row.Phase != "초안")
                return OpResult.Fail(403, "FORBIDDEN", "한 번이라도 확정된 건의 파일은 확정 전 첨부 빼기로 지울 수 없습니다.");
            return QueueFileJob(conn, actor, row, attachmentId, "RemoveDraft", null, now, version);
        }, after: id => FinishFileJob(id));
    }

    public OpResult EarlyDeleteFile(Actor actor, string caseId, int version, string attachmentId, string? reason, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var why = Text.Clean(reason, 240, false);
        if (string.IsNullOrWhiteSpace(why))
            return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
        return WithCase(actor, caseId, version, requestId, "early:" + attachmentId, (conn, row, now) =>
        {
            if (!row.Ever)
                return OpResult.Fail(422, "BAD_INPUT", "확정 전 파일은 첨부 빼기를 사용합니다.");
            if (row.Hold)
                return OpResult.Fail(409, "HOLD_ACTIVE", Msg.HoldActive);
            if (row.PurgedAt != null || row.BodiesClearedAt != null)
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", Msg.ContentLocked);
            if (row.FirstOn is { } anchor && row.Retention is int years && Kst.IsDue(anchor, years, now))
                return OpResult.Fail(409, "EXPIRED_CONTENT_LOCKED", "연한이 지난 파일은 보존 삭제 처리로 지웁니다.");
            return QueueFileJob(conn, actor, row, attachmentId, "EarlyFile", why, now, version);
        }, after: id => FinishFileJob(id));
    }

    public OpResult SetHold(Actor actor, string caseId, int version, string? reason, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var why = Text.Clean(reason, 240, false);
        if (string.IsNullOrEmpty(why)) return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
        return WithCase(actor, caseId, version, requestId, "hold", (conn, row, now) =>
        {
            if (row.Hold) return OpResult.Success(Msg.HoldSet, caseId, row.Version);
            var at = Kst.Canon(now);
            var next = UpdateCase(conn, caseId, version, "Hold=1, HoldReason=$why, HoldSetAt=$at", ("$why", why), ("$at", at));
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var pause = Database.Cmd(conn, """
                UPDATE DeleteJobs SET Status='Paused', ErrorCode='HOLD_ACTIVE'
                WHERE CaseId=$id AND Status IN ('Pending','RetryWait','Running');
                """, ("$id", caseId));
            pause.ExecuteNonQuery();
            AppendHistory(conn, caseId, now, actor.UserId, "보류", "건", caseId, "삭제 보류 설정. 사유: " + why, null);
            return OpResult.Success(Msg.HoldSet, caseId, next, extra: "hold-set");
        });
    }

    public OpResult ReleaseHold(Actor actor, string caseId, int version, string? reason, string? requestId)
    {
        if (!actor.IsAdmin) return OpResult.Fail(403, "FORBIDDEN", Msg.Forbidden);
        var why = Text.Clean(reason, 240, false);
        if (string.IsNullOrWhiteSpace(why))
            return OpResult.Fail(422, "REASON_REQUIRED", Msg.ReasonRequired);
        var result = WithCase(actor, caseId, version, requestId, "unhold", (conn, row, now) =>
        {
            if (!row.Hold) return OpResult.Fail(422, "BAD_INPUT", "보류 중인 건이 아닙니다.");
            var at = Kst.Canon(now);
            var next = UpdateCase(conn, caseId, version, "Hold=0, HoldReleasedAt=$at", ("$at", at));
            if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            using var resume = Database.Cmd(conn, """
                UPDATE DeleteJobs SET Status='Pending', NextAttemptAt=NULL, ErrorCode=NULL
                WHERE CaseId=$id AND Status='Paused';
                """, ("$id", caseId));
            resume.ExecuteNonQuery();
            AppendHistory(conn, caseId, now, actor.UserId, "보류해제", "건", caseId, "삭제 보류 해제. 사유: " + why, null);
            return OpResult.Success(Msg.HoldReleased, caseId, next, extra: "hold-clear");
        });
        if (result.Ok && result.Extra == "hold-clear")
        {
            _ledger.Append("hold-clear", caseId, null, Kst.Canon(Now), "삭제 보류 해제");
            ProcessDue();
            var fresh = LoadCaseVersion(caseId);
            return OpResult.Success(Msg.HoldReleased, caseId, fresh ?? result.Version);
        }
        return result;
    }

    OpResult WithCase(
        Actor actor,
        string caseId,
        int version,
        string? requestId,
        string command,
        Func<SqliteConnection, CaseSnap, DateTimeOffset, OpResult> body,
        Func<string?, OpResult>? after = null)
    {
        var gate = GateReady();
        if (gate != null) return gate;
        if (!Text.IsHexId(caseId) || !RequestOk(requestId))
            return OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
        var now = Now;
        string? extra = null;
        OpResult result;
        using (var shared = _gate.EnterShared())
        using (var caseLock = _locks.Enter(caseId))
        {
            result = Transact(conn =>
            {
                var replay = Replay(conn, actor, requestId!, command, caseId);
                if (replay != null) return replay;
                var row = LoadCase(conn, caseId);
                if (row is null) return OpResult.Fail(404, "NOT_FOUND", Msg.NotFound);
                if (row.Version != version) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
                var outcome = body(conn, row, now);
                if (!outcome.Ok) return outcome;
                extra = outcome.Extra;
                StoreReceipt(conn, actor, requestId!, command, caseId, version, outcome, now);
                return outcome;
            });
            Log(result.Code, caseId);
            if (result.Ok && result.Extra == "hold-set")
                _ledger.Append("hold-set", caseId, null, Kst.Canon(now), "삭제 보류 설정");
        }
        if (!result.Ok || after == null || extra is null || extra is "hold-set" or "hold-clear")
            return result;
        var followed = after(extra);
        return followed.Ok
            ? OpResult.Success(followed.Message, result.CaseId, followed.Version ?? result.Version)
            : followed;
    }

    OpResult QueueFileJob(SqliteConnection conn, Actor actor, CaseSnap row, string attachmentId, string kind, string? reason, DateTimeOffset now, int version)
    {
        using var read = Database.Cmd(conn, """
            SELECT StorageKey, StorageState FROM Attachments WHERE AttachmentId=$id AND CaseId=$case;
            """, ("$id", attachmentId), ("$case", row.Id));
        using var reader = read.ExecuteReader();
        if (!reader.Read()) return OpResult.Fail(404, "NOT_FOUND", "자료를 찾을 수 없음");
        var key = reader.IsDBNull(0) ? null : reader.GetString(0);
        var state = reader.GetString(1);
        reader.Close();
        if (state == "Deleted" || string.IsNullOrEmpty(key) || FileAlreadyAbsent(key))
        {
            MarkAttachmentGone(conn, attachmentId, now);
            var note = string.IsNullOrWhiteSpace(reason) ? "이미 없음 확인" : "이미 없음 확인. 사유: " + reason;
            AppendHistory(conn, row.Id, now, actor.UserId, "자료삭제", "자료", attachmentId, note, "absent:" + attachmentId);
            CloseOpenJobs(conn, row.Id, kind, attachmentId, now);
            var closed = UpdateCase(conn, row.Id, version, "", []);
            if (closed < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
            return OpResult.Success("이미 없는 파일이라 삭제 작업을 끝냈습니다.", row.Id, closed);
        }
        using (var open = Database.Cmd(conn, """
            SELECT JobId FROM DeleteJobs
            WHERE CaseId=$case AND Kind=$kind AND TargetAttachmentId=$att AND Status<>'Complete'
            ORDER BY CreatedAt LIMIT 1;
            """, ("$case", row.Id), ("$kind", kind), ("$att", attachmentId)))
        {
            var existing = open.ExecuteScalar() as string;
            if (!string.IsNullOrEmpty(existing))
                return OpResult.Success(Msg.QueuedDelete, row.Id, row.Version, extra: existing);
        }
        if (state != "Present")
            return OpResult.Fail(409, "PURGE_IN_PROGRESS", "이 자료는 삭제 처리 중이거나 파일 상태를 확인해야 합니다.");
        var jobId = Text.NewId();
        var at = Kst.Canon(now);
        using var job = Database.Cmd(conn, """
            INSERT INTO DeleteJobs(JobId, CaseId, Kind, TargetAttachmentId, Generation, Status, ApprovedActorId, ReasonSafe, Attempt, CreatedAt)
            VALUES ($id,$case,$kind,$att,1,'Pending',$actor,$why,0,$at);
            """,
            ("$id", jobId), ("$case", row.Id), ("$kind", kind), ("$att", attachmentId),
            ("$actor", actor.UserId), ("$why", string.IsNullOrWhiteSpace(reason) ? null : reason), ("$at", at));
        job.ExecuteNonQuery();
        using var item = Database.Cmd(conn, """
            INSERT INTO DeleteJobItems(JobId, AttachmentId, StorageKey, Status) VALUES ($job,$att,$key,'Pending');
            """, ("$job", jobId), ("$att", attachmentId), ("$key", key));
        item.ExecuteNonQuery();
        using var mark = Database.Cmd(conn,
            "UPDATE Attachments SET StorageState='DeletePending' WHERE AttachmentId=$id;",
            ("$id", attachmentId));
        mark.ExecuteNonQuery();
        var next = UpdateCase(conn, row.Id, version, "", []);
        if (next < 0) return OpResult.Fail(409, "STALE_VERSION", Msg.Stale);
        return OpResult.Success(Msg.QueuedDelete, row.Id, next, extra: jobId);
    }

    bool FileAlreadyAbsent(string? key)
    {
        if (string.IsNullOrEmpty(key)) return true;
        return !_files.TryResolve(key, out var absolute) || !File.Exists(absolute);
    }

    static void MarkAttachmentGone(SqliteConnection conn, string attachmentId, DateTimeOffset now)
    {
        using var cmd = Database.Cmd(conn, """
            UPDATE Attachments
            SET StorageState='Deleted', DeletedAt=COALESCE(DeletedAt,$at), StorageKey=NULL
            WHERE AttachmentId=$id AND StorageState<>'Deleted';
            """, ("$at", Kst.Canon(now)), ("$id", attachmentId));
        cmd.ExecuteNonQuery();
    }

    static void CloseOpenJobs(SqliteConnection conn, string caseId, string kind, string attachmentId, DateTimeOffset now)
    {
        using var jobs = Database.Cmd(conn, """
            SELECT JobId FROM DeleteJobs
            WHERE CaseId=$case AND Kind=$kind AND TargetAttachmentId=$att AND Status<>'Complete';
            """, ("$case", caseId), ("$kind", kind), ("$att", attachmentId));
        var ids = new List<string>();
        using (var reader = jobs.ExecuteReader())
        {
            while (reader.Read()) ids.Add(reader.GetString(0));
        }
        foreach (var id in ids)
        {
            using var item = Database.Cmd(conn, """
                UPDATE DeleteJobItems
                SET Status='AlreadyMissing', StorageKey=NULL, ActualDeletedAt=$at, ErrorCode=NULL
                WHERE JobId=$j AND AttachmentId=$a;
                """, ("$at", Kst.Canon(now)), ("$j", id), ("$a", attachmentId));
            item.ExecuteNonQuery();
            CompleteJob(conn, id, now);
        }
    }

    OpResult FinishFileJob(string? jobId)
    {
        if (string.IsNullOrEmpty(jobId)) return OpResult.Fail(503, "FILE_DELETE_FAILED", Msg.FileFail);
        var outcome = RunSingleFileJob(jobId, Now);
        return outcome;
    }

    static int UpdateCase(SqliteConnection conn, string caseId, int version, string sets, params (string Name, object? Value)[] args)
    {
        var sql = string.IsNullOrEmpty(sets)
            ? "UPDATE Cases SET Version=Version+1 WHERE CaseId=$id AND Version=$v;"
            : $"UPDATE Cases SET {sets}, Version=Version+1 WHERE CaseId=$id AND Version=$v;";
        var all = new List<(string, object?)> { ("$id", caseId), ("$v", version) };
        all.AddRange(args);
        using var cmd = Database.Cmd(conn, sql, all.ToArray());
        return cmd.ExecuteNonQuery() == 1 ? version + 1 : -1;
    }

    bool ContentLocked(SqliteConnection conn, CaseSnap row, DateTimeOffset now)
    {
        if (row.PurgedAt != null || row.BodiesClearedAt != null || row.Incomplete) return true;
        if (row.FirstOn is { } anchor && row.Retention is int years && !row.Hold && Kst.IsDue(anchor, years, now))
            return true;
        return ScalarInt(conn, """
            SELECT COUNT(*) FROM DeleteJobs
            WHERE CaseId=$id AND Kind='Expiry' AND Status IN ('Pending','Running','RetryWait');
            """, ("$id", row.Id)) > 0;
    }

    CaseSnap? LoadCase(SqliteConnection conn, string caseId)
    {
        using var cmd = Database.Cmd(conn, """
            SELECT c.CaseId, c.Year, c.Category, c.Title, c.Target, c.OwnerUserId, c.Shelf, c.BinderNo,
                   c.RetentionYears, c.FirstConfirmedOn, c.ConfirmedOn, c.EverConfirmed, c.Hold, c.HoldReason,
                   c.HoldSetAt, c.HoldReleasedAt, c.Phase, c.PurgedAt, c.BodiesClearedAt, c.PurgeIncomplete, c.Version,
                   r.DocumentNo, r.SummaryLine, r.Body, r.ConfirmerUserId
            FROM Cases c JOIN Results r ON r.CaseId=c.CaseId
            WHERE c.CaseId=$id;
            """, ("$id", caseId));
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return null;
        return new CaseSnap
        {
            Id = reader.GetString(0),
            Year = reader.GetInt32(1),
            Category = reader.GetString(2),
            Title = reader.GetString(3),
            Target = reader.GetString(4),
            OwnerId = reader.GetString(5),
            Shelf = reader.GetString(6),
            Binder = reader.GetString(7),
            Retention = reader.IsDBNull(8) ? null : reader.GetInt32(8),
            FirstOn = reader.IsDBNull(9) ? null : DateOnly.Parse(reader.GetString(9), CultureInfo.InvariantCulture),
            ConfirmedOn = reader.IsDBNull(10) ? null : DateOnly.Parse(reader.GetString(10), CultureInfo.InvariantCulture),
            Ever = reader.GetInt32(11) == 1,
            Hold = reader.GetInt32(12) == 1,
            HoldReason = reader.IsDBNull(13) ? null : reader.GetString(13),
            HoldSetAt = reader.IsDBNull(14) ? null : reader.GetString(14),
            HoldReleasedAt = reader.IsDBNull(15) ? null : reader.GetString(15),
            Phase = reader.GetString(16),
            PurgedAt = reader.IsDBNull(17) ? null : reader.GetString(17),
            BodiesClearedAt = reader.IsDBNull(18) ? null : reader.GetString(18),
            Incomplete = reader.GetInt32(19) == 1,
            Version = reader.GetInt32(20),
            Doc = reader.GetString(21),
            Summary = reader.GetString(22),
            Body = reader.IsDBNull(23) ? null : reader.GetString(23),
            ConfirmerId = reader.IsDBNull(24) ? null : reader.GetString(24)
        };
    }

    (string State, int Sequence)? LoadAction(SqliteConnection conn, string caseId, string actionId)
    {
        using var cmd = Database.Cmd(conn,
            "SELECT ActionState, Sequence FROM Actions WHERE ActionId=$id AND CaseId=$case;",
            ("$id", actionId), ("$case", caseId));
        using var reader = cmd.ExecuteReader();
        if (!reader.Read()) return null;
        return (reader.GetString(0), reader.GetInt32(1));
    }

    int? LoadCaseVersion(string caseId)
    {
        using var conn = _db.Open();
        using var cmd = Database.Cmd(conn, "SELECT Version FROM Cases WHERE CaseId=$id;", ("$id", caseId));
        var value = cmd.ExecuteScalar();
        return value is null or DBNull ? null : Convert.ToInt32(value, CultureInfo.InvariantCulture);
    }

    static bool TryMeta(
        int year, string? category, string? title, string? target, string? ownerId, string? shelf, string? binder,
        string? documentNo, string? summary, string? body, int? retentionYears, out MetaInput input, out OpResult? error)
    {
        input = default!;
        error = null;
        var cleanTitle = Text.Clean(title, 120, false);
        var cleanTarget = Text.Clean(target, 120, false);
        var cleanShelf = Text.Clean(shelf, 80, false) ?? "";
        var cleanBinder = Text.Clean(binder, 80, false) ?? "";
        var cleanDoc = Text.Clean(documentNo, 80, false) ?? "";
        var cleanSummary = Text.Clean(summary, 160, false) ?? "";
        var cleanBody = body is null ? null : Text.Clean(body, 20000, true);
        if (year is < 1990 or > 2100 || category is not ("업무진단" or "준법감사")
            || string.IsNullOrEmpty(cleanTitle) || string.IsNullOrEmpty(cleanTarget) || !Text.IsHexId(ownerId)
            || cleanShelf is null || cleanBinder is null || cleanDoc is null || cleanSummary is null
            || (body != null && cleanBody is null)
            || (retentionYears is < 1 or > 100))
        {
            error = OpResult.Fail(422, "BAD_INPUT", Msg.BadInput);
            return false;
        }
        input = new MetaInput(year, category, cleanTitle, cleanTarget, ownerId!, cleanShelf, cleanBinder, cleanDoc, cleanSummary,
            string.IsNullOrEmpty(cleanBody) ? null : cleanBody, retentionYears);
        return true;
    }

    sealed record MetaInput(int Year, string Category, string Title, string Target, string OwnerId, string Shelf, string Binder, string Doc, string Summary, string? Body, int? Retention);

    sealed class CaseSnap
    {
        public string Id { get; init; } = "";
        public int Year { get; init; }
        public string Category { get; init; } = "";
        public string Title { get; init; } = "";
        public string Target { get; init; } = "";
        public string OwnerId { get; init; } = "";
        public string Shelf { get; init; } = "";
        public string Binder { get; init; } = "";
        public int? Retention { get; init; }
        public DateOnly? FirstOn { get; init; }
        public DateOnly? ConfirmedOn { get; init; }
        public bool Ever { get; init; }
        public bool Hold { get; init; }
        public string? HoldReason { get; init; }
        public string? HoldSetAt { get; init; }
        public string? HoldReleasedAt { get; init; }
        public string Phase { get; init; } = "";
        public string? PurgedAt { get; init; }
        public string? BodiesClearedAt { get; init; }
        public bool Incomplete { get; init; }
        public int Version { get; init; }
        public string Doc { get; init; } = "";
        public string Summary { get; init; } = "";
        public string? Body { get; init; }
        public string? ConfirmerId { get; init; }
    }
}
