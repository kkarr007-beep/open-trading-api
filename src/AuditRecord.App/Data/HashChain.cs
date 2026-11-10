using System.Buffers.Binary;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace AuditRecord.Data;

/// <summary>
/// Append-only SHA-256 chain. Each row hash covers that row's own fields and the previous row hash,
/// so editing the last or only row is visible without a following row.
/// Tail deletion plus a matching rewrite of the stored tail, or a full recomputation, is not detectable
/// without an external checkpoint. This chain is not notarized.
/// </summary>
public static class HashChain
{
    public static byte[] Genesis() => new byte[32];

    public static byte[] Compute(
        long seq,
        string? caseId,
        string at,
        string? actorId,
        string action,
        string targetKind,
        string? targetId,
        string summary,
        byte[] prev)
    {
        using var ms = new MemoryStream();
        Write(ms, "v1");
        Write(ms, seq.ToString(CultureInfo.InvariantCulture));
        Write(ms, caseId);
        Write(ms, at);
        Write(ms, actorId);
        Write(ms, action);
        Write(ms, targetKind);
        Write(ms, targetId);
        Write(ms, summary);
        ms.Write(prev);
        return SHA256.HashData(ms.ToArray());
    }

    public static bool FixedEquals(byte[] a, byte[] b)
    {
        if (a.Length != b.Length) return false;
        return CryptographicOperations.FixedTimeEquals(a, b);
    }

    static void Write(Stream stream, string? value)
    {
        var bytes = Encoding.UTF8.GetBytes(value ?? "");
        Span<byte> len = stackalloc byte[4];
        BinaryPrimitives.WriteInt32LittleEndian(len, bytes.Length);
        stream.Write(len);
        stream.Write(bytes);
    }
}
