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

    public static (string ContentType, bool Inline) Decide(string? displayName, ReadOnlySpan<byte> head)
    {
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
        return ("application/octet-stream", false);
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
