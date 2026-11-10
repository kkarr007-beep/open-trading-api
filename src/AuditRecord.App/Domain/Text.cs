using System.Globalization;
using System.Text;

namespace AuditRecord.Domain;

public static class Text
{
    public static string? Clean(string? value, int max, bool multiline)
    {
        if (value is null) return null;
        var s = value.Replace("\r\n", "\n", StringComparison.Ordinal).Replace('\r', '\n').Trim();
        if (!multiline)
            s = s.Replace("\n", " ", StringComparison.Ordinal).Trim();
        if (s.Length == 0) return "";
        if (s.Length > max) return null;
        foreach (var ch in s)
        {
            if (ch == '\n' && multiline) continue;
            if (char.IsControl(ch)) return null;
        }
        return s;
    }

    public static string NormalizeLogin(string? login)
    {
        var s = (login ?? "").Trim().ToLowerInvariant();
        return s;
    }

    public static bool LoginOk(string login) =>
        login.Length is >= 3 and <= 40 && login.All(c => char.IsAsciiLetterOrDigit(c) || c is '.' or '-' or '_');

    public static string LikeContains(string term)
    {
        var escaped = term.Replace("\\", "\\\\", StringComparison.Ordinal)
            .Replace("%", "\\%", StringComparison.Ordinal)
            .Replace("_", "\\_", StringComparison.Ordinal);
        return "%" + escaped + "%";
    }

    public static string NewId() => Guid.NewGuid().ToString("N");

    public static string Secret(int length = 20)
    {
        const string alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var bytes = System.Security.Cryptography.RandomNumberGenerator.GetBytes(length);
        var chars = new char[length];
        for (var i = 0; i < length; i++)
            chars[i] = alphabet[bytes[i] % alphabet.Length];
        return new string(chars);
    }

    public static bool IsHexId(string? id) =>
        id is { Length: 32 } && id.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
}

public static class Msg
{
    public const string Saved = "저장했습니다.";
    public const string Forbidden = "이 작업 권한 없음";
    public const string NotFound = "건을 찾을 수 없음";
    public const string Stale = "다른 사용자가 먼저 변경했습니다. 최신 내용을 확인한 뒤 다시 저장하세요.";
    public const string RetentionRequired = "보존연한을 입력해야 확정할 수 있습니다.";
    public const string ReasonRequired = "사유를 입력하세요. 이력에는 앞뒤 값만 남고, 사유 문장은 복사되지 않습니다.";
    public const string HoldActive = "삭제 보류 중이라 삭제하지 않습니다.";
    public const string ContentLocked = "만료되었거나 이미 삭제된 내용은 다시 넣을 수 없습니다. 삭제된 자료는 복구되지 않습니다.";
    public const string Integrity = "이력 훼손—기록의 신뢰성을 확인할 수 없습니다. 변경은 중단되었습니다.";
    public const string Busy = "다른 작업으로 잠시 대기 중입니다. 같은 요청으로 다시 시도하세요.";
    public const string FileFail = "파일 삭제가 끝나지 않았습니다. 성공한 파일만 삭제일이 기록되고, 건 전체를 자료삭제됨으로 표시하지 않습니다.";
    public const string Policy = "보존 삭제 정책을 관리 화면에서 확인하기 전에는 자동 삭제를 실행하지 않습니다.";
    public const string Restore = "복구 대조가 끝나기 전에는 변경할 수 없습니다.";
    public const string BadInput = "입력값을 확인하세요.";
    public const string CloseBlocked = "이행 완료되지 않은 조치요구가 있어 종결할 수 없습니다.";
    public const string NoActionNeedsConfirm = "조치요구가 없습니다. 종결하려면 조치요구 없음을 확인하세요.";
    public const string LoginFailed = "로그인 정보가 올바르지 않습니다.";
    public const string LastAdmin = "마지막 활성 관리자 계정은 끄거나 다른 역할로 바꿀 수 없습니다.";
    public const string PasswordRule = "비밀번호는 12자 이상이어야 하며 로그인 이름과 같을 수 없습니다.";
    public const string CurrentPassword = "현재 비밀번호가 올바르지 않습니다.";
    public const string UnsafePath = "자료 위치를 안전하게 확인할 수 없습니다.";
    public const string TooLarge = "파일이 허용 크기를 넘습니다.";
    public const string AlreadyDone = "이미 처리된 요청입니다.";
    public const string HoldSet = "삭제 보류를 설정했습니다. 이미 삭제된 자료는 복구되지 않습니다.";
    public const string HoldReleased = "삭제 보류를 해제했습니다. 유예기간은 없으며, 이미 만료된 건은 다음 삭제 처리 대상입니다. 이미 삭제된 자료는 복구되지 않습니다.";
    public const string QueuedDelete = "삭제 작업을 접수했습니다. 접수가 완료를 뜻하지는 않습니다.";
    public const string BackupOk = "백업을 마쳤습니다.";
    public const string BackupFail = "백업이 완료되지 않았습니다. 미완료 복사본은 사용하지 마세요.";
    public const string RequestReused = "같은 요청 번호를 다른 작업에 다시 쓸 수 없습니다.";
    public const string NotDraftResult = "확정된 결과 본문은 확정 취소 뒤에만 고칠 수 있습니다.";
    public const string SummaryRequired = "결과 한 줄을 입력해야 확정할 수 있습니다.";
    public const string OpenActions = "완료되지 않은 조치가 있습니다.";
}

public sealed class OpResult
{
    public bool Ok { get; init; }
    public int Status { get; init; }
    public string Code { get; init; } = "";
    public string Message { get; init; } = "";
    public string? CaseId { get; init; }
    public int? Version { get; init; }
    public string? UserId { get; init; }
    public string? Extra { get; init; }
    string? _issued;

    public static OpResult Success(string message, string? caseId = null, int? version = null, string? userId = null, string? extra = null) =>
        new() { Ok = true, Status = 200, Code = "OK", Message = message, CaseId = caseId, Version = version, UserId = userId, Extra = extra };

    public static OpResult Fail(int status, string code, string message) =>
        new() { Ok = false, Status = status, Code = code, Message = message };

    public OpResult WithIssued(string password)
    {
        _issued = password;
        return this;
    }

    public string? TakeIssuedPassword()
    {
        var value = _issued;
        _issued = null;
        return value;
    }

    public override string ToString() => string.Create(CultureInfo.InvariantCulture, $"{Ok}:{Status}:{Code}");
}

public sealed record Actor(string UserId, string Login, string DisplayName, string Role, string Stamp)
{
    public bool IsLead => Role is "파트장" or "관리자";
    public bool IsAdmin => Role == "관리자";
}

public sealed class UserAccount
{
    public string UserId { get; init; } = "";
    public string Login { get; init; } = "";
    public string DisplayName { get; init; } = "";
    public string Role { get; init; } = "";
    public bool Enabled { get; init; }
    public string Stamp { get; init; } = "";
    public bool MustChange { get; init; }
    public int Version { get; init; }
    public bool IsLastAdmin { get; init; }
    public Actor ToActor() => new(UserId, Login, DisplayName, Role, Stamp);
}

public sealed class LoginAttempt
{
    public bool Ok { get; init; }
    public string Message { get; init; } = "";
    public UserAccount? User { get; init; }
}

public sealed class CaseCaps
{
    public bool EditList { get; init; }
    public bool EditResult { get; init; }
    public bool SetRetention { get; init; }
    public bool RetentionNeedsReason { get; init; }
    public bool Confirm { get; init; }
    public bool Cancel { get; init; }
    public bool Close { get; init; }
    public bool AddAction { get; init; }
    public bool Reply { get; init; }
    public bool Verify { get; init; }
    public bool AddFile { get; init; }
    public bool RemoveDraftFile { get; init; }
    public bool EarlyDelete { get; init; }
    public bool Hold { get; init; }
}

public sealed class ActionRow
{
    public string ActionId { get; init; } = "";
    public int Sequence { get; init; }
    public string Title { get; init; } = "";
    public string Type { get; init; } = "";
    public string TargetDept { get; init; } = "";
    public string RequestedOn { get; init; } = "";
    public string ReplyDueOn { get; init; } = "";
    public string? Content { get; init; }
    public string State { get; init; } = "";
    public bool ContentCleared { get; init; }
    public bool Overdue { get; init; }
    public List<FulfillmentRow> Entries { get; init; } = [];
}

public sealed class FulfillmentRow
{
    public string Id { get; init; } = "";
    public string AtDisplay { get; init; } = "";
    public string ActorName { get; init; } = "";
    public string Kind { get; init; } = "";
    public string FromState { get; init; } = "";
    public string ToState { get; init; } = "";
    public string? ReplyBody { get; init; }
    public string? CheckBody { get; init; }
    public bool BodiesCleared { get; init; }
}

public sealed class FileRow
{
    public string AttachmentId { get; init; } = "";
    public string Kind { get; init; } = "";
    public string DisplayName { get; init; } = "";
    public string State { get; init; } = "";
    public string? DeletedAtDisplay { get; init; }
    public string RegisteredAtDisplay { get; init; } = "";
    public string RegisteredBy { get; init; } = "";
    public string? DisplayLocation { get; init; }
    public string LocationNote { get; init; } = "";
}

public sealed class HistoryRow
{
    public long Seq { get; init; }
    public string AtDisplay { get; init; } = "";
    public string Action { get; init; } = "";
    public string TargetKind { get; init; } = "";
    public string? TargetId { get; init; }
    public string Summary { get; init; } = "";
    public string ActorName { get; init; } = "";
    public int? ActionSequence { get; init; }
}

public sealed class CaseDetail
{
    public string CaseId { get; init; } = "";
    public int Year { get; init; }
    public string Category { get; init; } = "";
    public string Title { get; init; } = "";
    public string Target { get; init; } = "";
    public string OwnerUserId { get; init; } = "";
    public string OwnerName { get; init; } = "";
    public string Shelf { get; init; } = "";
    public string BinderNo { get; init; } = "";
    public int? RetentionYears { get; init; }
    public string? FirstConfirmedOn { get; init; }
    public string? ConfirmedOn { get; init; }
    public bool EverConfirmed { get; init; }
    public bool Hold { get; init; }
    public string? HoldReason { get; init; }
    public string? HoldSetAt { get; init; }
    public string? HoldReleasedAt { get; init; }
    public string Phase { get; init; } = "";
    public string DisplayState { get; init; } = "";
    public string? PurgedAt { get; init; }
    public bool BodiesCleared { get; init; }
    public bool PurgeIncomplete { get; init; }
    public int Version { get; init; }
    public string DocumentNo { get; init; } = "";
    public string Summary { get; init; } = "";
    public string? Body { get; init; }
    public string? ConfirmerName { get; init; }
    public string? ExpiryDate { get; init; }
    public string? ExpiryDisplay { get; init; }
    public int? DaysUntilExpiry { get; init; }
    public bool Expired { get; init; }
    public int OpenActions { get; init; }
    public int DoneActions { get; init; }
    public int ActionCount { get; init; }
    public string PurgeNote { get; init; } = "";
    public bool DeletionQueued { get; init; }
    public int DeletionAttempts { get; init; }
    public string? DeletionError { get; init; }
    public bool BodiesHidden { get; init; }
    public CaseCaps Caps { get; init; } = new();
    public List<ActionRow> Actions { get; init; } = [];
    public List<FileRow> Files { get; init; } = [];
    public List<HistoryRow> History { get; init; } = [];
    public string PermissionNote { get; init; } = "확정·종결은 파트장 또는 관리자. 삭제 보류와 연한 전 파일 삭제는 관리자.";
}

public sealed class CaseListItem
{
    public string CaseId { get; init; } = "";
    public int Year { get; init; }
    public string Category { get; init; } = "";
    public string Title { get; init; } = "";
    public string Target { get; init; } = "";
    public string OwnerName { get; init; } = "";
    public string DisplayState { get; init; } = "";
    public string? NextDue { get; init; }
    public bool Overdue { get; init; }
    public int? RetentionYears { get; init; }
    public string? ExpiryDate { get; init; }
    public string? FirstConfirmedOn { get; init; }
    public bool Hold { get; init; }
    public bool DueSoon { get; init; }
    public bool DeletionDelayed { get; init; }
    public bool PurgeIncomplete { get; init; }
    public string DocumentNo { get; init; } = "";
    public string Shelf { get; init; } = "";
    public string BinderNo { get; init; } = "";
    public int OpenActions { get; init; }
    public int Version { get; init; }
}

public sealed class CaseListPage
{
    public List<CaseListItem> Items { get; init; } = [];
    public int Total { get; init; }
    public int Page { get; init; }
    public int PageSize { get; init; }
    public int Pages { get; init; }
}

public sealed class CaseQuery
{
    public int? Year { get; init; }
    public string? Category { get; init; }
    public string? Status { get; init; }
    public bool Overdue { get; init; }
    public bool DueSoon { get; init; }
    public string? Text { get; init; }
    public string Sort { get; init; } = "year";
    public string Dir { get; init; } = "desc";
    public int Page { get; init; } = 1;
    public int PageSize { get; init; } = 50;
}

public sealed class TodoAction
{
    public string CaseId { get; init; } = "";
    public string CaseTitle { get; init; } = "";
    public string ActionId { get; init; } = "";
    public int Sequence { get; init; }
    public string Title { get; init; } = "";
    public string Target { get; init; } = "";
    public string OwnerName { get; init; } = "";
    public string ReplyDueOn { get; init; } = "";
    public int DaysOver { get; init; }
    public string State { get; init; } = "";
}

public sealed class TodoDraft
{
    public string CaseId { get; init; } = "";
    public int Year { get; init; }
    public string Category { get; init; } = "";
    public string Title { get; init; } = "";
    public string Target { get; init; } = "";
    public string OwnerName { get; init; } = "";
    public int? RetentionYears { get; init; }
    public string DisplayState { get; init; } = "";
}

public sealed class TodoExpiry
{
    public string CaseId { get; init; } = "";
    public string Title { get; init; } = "";
    public string? ConfirmedOn { get; init; }
    public string ExpiryDate { get; init; } = "";
    public int DaysLeft { get; init; }
    public int OpenActions { get; init; }
    public bool Hold { get; init; }
    public bool Delayed { get; init; }
}

public sealed class TodoPage
{
    public bool ManagerView { get; init; }
    public List<TodoAction> Overdue { get; init; } = [];
    public List<TodoDraft> WaitingConfirm { get; init; } = [];
    public List<TodoExpiry> Expiring { get; init; } = [];
}

public sealed class SystemState
{
    public string Mode { get; init; } = "production";
    public bool Demo => Mode == "demo";
    public bool PolicyAcknowledged { get; init; }
    public string? PolicyAcknowledgedAt { get; init; }
    public string? PolicyAcknowledgedBy { get; init; }
    public int BackupDays { get; init; }
    public int BackupGenerations { get; init; }
    public bool IntegrityOk { get; init; }
    public bool RestoreLocked { get; init; }
    public string RestoreMessage { get; init; } = "";
    public bool ChainEmpty { get; init; }
}

public sealed class DeleteJobRow
{
    public string JobId { get; init; } = "";
    public string CaseId { get; init; } = "";
    public string Title { get; init; } = "";
    public string Kind { get; init; } = "";
    public string Status { get; init; } = "";
    public string? ErrorCode { get; init; }
    public string AtDisplay { get; init; } = "";
}

public sealed class BackupRow
{
    public string OperationId { get; init; } = "";
    public string State { get; init; } = "";
    public string AtDisplay { get; init; } = "";
    public string ActorName { get; init; } = "";
    public string? BackupId { get; init; }
    public string? ErrorCode { get; init; }
}

public sealed class ProcessReport
{
    public int Enqueued { get; init; }
    public int Completed { get; init; }
    public int Waiting { get; init; }
    public int Skipped { get; init; }
    public string Note { get; init; } = "";
}
