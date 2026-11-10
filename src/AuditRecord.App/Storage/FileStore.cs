using System.Text.RegularExpressions;

namespace AuditRecord.Storage;

public enum FileDeleteResult
{
    Deleted,
    Missing,
    Busy,
    Failed,
    Unsafe
}

public interface IFileDeleter
{
    FileDeleteResult Delete(string absolutePath);
}

public sealed class SystemFileDeleter : IFileDeleter
{
    public FileDeleteResult Delete(string absolutePath)
    {
        try
        {
            if (!File.Exists(absolutePath))
                return FileDeleteResult.Missing;
            var attrs = File.GetAttributes(absolutePath);
            if (attrs.HasFlag(FileAttributes.ReparsePoint))
                return FileDeleteResult.Unsafe;
            File.SetAttributes(absolutePath, FileAttributes.Normal);
            File.Delete(absolutePath);
            return File.Exists(absolutePath) ? FileDeleteResult.Failed : FileDeleteResult.Deleted;
        }
        catch (IOException)
        {
            return FileDeleteResult.Busy;
        }
        catch (UnauthorizedAccessException)
        {
            return FileDeleteResult.Busy;
        }
    }
}

public sealed class ScriptedDeleter : IFileDeleter
{
    readonly SystemFileDeleter _inner = new();
    readonly object _sync = new();
    readonly Dictionary<string, int> _failRemaining = new(StringComparer.Ordinal);

    public void FailTimes(string pathContains, int times)
    {
        lock (_sync)
            _failRemaining[pathContains] = times;
    }

    public FileDeleteResult Delete(string absolutePath)
    {
        lock (_sync)
        {
            foreach (var key in _failRemaining.Keys.ToList())
            {
                if (absolutePath.Contains(key, StringComparison.Ordinal) && _failRemaining[key] > 0)
                {
                    _failRemaining[key]--;
                    return FileDeleteResult.Busy;
                }
            }
        }
        return _inner.Delete(absolutePath);
    }
}

public sealed partial class FileStore
{
    public const long MaxBytes = 100L * 1024L * 1024L;
    readonly string _root;
    readonly string _uncPrefix;
    readonly IFileDeleter _deleter;

    public FileStore(AppPaths paths, IFileDeleter deleter)
    {
        _root = Path.GetFullPath(paths.FilesDir);
        _uncPrefix = paths.FilesShareUnc;
        _deleter = deleter;
    }

    public static string MakeKey(string caseId, string attachmentId) => $"c/{caseId}/a/{attachmentId}";

    [GeneratedRegex(@"^c/[0-9a-f]{32}/a/[0-9a-f]{32}$", RegexOptions.CultureInvariant)]
    private static partial Regex KeyPattern();

    public bool TryResolve(string? key, out string absolute)
    {
        absolute = "";
        if (string.IsNullOrEmpty(key) || !KeyPattern().IsMatch(key))
            return false;
        var root = Path.GetFullPath(_root);
        var combined = Path.GetFullPath(Path.Combine(root, key.Replace('/', Path.DirectorySeparatorChar)));
        var prefix = root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!combined.StartsWith(prefix, StringComparison.Ordinal))
            return false;
        var current = root;
        foreach (var seg in key.Split('/'))
        {
            if (seg is "" or "." or "..")
                return false;
            current = Path.Combine(current, seg);
            if (File.Exists(current) || Directory.Exists(current))
            {
                if (File.GetAttributes(current).HasFlag(FileAttributes.ReparsePoint))
                    return false;
            }
        }
        absolute = combined;
        return true;
    }

    public string? DisplayLocation(string? key)
    {
        if (string.IsNullOrWhiteSpace(_uncPrefix) || string.IsNullOrWhiteSpace(key) || !KeyPattern().IsMatch(key))
            return null;
        var parts = key.Split('/');
        var prefix = _uncPrefix.TrimEnd('\\', '/');
        return prefix + "\\" + parts[1] + "\\" + parts[3];
    }

    public bool Exists(string key) => TryResolve(key, out var abs) && File.Exists(abs);

    public long SizeOf(string? key)
    {
        if (string.IsNullOrEmpty(key) || !TryResolve(key, out var abs) || !File.Exists(abs)) return 0;
        if (File.GetAttributes(abs).HasFlag(FileAttributes.ReparsePoint)) return 0;
        return new FileInfo(abs).Length;
    }

    public string? Sha256Of(string? key)
    {
        if (string.IsNullOrEmpty(key) || !TryResolve(key, out var abs) || !File.Exists(abs)) return null;
        if (File.GetAttributes(abs).HasFlag(FileAttributes.ReparsePoint)) return null;
        using var sha = System.Security.Cryptography.SHA256.Create();
        using var stream = File.OpenRead(abs);
        return Convert.ToHexString(sha.ComputeHash(stream)).ToLowerInvariant();
    }

    public FileStream? OpenRead(string? key)
    {
        if (string.IsNullOrEmpty(key) || !TryResolve(key, out var abs) || !File.Exists(abs)) return null;
        if (File.GetAttributes(abs).HasFlag(FileAttributes.ReparsePoint)) return null;
        return new FileStream(abs, FileMode.Open, FileAccess.Read, FileShare.Read);
    }

    public FileDeleteResult DeleteKey(string? key)
    {
        if (string.IsNullOrEmpty(key))
            return FileDeleteResult.Missing;
        if (!TryResolve(key, out var abs))
            return FileDeleteResult.Unsafe;
        if (!File.Exists(abs))
            return FileDeleteResult.Missing;
        if (File.GetAttributes(abs).HasFlag(FileAttributes.ReparsePoint))
            return FileDeleteResult.Unsafe;
        return _deleter.Delete(abs);
    }

    public (bool Ok, string? Error) WriteNew(string key, Stream content)
    {
        if (!TryResolve(key, out var abs))
            return (false, "UNSAFE");
        var dir = Path.GetDirectoryName(abs)!;
        Directory.CreateDirectory(dir);
        if (File.Exists(abs))
            return (false, "EXISTS");
        var staging = Path.Combine(_root, "staging", TextId());
        Directory.CreateDirectory(Path.Combine(_root, "staging"));
        long count = 0;
        try
        {
            using (var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                var buffer = new byte[81920];
                int read;
                while ((read = content.Read(buffer, 0, buffer.Length)) > 0)
                {
                    count += read;
                    if (count > MaxBytes)
                    {
                        output.Dispose();
                        File.Delete(staging);
                        return (false, "TOO_LARGE");
                    }
                    output.Write(buffer, 0, read);
                }
                output.Flush(true);
            }
            File.Move(staging, abs);
            return (true, null);
        }
        catch (IOException)
        {
            TryDeleteQuiet(staging);
            return (false, "IO");
        }
    }

    public bool CopyTo(string key, string destinationFile)
    {
        if (!TryResolve(key, out var abs) || !File.Exists(abs))
            return false;
        if (File.GetAttributes(abs).HasFlag(FileAttributes.ReparsePoint))
            return false;
        Directory.CreateDirectory(Path.GetDirectoryName(destinationFile)!);
        using var input = new FileStream(abs, FileMode.Open, FileAccess.Read, FileShare.Read);
        using var output = new FileStream(destinationFile, FileMode.CreateNew, FileAccess.Write, FileShare.None);
        input.CopyTo(output);
        output.Flush(true);
        return true;
    }

    public void CleanupStaging(TimeSpan olderThan)
    {
        var dir = Path.Combine(_root, "staging");
        if (!Directory.Exists(dir)) return;
        foreach (var file in Directory.EnumerateFiles(dir))
        {
            try
            {
                if (File.GetAttributes(file).HasFlag(FileAttributes.ReparsePoint))
                    continue;
                if (DateTime.UtcNow - File.GetLastWriteTimeUtc(file) < olderThan)
                    continue;
                File.Delete(file);
            }
            catch (IOException)
            {
                // leave for a later pass
            }
        }
    }

    static void TryDeleteQuiet(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch (IOException) { }
    }

    static string TextId() => Guid.NewGuid().ToString("N");
}
