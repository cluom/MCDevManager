using System.Text.Json.Nodes;

namespace MCDevManager.Widgets;

public static class Cards
{
    private static JsonObject Text(string value, string size = "default", bool subtle = false) => new()
    { ["type"] = "TextBlock", ["text"] = value, ["size"] = size, ["wrap"] = true, ["isSubtle"] = subtle, ["spacing"] = "small" };
    private static JsonObject Action(string title, string verb) => new()
    { ["type"] = "Action.Execute", ["title"] = title, ["verb"] = verb, ["associatedInputs"] = "auto" };
    private static JsonObject Card(JsonArray body, JsonArray actions) => new()
    { ["type"] = "AdaptiveCard", ["$schema"] = "http://adaptivecards.io/schemas/adaptive-card.json", ["version"] = "1.5", ["body"] = body, ["actions"] = actions };
    private static JsonObject Choice(string id, string label, string value, IEnumerable<(string Name, string Id)> choices, bool multiple = false) => new()
    {
        ["type"] = "Input.ChoiceSet", ["id"] = id, ["label"] = label, ["value"] = value,
        ["style"] = "compact", ["isMultiSelect"] = multiple,
        ["choices"] = new JsonArray(choices.Select(x => (JsonNode)new JsonObject { ["title"] = x.Name, ["value"] = x.Id }).ToArray())
    };
    public static string Configuration(string definition, WidgetSettings settings, SharedAccount[] accounts, Resource[] resources, string? error = null)
    {
        var body = new JsonArray(Text("配置数据小组件", "medium"));
        if (accounts.Length == 0) body.Add(Text("先打开更新后的 MCDevManager 登录一次，同步小组件会话。"));
        if (!string.IsNullOrEmpty(error)) body.Add(Text(error));
        body.Add(Choice("accountId", "账号", settings.AccountId, accounts.Select(x => (x.Name, x.Id))));
        if (definition != "Overview")
        {
            body.Add(Choice("platform", "平台", settings.Platform, [("手机版组件", "pe"), ("电脑版组件", "comp"), ("联机大厅商品", "lobby")]));
            body.Add(Choice("days", "最近完整天数（截至昨日）", settings.Days.ToString(), new[] { 7, 14, 30, 60, 90 }.Select(x => (x + " 天", x.ToString()))));
            if (definition == "ModTrend") body.Add(Choice("modId", "模组 / 商品（切换账号或平台后先更新列表）", settings.ModId, resources.Select(x => (x.Name, x.Id))));
            body.Add(Choice("metrics", "同时显示的维度（多选）", string.Join(",", settings.Metrics),
                Metric.All.Where(x => definition == "ModTrend" || x.Sum).Select(x => (x.Name, x.Id)), true));
            body.Add(Text("混合单位显示相对趋势；图例保留原值。多选外观由 Windows 控件决定。", subtle: true));
        }
        var actions = new JsonArray(Action("保存并刷新", "save"), Action("取消", "cancel"));
        if (definition == "ModTrend") actions.Insert(0, Action("更新模组列表", "catalog"));
        return Card(body, actions).ToJsonString();
    }
    public static string Display(string definition, WidgetSettings settings, string accountName, WidgetData? data, string size, bool refreshing, string? message)
    {
        var body = new JsonArray(Text(accountName.Length == 0 ? "MCDevManager" : accountName, "medium"));
        if (!string.IsNullOrEmpty(message)) body.Add(Text(message));
        if (data is null) body.Add(Text("点击配置选择账号，再手动刷新。"));
        else
        {
            if (definition == "Overview")
            {
                var o = data.Overview ?? [];
                string F(string key) => o.GetValueOrDefault(key)?.ToString("N0") ?? "暂无数据";
                JsonObject Tile(string title, string value, string comparison) => new()
                {
                    ["type"] = "Column", ["width"] = "stretch", ["items"] = new JsonArray(Text(title, subtle: true),
                        new JsonObject { ["type"] = "TextBlock", ["text"] = value, ["size"] = "large", ["weight"] = "bolder", ["wrap"] = true }, Text(comparison, "small", true))
                };
                body.Add(new JsonObject { ["type"] = "ColumnSet", ["columns"] = new JsonArray(
                    Tile("本月收益 · 钻石", F("this_month_diamond"), "上月 " + F("last_month_diamond")),
                    Tile("本月下载", F("this_month_download"), "上月 " + F("last_month_download"))) });
                body.Add(new JsonObject { ["type"] = "ColumnSet", ["columns"] = new JsonArray(
                    Tile("昨日收益 · 钻石", F("yesterday_diamond"), "14 日均 " + F("days_14_average_diamond")),
                    Tile("昨日下载", F("yesterday_download"), "14 日均 " + F("days_14_average_download"))) });
            }
            else
            {
                var mod = settings.ModId.Length == 0 ? "全部模组" : data.Resources.FirstOrDefault(x => x.Id == settings.ModId)?.Name ?? "指定模组";
                body.Add(Text($"{mod} · {settings.Platform} · {settings.Days} 天", "small", true));
                body.Add(new JsonObject { ["type"] = "Image", ["url"] = ChartRenderer.DataUri(data.Series, settings.ModId.Length == 0,
                    size.Equals("Large", StringComparison.OrdinalIgnoreCase) ? 560 : 420), ["size"] = "stretch",
                    ["altText"] = string.Join("；", data.Series.Select(x => Metric.Find(x.MetricId).Name + " " + Metric.Find(x.MetricId).Format(x.Points.LastOrDefault()?.Value))) });
            }
            body.Add(Text("数据更新 " + data.FetchedAt.ToLocalTime().ToString("MM-dd HH:mm") + " · 缺失日期不补零", "small", true));
        }
        var refresh = Action(refreshing ? "正在刷新…" : "刷新", "refresh");
        refresh["isEnabled"] = !refreshing;
        refresh["associatedInputs"] = "none";
        return Card(body, new JsonArray(refresh, Action("配置", "configure"))).ToJsonString();
    }
}
