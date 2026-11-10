namespace AuditRecord.Storage;

/// <summary>
/// A known configuration problem. The message is one Korean line and is safe to show.
/// Callers must not append the exception stack.
/// </summary>
public sealed class StartupFailure : Exception
{
    public StartupFailure(string message) : base(message)
    {
    }
}
