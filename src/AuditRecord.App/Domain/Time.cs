using System.Globalization;

namespace AuditRecord.Domain;

public interface IClock
{
    DateTimeOffset UtcNow { get; }
}

public sealed class SystemClock : IClock
{
    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
}

public sealed class OffsetClock : IClock
{
    readonly IClock _inner;
    readonly TimeSpan _offset;
    public OffsetClock(IClock inner, TimeSpan offset)
    {
        _inner = inner;
        _offset = offset;
    }
    public DateTimeOffset UtcNow => _inner.UtcNow + _offset;
}

/// <summary>Test and demo seeding clock. Not exposed over HTTP.</summary>
public sealed class ManualClock : IClock
{
    DateTimeOffset _utc;
    public ManualClock(DateTimeOffset utc) => _utc = utc.ToUniversalTime();
    public DateTimeOffset UtcNow => _utc;
    public void Set(DateTimeOffset utc) => _utc = utc.ToUniversalTime();
    public void Advance(TimeSpan by) => _utc = _utc.Add(by);
}

public static class Kst
{
    public static readonly TimeZoneInfo Zone = TimeZoneInfo.FindSystemTimeZoneById("Asia/Seoul");

    public static DateTimeOffset ToKst(DateTimeOffset instant) => TimeZoneInfo.ConvertTime(instant, Zone);

    public static DateOnly DateOf(DateTimeOffset instant) => DateOnly.FromDateTime(ToKst(instant).DateTime);

    public static string Canon(DateTimeOffset instant) =>
        ToKst(instant).ToString("yyyy-MM-dd'T'HH:mm:ss.fffffffzzz", CultureInfo.InvariantCulture);

    public static string Display(DateTimeOffset instant) =>
        ToKst(instant).ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);

    public static DateTimeOffset At(int year, int month, int day, int hour = 0, int minute = 0, int second = 0)
    {
        var local = new DateTime(year, month, day, hour, minute, second, DateTimeKind.Unspecified);
        return new DateTimeOffset(local, Zone.GetUtcOffset(local));
    }

    /// <summary>
    /// Calendar years from the anchor date. 29 February moves to the last valid day of February
    /// when the target year is not a leap year (DateOnly.AddYears). Expiry is 00:00 KST that day.
    /// This is the application's chosen rule, not a statutory retention period.
    /// </summary>
    public static DateOnly AddYears(DateOnly anchor, int years) => anchor.AddYears(years);

    public static DateTimeOffset ExpiryInstant(DateOnly anchor, int years)
    {
        var due = AddYears(anchor, years);
        var local = new DateTime(due.Year, due.Month, due.Day, 0, 0, 0, DateTimeKind.Unspecified);
        return new DateTimeOffset(local, Zone.GetUtcOffset(local));
    }

    public static bool IsDue(DateOnly anchor, int years, DateTimeOffset now) =>
        now >= ExpiryInstant(anchor, years);

    public static int? DaysUntil(DateOnly anchor, int years, DateTimeOffset now)
    {
        var due = AddYears(anchor, years);
        return due.DayNumber - DateOf(now).DayNumber;
    }
}
