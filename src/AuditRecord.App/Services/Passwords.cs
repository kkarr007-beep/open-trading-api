using Microsoft.AspNetCore.Identity;

namespace AuditRecord.Services;

public sealed class Passwords
{
    readonly PasswordHasher<string> _hasher = new();

    public string Hash(string userId, string password) => _hasher.HashPassword(userId, password);

    public bool Verify(string userId, string hash, string password) =>
        _hasher.VerifyHashedPassword(userId, hash, password) != PasswordVerificationResult.Failed;

    public static bool Acceptable(string? password, string login) =>
        !string.IsNullOrEmpty(password)
        && password.Length >= 12
        && password.Length <= 200
        && !string.Equals(password, login, StringComparison.OrdinalIgnoreCase);
}
