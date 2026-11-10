using AuditRecord.Storage;

namespace AuditRecord.Services;

public sealed class RetentionWorker : BackgroundService
{
    readonly AuditService _audit;
    readonly FileStore _files;
    readonly AppPaths _paths;
    readonly ILogger<RetentionWorker> _log;

    public RetentionWorker(AuditService audit, FileStore files, AppPaths paths, ILogger<RetentionWorker> log)
    {
        _audit = audit;
        _files = files;
        _paths = paths;
        _log = log;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                _audit.ProcessDue();
                _files.CleanupStaging(TimeSpan.FromHours(1));
            }
            catch (Exception ex)
            {
                _log.LogWarning("worker {Type}", ex.GetType().Name);
            }
            try
            {
                await Task.Delay(TimeSpan.FromSeconds(_paths.WorkerSeconds), stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }
}
