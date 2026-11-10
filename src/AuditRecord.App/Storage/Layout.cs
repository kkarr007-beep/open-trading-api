namespace AuditRecord.Storage;

public sealed class AppPaths
{
    public string Root { get; }
    public string AppDir { get; }
    public string DataDir { get; }
    public string FilesDir { get; }
    public string StagingDir { get; }
    public string BackupDir { get; }
    public string KeysDir { get; }
    public string DatabasePath { get; }
    public string LedgerPath { get; }
    public string LockPath { get; }
    public string RestoreLockPath { get; }
    public string DemoAccessPath { get; }
    public string Mode { get; }
    public string FilesShareUnc { get; }
    public string BackupAllowRoot { get; }
    public bool AllowInsecureHttp { get; }
    public int WorkerSeconds { get; }
    public string? Urls { get; }
    public string? CertificatePath { get; }
    public string? BindAddress { get; }
    public int BindPort { get; }

    public AppPaths(
        string root,
        string mode,
        string? filesShareUnc,
        string? backupAllowRoot,
        bool allowInsecureHttp,
        int workerSeconds,
        string? urls,
        string? certificatePath,
        string? bindAddress,
        int bindPort)
    {
        Root = Path.GetFullPath(root);
        AppDir = AppContext.BaseDirectory;
        DataDir = Path.Combine(Root, "data");
        FilesDir = Path.Combine(Root, "files");
        StagingDir = Path.Combine(FilesDir, "staging");
        BackupDir = Path.Combine(Root, "backup");
        KeysDir = Path.Combine(Root, "app-keys");
        DatabasePath = Path.Combine(DataDir, "audit.db");
        LedgerPath = Path.Combine(DataDir, "retention-ledger.log");
        LockPath = Path.Combine(DataDir, "instance.lock");
        RestoreLockPath = Path.Combine(DataDir, "RESTORE-LOCK");
        DemoAccessPath = Path.Combine(DataDir, "demo-access.local");
        Mode = mode == "demo" ? "demo" : "production";
        FilesShareUnc = (filesShareUnc ?? "").Trim();
        BackupAllowRoot = string.IsNullOrWhiteSpace(backupAllowRoot)
            ? BackupDir
            : Path.GetFullPath(backupAllowRoot);
        AllowInsecureHttp = allowInsecureHttp;
        WorkerSeconds = workerSeconds < 5 ? 5 : workerSeconds;
        Urls = urls;
        CertificatePath = string.IsNullOrWhiteSpace(certificatePath) ? null : certificatePath;
        BindAddress = string.IsNullOrWhiteSpace(bindAddress) ? null : bindAddress;
        BindPort = bindPort;
    }

    public static AppPaths FromConfiguration(IConfiguration config, string contentRoot)
    {
        var rootSetting = config["Audit:Root"];
        string root;
        // The published app directory is the content root. ".." and an empty value
        // both mean the package folder beside app/, which holds data/ and backup/.
        // "var" remains the local-development folder under the content root.
        if (string.IsNullOrWhiteSpace(rootSetting) || rootSetting == "..")
            root = Path.GetFullPath(Path.Combine(contentRoot, ".."));
        else if (rootSetting == "var")
            root = Path.GetFullPath(Path.Combine(contentRoot, "var"));
        else
            root = Path.GetFullPath(rootSetting);

        var mode = (config["Audit:Mode"] ?? "production").Trim().ToLowerInvariant();
        _ = int.TryParse(config["Audit:WorkerSeconds"], out var seconds);
        if (seconds == 0) seconds = 60;
        _ = int.TryParse(config["Audit:Port"], out var port);
        return new AppPaths(
            root,
            mode,
            config["Audit:FilesShareUnc"],
            config["Audit:BackupAllowRoot"],
            string.Equals(config["Audit:AllowInsecureHttp"], "true", StringComparison.OrdinalIgnoreCase),
            seconds,
            config["Audit:Urls"],
            config["Audit:CertificatePath"],
            config["Audit:BindAddress"],
            port);
    }

    public void EnsureDirectories()
    {
        Directory.CreateDirectory(DataDir);
        Directory.CreateDirectory(FilesDir);
        Directory.CreateDirectory(StagingDir);
        Directory.CreateDirectory(BackupDir);
        Directory.CreateDirectory(KeysDir);
        if (OperatingSystem.IsLinux() || OperatingSystem.IsMacOS())
        {
            try
            {
                File.SetUnixFileMode(KeysDir, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
            }
            catch (Exception)
            {
                // the directory still exists; the install guide states the required permissions
            }
        }
        if (!string.IsNullOrWhiteSpace(BackupAllowRoot))
            Directory.CreateDirectory(BackupAllowRoot);
    }
}

public sealed class InstanceLock : IDisposable
{
    FileStream? _stream;

    public void Acquire(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        try
        {
            _stream = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        }
        catch (IOException ex)
        {
            throw new InvalidOperationException("another instance holds the data directory", ex);
        }
    }

    public void Dispose() => _stream?.Dispose();
}
