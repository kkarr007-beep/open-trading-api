using System.Text.Json;

namespace AuditRecord.Storage;

public sealed record LedgerEvent(
    long Seq,
    string EventId,
    string Kind,
    string CaseId,
    string? AttachmentId,
    string At,
    string SummarySafe);

/// <summary>
/// Append-only deletion and hold facts kept beside the database so an older SQLite snapshot
/// can be reconciled. Lines contain no document bodies and no storage paths.
/// </summary>
public sealed class RetentionLedger
{
    readonly string _path;
    readonly object _sync = new();
    static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };

    public RetentionLedger(AppPaths paths) => _path = paths.LedgerPath;

    public void Append(string kind, string caseId, string? attachmentId, string at, string summary)
    {
        lock (_sync)
        {
            var seq = 1L;
            if (File.Exists(_path))
            {
                var last = ReadAll().LastOrDefault();
                if (last != null) seq = last.Seq + 1;
            }
            var ev = new LedgerEvent(seq, Guid.NewGuid().ToString("N"), kind, caseId, attachmentId, at, summary);
            var line = JsonSerializer.Serialize(ev, Json);
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            using var stream = new FileStream(_path, FileMode.Append, FileAccess.Write, FileShare.Read);
            using var writer = new StreamWriter(stream);
            writer.WriteLine(line);
            writer.Flush();
            stream.Flush(true);
        }
    }

    public IReadOnlyList<LedgerEvent> ReadAll()
    {
        if (!File.Exists(_path))
            return [];
        var list = new List<LedgerEvent>();
        foreach (var line in File.ReadLines(_path))
        {
            if (string.IsNullOrWhiteSpace(line)) continue;
            var ev = JsonSerializer.Deserialize<LedgerEvent>(line, Json);
            if (ev is null || string.IsNullOrWhiteSpace(ev.EventId))
                throw new InvalidDataException("ledger line is unreadable");
            list.Add(ev);
        }
        return list;
    }

    public void ReplaceWithCopy(string sourcePath)
    {
        lock (_sync)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.Copy(sourcePath, _path, overwrite: true);
        }
    }
}
