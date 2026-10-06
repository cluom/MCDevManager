using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace MCDevManager.Widgets;

public static class WidgetJson
{
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web);
    public static string Encode<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T Decode<T>(string json) => JsonSerializer.Deserialize<T>(json, Options)
        ?? throw new InvalidDataException("数据为空");
}

public sealed record WidgetSettings(string AccountId, string Platform, int Days, string ModId, string[] Metrics)
{
    public static WidgetSettings Default => new("", "pe", 14, "", ["diamond", "cnt_buy", "DAU"]);
    public WidgetSettings Validate(bool singleMod)
    {
        if (AccountId.Length > 0 && !IsId(AccountId)) throw new InvalidDataException("请选择有效账号");
        if (Platform is not ("pe" or "comp" or "lobby")) throw new InvalidDataException("无效平台");
        if (Days is < 1 or > 90) throw new InvalidDataException("天数须在 1～90 之间");
        if (ModId.Length > 0 && !IsId(ModId)) throw new InvalidDataException("请选择有效模组");
        var selected = Metrics.Distinct().ToArray();
        if (selected.Length == 0 || selected.Any(x => !Metric.All.Any(m => m.Id == x)))
            throw new InvalidDataException("至少选择一个有效维度");
        if (!singleMod && selected.Contains("refund_rate")) throw new InvalidDataException("退款率仅支持单模组");
        return this with { Metrics = selected };
    }
    public static bool IsId(string value) => value.Length is > 0 and <= 64 && value.All(char.IsAsciiDigit);
    [JsonIgnore]
    public string Key => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(WidgetJson.Encode(this))));
}

public sealed record Metric(string Id, string Name, string Unit, bool Sum = true, bool Percent = false)
{
    public static readonly Metric[] All =
    [
        new("diamond", "钻石收益", "钻石"), new("download_num", "下载量", "次"),
        new("cnt_buy", "新增购买", "次"), new("DAU", "日活", "人"),
        new("points", "绿宝石收益", "绿宝石"), new("focus_cnt", "新增粉丝", "人"),
        new("refund_rate", "退款率", "%", false, true),
        new("wishlist_adds_uv", "愿望单新增", "人"), new("wishlist_gifts", "愿望单赠送", "次"),
        new("wishlist_purchases", "愿望单购买", "次"), new("wishlist_removes_uv", "愿望单移除", "人")
    ];
    public static Metric Find(string id) => All.Single(x => x.Id == id);
    public string Label(bool aggregate) => Id == "DAU" && aggregate ? "日活合计 · 未去重" : Name;
    public string Format(double? value) => value is null ? "暂无数据" :
        (Percent ? (value.Value * 100).ToString("0.##", CultureInfo.InvariantCulture) :
            value.Value.ToString("N0", CultureInfo.InvariantCulture)) + " " + Unit;
}

public sealed record SharedAccount(string Id, string Name, Dictionary<string, string> Cookies)
{
    [JsonIgnore]
    public string Fingerprint => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(
        string.Join("\n", Cookies.OrderBy(x => x.Key).Select(x => x.Key + "=" + x.Value)))));
}
public sealed record AccountSnapshot(SharedAccount[] Accounts);
public sealed record Resource(string Id, string Name);
public sealed record DayRow(string Id, DateOnly Date, Dictionary<string, double?> Values);
public sealed record Point(DateOnly Date, double? Value);
public sealed record MetricSeries(string MetricId, Point[] Points);
public sealed record WidgetData(string SettingsKey, string SessionFingerprint, DateTimeOffset FetchedAt,
    Resource[] Resources, Dictionary<string, double?>? Overview, MetricSeries[] Series);
public sealed class WidgetApiException(string code, string message) : Exception(message)
{
    public string Code { get; } = code;
}

public static class DayAggregator
{
    public static MetricSeries[] Build(WidgetSettings settings, IEnumerable<Resource> resources,
        IEnumerable<DayRow> rows, DateOnly end)
    {
        var ids = (settings.ModId.Length > 0 ? new[] { settings.ModId } : resources.Select(x => x.Id)).ToHashSet();
        var filtered = rows.Where(x => ids.Contains(x.Id)).ToArray();
        if (filtered.GroupBy(x => (x.Id, x.Date)).Any(x => x.Count() > 1))
            throw new InvalidDataException("接口返回重复日数据，已停止合计");
        var byDate = filtered.GroupBy(x => x.Date).ToDictionary(x => x.Key, x => x.ToArray());
        return settings.Metrics.Select(metricId =>
        {
            var metric = Metric.Find(metricId);
            var points = Enumerable.Range(0, settings.Days).Select(offset =>
            {
                var date = end.AddDays(offset - settings.Days + 1);
                var day = byDate.GetValueOrDefault(date) ?? [];
                var values = day.Select(x => x.Values.GetValueOrDefault(metricId)).ToArray();
                // 一个日期缺少任一目标作品/字段时不声称账号合计完整，也不补零。
                double? value = ids.Count == 0 || day.Length != ids.Count || values.Any(x => x is null)
                    ? null : metric.Sum ? values.Sum(x => x!.Value) : values.Single();
                return new Point(date, value);
            }).ToArray();
            return new MetricSeries(metricId, points);
        }).ToArray();
    }
}
