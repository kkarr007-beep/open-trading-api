using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using AuditRecord.Data;
using AuditRecord.Storage;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;

namespace AuditRecord.Tests;

public class StartupTests
{
    [Fact]
    public void Non_loopback_http_start_is_refused()
    {
        var failure = Assert.Throws<StartupFailure>(() =>
            StartupChecks.EnsureHttpLoopback("http://10.9.8.7:47321", allowInsecureHttp: false));
        Assert.DoesNotContain("\n", failure.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("at AuditRecord", failure.Message, StringComparison.Ordinal);
        Assert.Contains("루프백이 아닌 HTTP", failure.Message, StringComparison.Ordinal);

        using var factory = new AuditFactory().WithWebHostBuilder(builder =>
        {
            builder.UseSetting("Audit:AllowInsecureHttp", "false");
            builder.UseSetting("Audit:Urls", "http://10.9.8.7:47399");
        });
        var thrown = Assert.ThrowsAny<Exception>(() => factory.CreateClient());
        var text = Flatten(thrown);
        Assert.Contains("루프백이 아닌 HTTP", text, StringComparison.Ordinal);
        Assert.DoesNotContain("probe-secret-detail", text, StringComparison.Ordinal);
    }

    [Fact]
    public void Missing_or_wrong_pfx_fails_with_one_korean_line()
    {
        var missing = Assert.Throws<StartupFailure>(() =>
            StartupChecks.EnsureCertificate(Path.Combine(Path.GetTempPath(), "missing-audit.pfx"), "x"));
        Assert.Contains("인증서 파일을 찾을 수 없습니다", missing.Message, StringComparison.Ordinal);
        Assert.DoesNotContain("\n", missing.Message, StringComparison.Ordinal);

        var dir = Directory.CreateTempSubdirectory("audit-pfx-").FullName;
        var path = Path.Combine(dir, "audit.pfx");
        try
        {
            using var rsa = RSA.Create(2048);
            var request = new CertificateRequest("CN=audit-test", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            using var cert = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddDays(2));
            File.WriteAllBytes(path, cert.Export(X509ContentType.Pfx, "correct-secret"));
            StartupChecks.EnsureCertificate(path, "correct-secret");
            var wrong = Assert.Throws<StartupFailure>(() => StartupChecks.EnsureCertificate(path, "wrong-secret"));
            Assert.Contains("인증서 암호가 맞지 않습니다", wrong.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("CryptographicException", wrong.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("\n", wrong.Message, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(dir, true);
        }
    }

    [Fact]
    public void Mode_mismatch_fails_with_one_korean_line()
    {
        var root = Directory.CreateTempSubdirectory("audit-mode-").FullName;
        try
        {
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
            {
                ["Audit:Root"] = root,
                ["Audit:Mode"] = "production"
            }).Build();
            var paths = AppPaths.FromConfiguration(config, root);
            paths.EnsureDirectories();
            var db = new Database(paths);
            db.Initialize("production");
            var failure = Assert.Throws<StartupFailure>(() => db.Initialize("demo"));
            Assert.Contains("데이터베이스 모드가 이 프로세스와 다릅니다", failure.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("\n", failure.Message, StringComparison.Ordinal);
            Assert.DoesNotContain("InvalidOperationException", failure.Message, StringComparison.Ordinal);
        }
        finally
        {
            Directory.Delete(root, true);
        }
    }

    static string Flatten(Exception ex)
    {
        var lines = new List<string>();
        for (var current = ex; current != null; current = current.InnerException)
            lines.Add(current.Message);
        return string.Join(" ", lines);
    }
}
