using System.Net;
using System.Text;
using AuditRecord.Services;
using AuditRecord.Storage;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace AuditRecord.Tests;

public class InstallTests
{
    [Fact]
    public void Listen_url_precedence_is_args_then_env_then_config()
    {
        Assert.Equal("http://127.0.0.1:1", ListenUrls.Resolve(["--urls", "http://127.0.0.1:1"], "http://127.0.0.1:2", "http://127.0.0.1:3"));
        Assert.Equal("http://127.0.0.1:1", ListenUrls.Resolve(["--urls=http://127.0.0.1:1"], "http://127.0.0.1:2", "http://127.0.0.1:3"));
        Assert.Equal("http://127.0.0.1:2", ListenUrls.Resolve([], "http://127.0.0.1:2", "http://127.0.0.1:3"));
        Assert.Equal("http://127.0.0.1:3", ListenUrls.Resolve([], "  ", "http://127.0.0.1:3"));
        Assert.Equal(ListenUrls.DefaultUrl, ListenUrls.Resolve([], null, null));
    }

    [Fact]
    public void Empty_root_is_the_package_folder_beside_the_app()
    {
        var package = Directory.CreateTempSubdirectory("audit-pkg-").FullName;
        var app = Path.Combine(package, "app");
        Directory.CreateDirectory(app);
        try
        {
            var empty = new ConfigurationBuilder().Build();
            var paths = AppPaths.FromConfiguration(empty, app);
            Assert.Equal(Path.GetFullPath(package), paths.Root);
            Assert.Equal(Path.Combine(Path.GetFullPath(package), "data"), paths.DataDir);
            Assert.Equal(Path.Combine(Path.GetFullPath(package), "backup"), paths.BackupDir);
            Assert.DoesNotContain($"{Path.DirectorySeparatorChar}var", paths.DataDir, StringComparison.Ordinal);

            var dotted = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Audit:Root"] = ".." }).Build();
            Assert.Equal(Path.GetFullPath(package), AppPaths.FromConfiguration(dotted, app).Root);
        }
        finally
        {
            Directory.Delete(package, true);
        }
    }

    [Fact]
    public async Task Setup_from_another_address_is_refused_and_creates_nobody()
    {
        await using var factory = new AuditFactory();
        var context = await factory.Server.SendAsync(http =>
        {
            http.Connection.RemoteIpAddress = IPAddress.Parse("10.9.8.7");
            http.Request.Method = "GET";
            http.Request.Path = "/account/setup";
        });
        var body = await new StreamReader(context.Response.Body, Encoding.UTF8).ReadToEndAsync();
        Assert.Equal(403, context.Response.StatusCode);
        Assert.Contains("127.0.0.1", body, StringComparison.Ordinal);
        Assert.DoesNotContain("설치하고 시작하기", body, StringComparison.Ordinal);
        Assert.Empty(factory.Services.GetRequiredService<AuditService>().ListUsers());
    }

    [Fact]
    public async Task Production_error_page_hides_the_exception()
    {
        await using var origin = new AuditFactory();
        await using var factory = origin.WithWebHostBuilder(builder =>
        {
            builder.UseEnvironment("Production");
            builder.ConfigureServices(services => services.AddSingleton<IStartupFilter, ProbeError>());
        });
        using var client = factory.CreateClient(new() { AllowAutoRedirect = false });
        var response = await client.GetAsync("/probe-error");
        var body = await response.Content.ReadAsStringAsync();
        Assert.Equal(HttpStatusCode.InternalServerError, response.StatusCode);
        Assert.Contains("처리를 마치지 못했습니다", body, StringComparison.Ordinal);
        Assert.DoesNotContain("probe-secret-detail", body, StringComparison.Ordinal);
        Assert.DoesNotContain("InvalidOperationException", body, StringComparison.Ordinal);
    }

    sealed class ProbeError : IStartupFilter
    {
        public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next) => app =>
        {
            next(app);
            app.Use(async (context, nextMiddleware) =>
            {
                if (context.Request.Path == "/probe-error")
                    throw new InvalidOperationException("probe-secret-detail");
                await nextMiddleware();
            });
        };
    }
}
