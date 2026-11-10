namespace AuditRecord.Services;

/// <summary>
/// Single-process gate. Shared for case changes and deletion, exclusive for backup.
/// Writers wait ahead of new readers so a backup is not starved.
/// </summary>
public sealed class MaintenanceGate
{
    readonly object _sync = new();
    int _readers;
    int _waitingWriters;
    bool _writer;

    public IDisposable EnterShared()
    {
        lock (_sync)
        {
            while (_writer || _waitingWriters > 0)
                Monitor.Wait(_sync);
            _readers++;
        }
        return new Releaser(ReleaseShared);
    }

    public IDisposable EnterExclusive()
    {
        lock (_sync)
        {
            _waitingWriters++;
            try
            {
                while (_writer || _readers > 0)
                    Monitor.Wait(_sync);
                _writer = true;
            }
            finally
            {
                _waitingWriters--;
            }
        }
        return new Releaser(ReleaseExclusive);
    }

    void ReleaseShared()
    {
        lock (_sync)
        {
            _readers--;
            if (_readers == 0)
                Monitor.PulseAll(_sync);
        }
    }

    void ReleaseExclusive()
    {
        lock (_sync)
        {
            _writer = false;
            Monitor.PulseAll(_sync);
        }
    }

    sealed class Releaser(Action release) : IDisposable
    {
        int _once;
        public void Dispose()
        {
            if (Interlocked.Exchange(ref _once, 1) == 0)
                release();
        }
    }
}

public sealed class CaseLocks
{
    readonly System.Collections.Concurrent.ConcurrentDictionary<string, object> _locks = new();

    public IDisposable Enter(string caseId)
    {
        var gate = _locks.GetOrAdd(caseId, static _ => new object());
        Monitor.Enter(gate);
        return new Exit(gate);
    }

    sealed class Exit(object gate) : IDisposable
    {
        public void Dispose() => Monitor.Exit(gate);
    }
}
