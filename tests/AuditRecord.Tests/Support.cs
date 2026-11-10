using System.Net;
using AuditRecord.Data;
using AuditRecord.Domain;
using AuditRecord.Services;
using AuditRecord.Storage;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging.Abstractions;

[assembly: CollectionBehavior(DisableTestParallelization = true)]

namespace AuditRecord.Tests;

public sealed class Harness : IDisposable
{
    public string Root { get; }
    public ManualClock Clock { get; }
    public ScriptedDeleter Deleter { get; } = new();
    public AppPaths Paths { get; }
    public AuditService Svc { get; }
    public const string Password = "correct-horse-battery";

    public Harness(string mode = "production", DateTimeOffset? now = null)
    {
        Root = Directory.CreateTempSubdirectory("audit-test-").FullName;
        Clock = new ManualClock(now ?? Kst.At(2026, 11, 6, 9, 0));
        Paths = new AppPaths(Root, mode, @"\\audit-files\records", null, true, 60, "http://127.0.0.1:47321", null, null, 0);
        Paths.EnsureDirectories();
        var db = new Database(Paths);
        db.Initialize(mode);
        Svc = new AuditService(db, new FileStore(Paths, Deleter), new MaintenanceGate(), Clock, new RetentionLedger(Paths), new Passwords(), Paths, NullLogger<AuditService>.Instance);
    }

    public Actor Admin()
    {
        var result = Svc.SetupAdmin("admin-user", "시험관리", Password);
        if (!result.Ok || result.UserId is null) throw new InvalidOperationException(result.Code);
        return Svc.FindUser(result.UserId)!.ToActor();
    }

    public Actor Account(Actor admin, string login, string name, string role)
    {
        var result = Svc.CreateAccount(admin, login, name, role, Guid.NewGuid().ToString("N"));
        if (!result.Ok || result.UserId is null) throw new InvalidOperationException(result.Message);
        return Svc.FindUser(result.UserId)!.ToActor();
    }

    public void Ack(Actor admin) => Svc.AcknowledgePolicy(admin, Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        try { Directory.Delete(Root, true); } catch { }
    }
}

public sealed class AuditFactory : WebApplicationFactory<Program>
{
    public string Root { get; } = Directory.CreateTempSubdirectory("audit-http-").FullName;
    public ManualClock Clock { get; } = new(Kst.At(2026, 11, 6, 9, 0));
    public ScriptedDeleter Deleter { get; } = new();
    public string Mode { get; init; } = "production";

    protected override void ConfigureWebHost(Microsoft.AspNetCore.Hosting.IWebHostBuilder builder)
    {
        builder.UseSetting("Audit:Root", Root);
        builder.UseSetting("Audit:Mode", Mode);
        builder.UseSetting("Audit:AllowInsecureHttp", "true");
        builder.UseSetting("Audit:Urls", "http://127.0.0.1:47321");
        builder.UseSetting("Audit:FilesShareUnc", @"\\audit-files\records");
        builder.UseSetting("Audit:WorkerSeconds", "3600");
        builder.ConfigureServices(services =>
        {
            services.RemoveAll<IClock>();
            services.AddSingleton<IClock>(Clock);
            services.RemoveAll<IFileDeleter>();
            services.AddSingleton<IFileDeleter>(Deleter);
            services.AddSingleton<IStartupFilter, LoopbackWhenMissing>();
        });
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        try { Directory.Delete(Root, true); } catch { }
    }
}

[CollectionDefinition("serial", DisableParallelization = true)]
public sealed class SerialCollection;

sealed class LoopbackWhenMissing : IStartupFilter
{
    public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next) => app =>
    {
        app.Use(async (context, nextMiddleware) =>
        {
            if (context.Connection.RemoteIpAddress is null)
                context.Connection.RemoteIpAddress = IPAddress.Loopback;
            await nextMiddleware();
        });
        next(app);
    };
}
