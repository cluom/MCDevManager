using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Text.Json;

namespace MCDevManager.Widgets;

public sealed class NeteaseApi : IDisposable
{
    private static readonly Uri Origin = new("https://mc-launcher.webapp.163.com/");
    private readonly HttpClient client;
    private readonly Action<string> diagnostic;
    private readonly SemaphoreSlim limit = new(4);
    public NeteaseApi(SharedAccount account, Action<string>? log = null, HttpMessageHandler? handler = null)
    {
        diagnostic = log ?? (_ => { });
        client = new HttpClient(handler ?? new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
        { BaseAddress = Origin, Timeout = TimeSpan.FromSeconds(25) };
        client.DefaultRequestHeaders.TryAddWithoutValidation("Cookie", CookieHeader(account.Cookies));
        client.DefaultRequestHeaders.UserAgent.ParseAdd("MCDevManager-WindowsWidgets/1.0");
    }
    public static string CookieHeader(IReadOnlyDictionary<string, string> cookies)
    {
        if (cookies.Count == 0) throw new WidgetApiException("login", "请在主客户端重新登录");
        foreach (var (name, value) in cookies)
            if (name.Length == 0 || name.Any(c => !char.IsAsciiLetterOrDigit(c) && c is not ('_' or '-')) ||
                value.Any(c => c < 0x20 || c == 0x7f || c == ';'))
                throw new WidgetApiException("cookie", "登录会话格式异常，请在主客户端重新登录");
        // 原始线格式直接使用；百分号、加号和等号不重新编码/解码。
        return string.Join("; ", cookies.Select(x => x.Key + "=" + x.Value));
    }
    private async Task<JsonElement> Get(string path, CancellationToken ct)
    {
        await limit.WaitAsync(ct);
        var watch = Stopwatch.StartNew();
        try
        {
            using var response = await client.GetAsync(path, ct);
            diagnostic($"api path={path.Split('?')[0]} status={(int)response.StatusCode} ms={watch.ElapsedMilliseconds}");
            if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
                throw new WidgetApiException("login", "登录已失效，请在主客户端重新登录");
            if (!response.IsSuccessStatusCode)
                throw new WidgetApiException("http", $"请求失败（HTTP {(int)response.StatusCode}），可重试");
            using var document = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct));
            var root = document.RootElement;
            var status = Text(root, "status");
            if (status is "401" or "no_login") throw new WidgetApiException("login", "登录已失效，请在主客户端重新登录");
            if (status is not ("200" or "201" or "ok" or "OK" or "Ok") ||
                !root.TryGetProperty("data", out var data) || data.ValueKind == JsonValueKind.Null)
                throw new WidgetApiException("response", "平台未返回可用数据，请重试");
            return data.Clone();
        }
        finally { limit.Release(); }
    }
    public static string Text(JsonElement item, string name) => item.TryGetProperty(name, out var value) &&
        value.ValueKind is not (JsonValueKind.Null or JsonValueKind.Undefined) ? value.ToString() : "";
    public static double? Number(JsonElement item, string name) => item.TryGetProperty(name, out var value) &&
        double.TryParse(value.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var n) && double.IsFinite(n) ? n : null;
    private static JsonElement[] Array(JsonElement item, string name) => item.TryGetProperty(name, out var a) &&
        a.ValueKind == JsonValueKind.Array ? a.EnumerateArray().Select(x => x.Clone()).ToArray() :
        throw new WidgetApiException("response", "接口列表格式异常");
    public async Task<Resource[]> Resources(string platform, CancellationToken ct)
    {
        if (platform == "lobby")
        {
            var owners = await Get("goods/pe/summary?start=0&span=2147483647", ct);
            var items = Array(owners, "items");
            if (Number(owners, "count") != items.Length) throw new WidgetApiException("partial", "作品列表不完整");
            var goods = await Task.WhenAll(items.Select(async owner =>
            {
                var id = Text(owner, "item_id");
                if (!WidgetSettings.IsId(id)) throw new WidgetApiException("response", "作品编号格式异常");
                var result = await Get($"goods/pe/{id}/", ct);
                return Array(result, "goods").Select(x => new Resource(Text(x, "goods_id"), Text(owner, "item_name") + " · " + Text(x, "name")));
            }));
            return ValidateResources(goods.SelectMany(x => x));
        }
        var data = await Get($"items/categories/{platform}/?start=0&span=2147483647", ct);
        var list = Array(data, "item");
        if (Number(data, "count") != list.Length) throw new WidgetApiException("partial", "作品列表不完整");
        return ValidateResources(list.Where(x => Text(x, "online_time") is not ("" or "UNKNOWN") &&
            Number(x, "pri_type") != 9).Select(x => new Resource(Text(x, "item_id"), Text(x, "item_name"))));
    }
    private static Resource[] ValidateResources(IEnumerable<Resource> resources)
    {
        var values = resources.ToArray();
        if (values.Any(x => !WidgetSettings.IsId(x.Id))) throw new WidgetApiException("response", "模组/商品编号格式异常");
        return values.DistinctBy(x => x.Id).OrderBy(x => x.Name).ToArray();
    }
    public async Task<Dictionary<string, double?>> Overview(CancellationToken ct)
    {
        var data = await Get("data_analysis/overview", ct);
        string[] fields = ["this_month_diamond", "last_month_diamond", "this_month_download", "last_month_download",
            "yesterday_diamond", "days_14_average_diamond", "yesterday_download", "days_14_average_download"];
        return fields.ToDictionary(x => x, x => Number(data, x));
    }
    public async Task<DayRow[]> Days(WidgetSettings settings, Resource[] resources, DateOnly end, CancellationToken ct)
    {
        var ids = settings.ModId.Length > 0 ? new[] { settings.ModId } : resources.Select(x => x.Id).ToArray();
        if (ids.Any(x => !resources.Any(r => r.Id == x))) throw new WidgetApiException("selection", "选择的模组已不存在，请重新配置");
        if (ids.Length == 0) return [];
        var start = end.AddDays(1 - settings.Days).ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        var platform = settings.Platform == "lobby" ? "pe" : settings.Platform;
        var endpoint = settings.Platform == "lobby" ? "data_analysis/goods/day_detail/" : "data_analysis/day_detail/";
        var batches = await Task.WhenAll(ids.Chunk(30).Select(async batch =>
        {
            var path = endpoint + $"?platform={platform}&category={platform}&start_date={start}&end_date={end:yyyyMMdd}" +
                "&item_list_str=" + Uri.EscapeDataString(string.Join(",", batch)) +
                "&sort=dateid&order=ASC&start=0&span=2147483647&is_need_us_rank_data=" +
                (settings.Platform == "lobby" ? "false&mc_type=1" : "true");
            var result = await Get(path, ct);
            return Array(result, "data").Select(ParseRow).ToArray();
        }));
        return batches.SelectMany(x => x).ToArray();
    }
    public static DayRow ParseRow(JsonElement item)
    {
        if (!DateOnly.TryParseExact(Text(item, "dateid"), "yyyyMMdd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var date))
            throw new WidgetApiException("response", "统计日期格式异常");
        return new DayRow(Text(item, "iid"), date, Metric.All.ToDictionary(x => x.Id, x => Number(item, x.Id)));
    }
    public void Dispose() { client.Dispose(); limit.Dispose(); }
}
