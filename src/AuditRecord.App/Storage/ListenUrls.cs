namespace AuditRecord.Storage;

public static class ListenUrls
{
    public const string DefaultUrl = "http://127.0.0.1:47321";

    // Precedence: --urls, then AUDIT_URLS, then Audit:Urls (files and Audit__Urls), then the default.
    public static string Resolve(string[] args, string? auditUrlsEnvironment, string? configuredUrls)
    {
        var fromArgs = ReadOption(args, "--urls");
        if (!string.IsNullOrWhiteSpace(fromArgs)) return fromArgs.Trim();
        if (!string.IsNullOrWhiteSpace(auditUrlsEnvironment)) return auditUrlsEnvironment.Trim();
        if (!string.IsNullOrWhiteSpace(configuredUrls)) return configuredUrls.Trim();
        return DefaultUrl;
    }

    public static string? ReadOption(string[] args, string name)
    {
        for (var i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            if (arg.StartsWith(name + "=", StringComparison.Ordinal))
                return arg[(name.Length + 1)..];
            if (string.Equals(arg, name, StringComparison.Ordinal) && i + 1 < args.Length)
                return args[i + 1];
        }
        return null;
    }
}
