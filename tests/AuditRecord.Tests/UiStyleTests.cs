namespace AuditRecord.Tests;

public class UiStyleTests
{
    [Fact]
    public void Stylesheet_keeps_korean_words_and_local_controls()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir is not null && !File.Exists(Path.Combine(dir.FullName, "src", "AuditRecord.App", "wwwroot", "css", "site.css")))
            dir = dir.Parent;
        Assert.NotNull(dir);
        var css = File.ReadAllText(Path.Combine(dir.FullName, "src", "AuditRecord.App", "wwwroot", "css", "site.css"));
        Assert.Contains("word-break: keep-all", css, StringComparison.Ordinal);
        Assert.Contains("white-space: nowrap", css, StringComparison.Ordinal);
        Assert.Contains("appearance: none", css, StringComparison.Ordinal);
        Assert.Contains("Malgun Gothic", css, StringComparison.Ordinal);
        Assert.Contains("prefers-reduced-motion", css, StringComparison.Ordinal);
        Assert.DoesNotContain("@font-face", css, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("http://", css, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("https://", css, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("letter-spacing: 0.08em", css, StringComparison.Ordinal);
    }
}
