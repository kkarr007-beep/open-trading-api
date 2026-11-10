using System.Net;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace AuditRecord.Storage;

public static class StartupChecks
{
    public static void EnsureHttpLoopback(string urls, bool allowInsecureHttp)
    {
        if (allowInsecureHttp || IsLoopback(urls)) return;
        throw new StartupFailure("루프백이 아닌 HTTP는 시작하지 않습니다. 내부망은 인증서를 쓰거나 AllowInsecureHttp 를 명시하세요.");
    }

    public static void EnsureCertificate(string? path, string password)
    {
        if (string.IsNullOrWhiteSpace(path)) return;
        if (!File.Exists(path))
            throw new StartupFailure("인증서 파일을 찾을 수 없습니다. Audit:CertificatePath 를 확인하세요.");
        try
        {
            using var cert = X509CertificateLoader.LoadPkcs12FromFile(path, password, X509KeyStorageFlags.EphemeralKeySet);
            if (!cert.HasPrivateKey)
                throw new StartupFailure("인증서에 개인 키가 없습니다. PFX를 확인하세요.");
        }
        catch (StartupFailure)
        {
            throw;
        }
        catch (CryptographicException)
        {
            throw new StartupFailure("인증서 암호가 맞지 않습니다. AUDIT_CERT_PASSWORD 또는 AUDIT_CERT_PASSWORD_FILE 을 확인하세요.");
        }
    }

    public static int DemoClockOffsetDays(string mode, string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw) || raw.Trim() == "0") return 0;
        if (!int.TryParse(raw.Trim(), System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out var days))
            throw new StartupFailure("DemoClockOffsetDays 는 정수여야 합니다.");
        if (mode != "demo" && days != 0)
            throw new StartupFailure("DemoClockOffsetDays 는 예시 모드에서만 사용할 수 있습니다.");
        return days;
    }

    public static bool IsLoopback(string urls)
    {
        foreach (var part in urls.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (!Uri.TryCreate(part, UriKind.Absolute, out var uri)) return false;
            if (uri.Host is not ("localhost" or "127.0.0.1" or "::1")) return false;
        }
        return true;
    }
}
