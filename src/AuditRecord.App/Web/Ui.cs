namespace AuditRecord.Web;

/// <summary>화면 전용 도우미. 상태 문자열을 배지 모양 클래스로 바꾼다.</summary>
public static class Ui
{
    public static string BadgeClass(string? state) => state switch
    {
        "초안" => "badge badge-draft",
        "확정" => "badge badge-confirmed",
        "조치중" => "badge badge-acting",
        "종결" => "badge badge-closed",
        "자료삭제됨" => "badge badge-purged",
        "요구중" => "badge badge-acting",
        "회신수신" => "badge badge-confirmed",
        "이행완료" => "badge badge-closed",
        _ => "badge"
    };
}
