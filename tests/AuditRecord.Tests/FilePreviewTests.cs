using System.Net;
using System.Text;
using AuditRecord.Services;
using AuditRecord.Storage;
using Microsoft.Extensions.DependencyInjection;

namespace AuditRecord.Tests;

public class FilePreviewTests
{
    [Fact]
    public void Active_content_is_never_classified_as_inline()
    {
        var html = Encoding.UTF8.GetBytes("<html><script>alert(1)</script></html>");
        var svg = Encoding.UTF8.GetBytes("<svg onload='alert(1)'></svg>");
        Assert.False(FilePreview.Decide("note.html", html).Inline);
        Assert.False(FilePreview.Decide("picture.png", svg).Inline);
        Assert.Equal("application/octet-stream", FilePreview.Decide("picture.png", html).ContentType);
        var png = new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        var image = FilePreview.Decide("scan.png", png);
        Assert.True(image.Inline);
        Assert.Equal("image/png", image.ContentType);
    }

    [Fact]
    public async Task Preview_checks_permission_headers_and_records_history()
    {
        await using var factory = new AuditFactory();
        var svc = factory.Services.GetRequiredService<AuditService>();
        var admin = svc.SetupAdmin("admin-user", "시험관리", Harness.Password);
        var actor = svc.FindUser(admin.UserId!)!.ToActor();
        var id = svc.RegisterCase(actor, 2026, "업무진단", "예시 제목", "예시조직", actor.UserId, "서가", "철", "문서", "한줄", "본문", 5, Guid.NewGuid().ToString("N")).CaseId!;
        var png = new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };
        var added = svc.AddFile(actor, id, 1, "증빙", "그림.png", new MemoryStream(png), Guid.NewGuid().ToString("N"));
        Assert.True(added.Ok, added.Message);
        var htmlBytes = Encoding.UTF8.GetBytes("<svg xmlns='http://www.w3.org/2000/svg'></svg>");
        var second = svc.AddFile(actor, id, added.Version!.Value, "증빙", "page.html", new MemoryStream(htmlBytes), Guid.NewGuid().ToString("N"));
        Assert.True(second.Ok, second.Message);
        var detail = svc.GetCase(actor, id)!;
        var image = detail.Files.Single(file => file.DisplayName == "그림.png");
        var active = detail.Files.Single(file => file.DisplayName == "page.html");

        using var client = factory.CreateClient(new() { AllowAutoRedirect = false, HandleCookies = true });
        var anon = await client.GetAsync($"/cases/{id}/files/{image.AttachmentId}?view=inline");
        Assert.Equal(HttpStatusCode.Redirect, anon.StatusCode);

        await client.PostAsync("/account/login", Form(await Token(client), new()
        {
            ["login"] = "admin-user",
            ["password"] = Harness.Password
        }));
        var preview = await client.GetAsync($"/cases/{id}/files/{image.AttachmentId}?view=inline");
        Assert.Equal(HttpStatusCode.OK, preview.StatusCode);
        Assert.Equal("image/png", preview.Content.Headers.ContentType?.MediaType);
        Assert.Contains("nosniff", preview.Headers.GetValues("X-Content-Type-Options").Single(), StringComparison.Ordinal);
        var csp = preview.Headers.GetValues("Content-Security-Policy").Single();
        Assert.Contains("sandbox", csp, StringComparison.Ordinal);
        Assert.Contains("script-src 'none'", csp, StringComparison.Ordinal);
        Assert.StartsWith("inline", preview.Content.Headers.ContentDisposition?.ToString(), StringComparison.OrdinalIgnoreCase);
        var history = svc.GetCase(actor, id)!.History;
        Assert.Contains(history, row => row.Action == "열람" && row.Summary.Contains("그림.png", StringComparison.Ordinal));

        var download = await client.GetAsync($"/cases/{id}/files/{active.AttachmentId}?view=inline");
        Assert.Equal(HttpStatusCode.OK, download.StatusCode);
        Assert.Equal("application/octet-stream", download.Content.Headers.ContentType?.MediaType);
        Assert.StartsWith("attachment", download.Content.Headers.ContentDisposition?.ToString(), StringComparison.OrdinalIgnoreCase);
        Assert.Contains("script-src 'none'", download.Headers.GetValues("Content-Security-Policy").Single(), StringComparison.Ordinal);
    }

    static async Task<string> Token(HttpClient client)
    {
        var html = await client.GetStringAsync("/account/login");
        var match = System.Text.RegularExpressions.Regex.Match(html, "name=\"__RequestVerificationToken\"[^>]*value=\"([^\"]+)\"");
        return match.Groups[1].Value;
    }

    static FormUrlEncodedContent Form(string token, Dictionary<string, string> fields)
    {
        fields["__RequestVerificationToken"] = token;
        return new FormUrlEncodedContent(fields);
    }
}
