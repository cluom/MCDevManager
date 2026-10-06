using MCDevManager.Widgets;
using System.Net;
using System.Text;
using System.Text.Json;

var checks = 0;
void Check(bool value, string label) { if (!value) throw new Exception(label); checks++; Console.WriteLine("PASS " + label); }
var end = new DateOnly(2026, 10, 5);
var settings = new WidgetSettings("1", "pe", 3, "", ["diamond", "DAU"]);
Resource[] resources = [new("10", "A"), new("11", "B")];
DayRow[] rows = [new("10", end, new() { ["diamond"] = 12, ["DAU"] = 3 }), new("11", end, new() { ["diamond"] = 8, ["DAU"] = 4 }),
    new("10", end.AddDays(-1), new() { ["diamond"] = 0, ["DAU"] = 0 }), new("11", end.AddDays(-1), new() { ["diamond"] = 0, ["DAU"] = null })];
var series = DayAggregator.Build(settings, resources, rows, end);
Check(series[0].Points[^1].Value == 20 && series[1].Points[^1].Value == 7, "字段合计与DAU大小写");
Check(series[0].Points[0].Value == null && series[1].Points[1].Value == null && series[0].Points[1].Value == 0, "缺失不补零与真实零保留");
try { DayAggregator.Build(settings, resources, rows.Concat([rows[0]]), end); Check(false, "重复防重"); } catch (InvalidDataException) { Check(true, "拒绝重复日数据"); }
Check(ChartScale.IsRelative(series) && ChartScale.PlotPoints(series[0], true)[^1].Value == 100, "混合单位相对趋势");
Check(!ChartScale.IsRelative([series[0]]), "单维度原值刻度");
Check(ChartScale.PlotPoints(new("diamond", [new(end, 0)]), true)[0].Value == 0, "全零不除零");
try { (settings with { Metrics = ["refund_rate"] }).Validate(false); Check(false, "比率保护"); } catch (InvalidDataException) { Check(true, "合计不平均退款率"); }
var account = new SharedAccount("1", "测试", new() { ["S_INFO"] = "abc%2Bdef==", ["token"] = "x+y=z" });
Check(NeteaseApi.CookieHeader(account.Cookies) == "S_INFO=abc%2Bdef==; token=x+y=z", "Cookie原值无二次编码");
try { NeteaseApi.CookieHeader(new Dictionary<string, string> { ["S_INFO"] = "x\r\nInjected: y" }); Check(false, "header注入"); }
catch (WidgetApiException) { Check(true, "拒绝非法头部"); }
var handler = new FakeHandler();
using (var api = new NeteaseApi(account, handler: handler))
{
    var selected = await api.Resources("pe", default);
    Check(selected.Length == 1 && selected[0].Id == "10", "过滤前置与从未上线");
    await api.Days(settings with { ModId = "10" }, selected, end, default);
    Check(handler.Requests[^1].Contains("data_analysis/day_detail/") && handler.Requests[^1].Contains("end_date=20261005") &&
        handler.Requests[^1].Contains("start_date=20261003"), "数据追踪接口及完整日期");
    var lobby = await api.Resources("lobby", default);
    Check(lobby.Length == 1 && lobby[0].Id == "99", "联机大厅展开商品ID");
    await api.Days(settings with { Platform = "lobby", ModId = "99" }, lobby, end, default);
    Check(handler.Requests[^1].Contains("goods/day_detail/") && handler.Requests[^1].Contains("mc_type=1"), "联机大厅日详情参数");
    handler.Status = HttpStatusCode.Unauthorized;
    try { await api.Overview(default); Check(false, "会话失效"); } catch (WidgetApiException e) { Check(e.Code == "login", "401不当作零"); }
}
var root = Path.Combine(Path.GetTempPath(), "MCDevWidgetsTest-" + Guid.NewGuid().ToString("N"));
try
{
    var store = new SecureStore(root);
    store.Import(new([account]));
    Check(store.Accounts().Single().Cookies["S_INFO"] == "abc%2Bdef==", "DPAPI往返");
    Check(!Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(root, "accounts.dat"))).Contains("abc%2Bdef"), "磁盘无明文Cookie");
    var data = new WidgetData(settings.Key, account.Fingerprint, DateTimeOffset.Now, resources, null, series);
    store.Cache("a", data);
    Check(store.Cache("a")?.SettingsKey == settings.Key && store.Cache("b") == null, "缓存实例隔离");
    store.Import(new([])); Check(store.Accounts().Length == 0, "注销撤销会话");
    var card = Cards.Configuration("AccountTrend", settings, [account], resources);
    using var parsed = JsonDocument.Parse(card);
    var metrics = parsed.RootElement.GetProperty("body").EnumerateArray().Single(x => x.TryGetProperty("id", out var id) && id.GetString() == "metrics");
    Check(metrics.GetProperty("isMultiSelect").GetBoolean(), "指标多选控件");
    var display = Cards.Display("AccountTrend", settings, "测试", data, "Large", true, "正在刷新");
    Check(display.Contains("data:image/png;base64,") && !display.Contains("abc%2Bdef"), "同图PNG多线无会话泄露");
    Check(ChartRenderer.Render(series, true).Length > 1_000, "图表生成");
    var stale = data with { FetchedAt = new DateTimeOffset(2026, 9, 30, 12, 0, 0, TimeSpan.FromHours(8)), Overview = new() { ["yesterday_diamond"] = 8 } };
    var overview = Cards.Display("Overview", settings, "测试", stale, "Large", false, null);
    using var staleJson = JsonDocument.Parse(overview);
    Check(staleJson.RootElement.ToString().Contains("09-29") && !staleJson.RootElement.ToString().Contains("昨日收益"), "旧缓存用绝对日期防止跨午夜误标");
}
finally { foreach (var file in Directory.EnumerateFiles(root)) File.Delete(file); Directory.Delete(root); }
Console.WriteLine($"全部通过：{checks} 项");

sealed class FakeHandler : HttpMessageHandler
{
    public List<string> Requests { get; } = [];
    public HttpStatusCode Status = HttpStatusCode.OK;
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var path = request.RequestUri!.PathAndQuery; Requests.Add(path);
        var json = path.StartsWith("/items/categories/") ? """
          {"count":3,"item":[{"item_id":"10","item_name":"A","online_time":"20260101","pri_type":0},
          {"item_id":"11","item_name":"P","online_time":"20260101","pri_type":9},{"item_id":"12","online_time":"UNKNOWN"}]}
          """ : path.StartsWith("/goods/pe/summary") ? """
          {"count":1,"items":[{"item_id":"20","item_name":"地图"}]}
          """ : path.StartsWith("/goods/pe/20/") ? """
          {"goods":[{"goods_id":"99","name":"商品"}]}
          """ : "{\"data\":[]}";
        return Task.FromResult(new HttpResponseMessage(Status) { Content = new StringContent("{\"status\":\"ok\",\"data\":" + json + "}", Encoding.UTF8, "application/json") });
    }
}
