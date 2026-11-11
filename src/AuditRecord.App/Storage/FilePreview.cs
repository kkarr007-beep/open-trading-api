namespace AuditRecord.Storage;

public static class FilePreview
{
    public static string KindForName(string? displayName)
    {
        var ext = Extension(displayName);
        if (ext is ".png" or ".jpg" or ".jpeg" or ".gif" or ".webp") return "image";
        if (ext == ".pdf") return "pdf";
        if (ext is ".txt" or ".csv" or ".md" or ".log") return "text";
        return "download";
    }

    /// <summary>
    /// 표시 이름에 확장자가 없으면 올린 파일의 확장자를 붙인다.
    /// 확장자가 있어야 내려받은 파일이 Windows에서 제 프로그램으로 열린다.
    /// 표시 이름을 비워 두면 올린 파일 이름을 그대로 쓴다.
    /// </summary>
    public static string NameWithExtension(string? displayName, string? uploadedName)
    {
        var uploaded = SafeName(uploadedName);
        var shown = (displayName ?? "").Trim();
        if (shown.Length == 0) return uploaded;
        shown = SafeName(shown);
        if (Path.GetExtension(shown).Length > 1) return shown;
        var ext = Path.GetExtension(uploaded);
        if (ext.Length <= 1) return shown;
        return shown + ext.ToLowerInvariant();
    }

    /// <summary>
    /// 확장자에 맞는 형식. Windows는 이 형식과 파일 이름으로 열 프로그램을 고른다.
    /// 화면 안에서 펼쳐 보여 주는 것은 Decide가 따로 정한다.
    /// </summary>
    public static string ContentTypeFor(string? displayName) => Extension(displayName) switch
    {
        ".pdf" => "application/pdf",
        ".png" => "image/png",
        ".jpg" or ".jpeg" => "image/jpeg",
        ".gif" => "image/gif",
        ".webp" => "image/webp",
        ".bmp" => "image/bmp",
        ".txt" or ".log" or ".md" => "text/plain; charset=utf-8",
        ".csv" => "text/csv; charset=utf-8",
        ".rtf" => "application/rtf",
        ".doc" => "application/msword",
        ".docx" => "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".xls" => "application/vnd.ms-excel",
        ".xlsx" => "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".ppt" => "application/vnd.ms-powerpoint",
        ".pptx" => "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ".hwp" => "application/x-hwp",
        ".hwpx" => "application/hwp+zip",
        ".zip" => "application/zip",
        ".7z" => "application/x-7z-compressed",
        ".tar" => "application/x-tar",
        ".gz" => "application/gzip",
        ".eml" => "message/rfc822",
        ".msg" => "application/vnd.ms-outlook",
        _ => "application/octet-stream"
    };

    public static (string ContentType, bool Inline) Decide(string? displayName, ReadOnlySpan<byte> head)
    {
        // 브라우저가 실행할 수 있는 내용은 형식을 숨기고 내려받기만 한다.
        if (LooksActive(head) || ActiveExtension(displayName))
            return ("application/octet-stream", false);
        var ext = Extension(displayName);
        if (ext == ".pdf" && Starts(head, 0x25, 0x50, 0x44, 0x46))
            return ("application/pdf", true);
        if (ext == ".png" && Starts(head, 0x89, 0x50, 0x4E, 0x47))
            return ("image/png", true);
        if (ext is ".jpg" or ".jpeg" && Starts(head, 0xFF, 0xD8, 0xFF))
            return ("image/jpeg", true);
        if (ext == ".gif" && (Starts(head, 0x47, 0x49, 0x46, 0x38)))
            return ("image/gif", true);
        if (ext == ".webp" && head.Length >= 12 && Starts(head, 0x52, 0x49, 0x46, 0x46) && head[8] == 0x57 && head[9] == 0x45 && head[10] == 0x42 && head[11] == 0x50)
            return ("image/webp", true);
        if (ext is ".txt" or ".csv" or ".md" or ".log" && LooksText(head))
            return ("text/plain; charset=utf-8", true);
        // 화면에 펼치지는 않지만, 내려받은 파일이 제 프로그램으로 열리도록 형식은 확장자에 맞춘다.
        return (ContentTypeFor(displayName), false);
    }

    /// <summary>
    /// 옛 브라우저가 쓰는 ASCII 이름. 확장자는 그대로 남겨 Windows가 열 프로그램을 고를 수 있게 한다.
    /// 한글 이름은 filename*의 UTF-8 값으로 따로 보낸다.
    /// </summary>
    public static string AsciiFallbackName(string? displayName)
    {
        var name = SafeName(displayName);
        var ext = Path.GetExtension(name);
        var stem = Path.GetFileNameWithoutExtension(name);
        var kept = new string(stem.Where(c => c is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or '-' or '_' or '.').ToArray());
        if (kept.Length == 0) kept = "file";
        if (kept.Length > 60) kept = kept[..60];
        var cleanExt = new string(ext.Where(c => c is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or '.').ToArray());
        return kept + cleanExt.ToLowerInvariant();
    }

    public static string SafeName(string? displayName)
    {
        var name = string.IsNullOrWhiteSpace(displayName) ? "file" : Path.GetFileName(displayName.Trim());
        var chars = name.Select(c => c is '"' or '\\' or '\r' or '\n' ? '_' : c).ToArray();
        var cleaned = new string(chars);
        return string.IsNullOrWhiteSpace(cleaned) ? "file" : cleaned;
    }

    static string Extension(string? displayName)
    {
        var name = SafeName(displayName);
        return Path.GetExtension(name).ToLowerInvariant();
    }

    static bool ActiveExtension(string? displayName)
    {
        var ext = Extension(displayName);
        return ext is ".html" or ".htm" or ".svg" or ".xml" or ".xhtml" or ".js" or ".mjs" or ".shtml";
    }

    static bool LooksActive(ReadOnlySpan<byte> head)
    {
        var text = System.Text.Encoding.UTF8.GetString(head).TrimStart('\uFEFF', ' ', '\t', '\r', '\n').ToLowerInvariant();
        return text.StartsWith("<!doctype", StringComparison.Ordinal)
            || text.StartsWith("<html", StringComparison.Ordinal)
            || text.StartsWith("<svg", StringComparison.Ordinal)
            || text.StartsWith("<?xml", StringComparison.Ordinal)
            || text.StartsWith("<script", StringComparison.Ordinal);
    }

    static bool LooksText(ReadOnlySpan<byte> head)
    {
        foreach (var b in head)
        {
            if (b == 0) return false;
            if (b < 9 || (b > 13 && b < 32)) return false;
        }
        return true;
    }

    static bool Starts(ReadOnlySpan<byte> head, params byte[] magic)
    {
        if (head.Length < magic.Length) return false;
        for (var i = 0; i < magic.Length; i++)
            if (head[i] != magic[i]) return false;
        return true;
    }
}
