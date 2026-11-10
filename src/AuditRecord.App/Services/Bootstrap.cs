using AuditRecord.Storage;

namespace AuditRecord.Services;

public sealed class Bootstrap
{
    readonly AppPaths _paths;
    readonly InstanceLock _lock;
    readonly Data.Database _db;
    readonly AuditService _audit;

    public Bootstrap(AppPaths paths, InstanceLock instanceLock, Data.Database db, AuditService audit)
    {
        _paths = paths;
        _lock = instanceLock;
        _db = db;
        _audit = audit;
    }

    public void Run()
    {
        _paths.EnsureDirectories();
        _lock.Acquire(_paths.LockPath);
        _db.Initialize(_paths.Mode);
        if (_paths.Mode == "demo" && !_audit.AnyUsers())
            _audit.SeedDemo();
        _audit.ReconcileRestore();
        if (!_audit.RestoreLocked)
        {
            _audit.HealLedger();
            _audit.ScanMissingFiles();
            _audit.ProcessDue();
        }
    }
}
