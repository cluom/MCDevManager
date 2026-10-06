using Microsoft.Windows.Widgets.Providers;
using System.Text.Json;

namespace MCDevManager.Widgets;

public sealed class WidgetProvider : IWidgetProvider, IWidgetProvider2
{
    private sealed class Instance(string id, string definition, string size, WidgetSettings settings)
    {
        public readonly string Id = id;
        public readonly string Definition = definition;
        public string Size = size;
        public WidgetSettings Settings = settings;
        public WidgetSettings Draft = settings;
        public WidgetData? Data;
        public Resource[] DraftResources = [];
        public bool Configuring;
        public bool Refreshing;
        public string? Message;
        public int Generation;
        public CancellationTokenSource? Pending;
    }
    private static readonly object Gate = new();
    private static readonly Dictionary<string, Instance> Instances = [];
    private static readonly SecureStore Store = new();
    private static FileSystemWatcher? sessionWatcher;
    private static bool initialized;
    public WidgetProvider()
    {
        lock (Gate)
        {
            if (initialized) return;
            foreach (var info in WidgetManager.GetDefault().GetWidgetInfos())
            {
                var context = info.WidgetContext;
                Add(context.Id, context.DefinitionId, context.Size.ToString(), info.CustomState);
            }
            initialized = true;
            sessionWatcher = new FileSystemWatcher(SecureStore.DefaultRoot, "accounts.dat")
            { NotifyFilter = NotifyFilters.FileName | NotifyFilters.LastWrite | NotifyFilters.CreationTime };
            sessionWatcher.Changed += (_, _) => SessionsChanged();
            sessionWatcher.Created += (_, _) => SessionsChanged();
            sessionWatcher.Renamed += (_, _) => SessionsChanged();
            sessionWatcher.EnableRaisingEvents = true;
        }
    }
    private static void SessionsChanged() => Safe(() =>
    {
        var accounts = Store.Accounts();
        foreach (var state in Instances.Values)
        {
            var account = accounts.FirstOrDefault(x => x.Id == state.Settings.AccountId);
            if (account is null || (state.Data is not null && state.Data.SessionFingerprint != account.Fingerprint))
            {
                state.Pending?.Cancel(); state.Generation++; state.Refreshing = false;
                state.Data = null; state.Message = "登录会话已变化，请重新配置或刷新";
                Store.DeleteCache(state.Id);
            }
            Update(state);
        }
    });
    private static Instance Add(string id, string definition, string size, string state = "")
    {
        if (Instances.TryGetValue(id, out var known)) return known;
        var settings = WidgetSettings.Default;
        try { if (state.Length > 0) settings = WidgetJson.Decode<WidgetSettings>(state).Validate(definition == "ModTrend"); }
        catch (Exception) { Store.Diagnostic("state_reset invalid=1"); }
        var instance = new Instance(id, definition, size, settings);
        Instances[id] = instance;
        try
        {
            var account = Store.Accounts().FirstOrDefault(x => x.Id == settings.AccountId);
            var cache = Store.Cache(id);
            if (cache is not null && cache.SettingsKey == settings.Key && cache.SessionFingerprint == account?.Fingerprint) instance.Data = cache;
        }
        catch (Exception) { Store.Diagnostic("cache_read failed=1"); }
        return instance;
    }
    private static void Update(Instance instance)
    {
        // Gate 保证结果发布与配置切换串行；WinRT 回调对象绝不跨异步生命周期保存。
        var accounts = Store.Accounts();
        var account = accounts.FirstOrDefault(x => x.Id == instance.Settings.AccountId);
        if (account is null || instance.Data?.SessionFingerprint != account.Fingerprint) instance.Data = null;
        var template = instance.Configuring
            ? Cards.Configuration(instance.Definition, instance.Draft, accounts, instance.DraftResources, instance.Message)
            : Cards.Display(instance.Definition, instance.Settings, account?.Name ?? "", instance.Data, instance.Size, instance.Refreshing, instance.Message);
        WidgetManager.GetDefault().UpdateWidget(new WidgetUpdateRequestOptions(instance.Id)
        { Template = template, Data = "{}", CustomState = WidgetJson.Encode(instance.Settings) });
    }
    private static void Safe(Action action)
    {
        try { lock (Gate) action(); }
        catch (Exception e) { Store.Diagnostic("callback_failed type=" + e.GetType().Name); }
    }
    public void CreateWidget(WidgetContext context)
    {
        var id = context.Id; var definition = context.DefinitionId; var size = context.Size.ToString();
        Safe(() => { var state = Add(id, definition, size); state.Configuring = true; Update(state); });
    }
    public void DeleteWidget(string widgetId, string customState) => Safe(() =>
    {
        if (Instances.Remove(widgetId, out var state)) state.Pending?.Cancel();
        Store.DeleteCache(widgetId);
    });
    public void Activate(WidgetContext context)
    {
        var id = context.Id; var definition = context.DefinitionId; var size = context.Size.ToString();
        Safe(() => { var state = Add(id, definition, size); state.Size = size; Update(state); });
    }
    public void Deactivate(string widgetId) { }
    public void OnWidgetContextChanged(WidgetContextChangedArgs args)
    {
        var id = args.WidgetContext.Id; var size = args.WidgetContext.Size.ToString();
        Safe(() => { if (Instances.TryGetValue(id, out var state)) { state.Size = size; Update(state); } });
    }
    public void OnCustomizationRequested(WidgetCustomizationRequestedArgs args)
    {
        var id = args.WidgetContext.Id;
        Safe(() => { if (Instances.TryGetValue(id, out var state)) Configure(state); });
    }
    private static void Configure(Instance state)
    {
        state.Configuring = true; state.Draft = state.Settings; state.DraftResources = state.Data?.Resources ?? [];
        state.Message = null; Update(state);
    }
    public void OnActionInvoked(WidgetActionInvokedArgs args)
    {
        var id = args.WidgetContext.Id; var verb = args.Verb; var payload = args.Data;
        Safe(() =>
        {
            if (!Instances.TryGetValue(id, out var state)) return;
            if (verb == "configure") { Configure(state); return; }
            if (verb == "cancel") { state.Configuring = false; state.Message = null; Update(state); return; }
            if (verb is "save" or "catalog")
            {
                try
                {
                    using var json = JsonDocument.Parse(payload);
                    var data = json.RootElement;
                    string Field(string name, string fallback) => data.TryGetProperty(name, out var value) ? value.ToString() : fallback;
                    var draft = new WidgetSettings(Field("accountId", state.Draft.AccountId), Field("platform", state.Draft.Platform),
                        int.Parse(Field("days", state.Draft.Days.ToString())), Field("modId", state.Draft.ModId),
                        Field("metrics", string.Join(",", state.Draft.Metrics)).Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries));
                    draft = draft.Validate(state.Definition == "ModTrend");
                    if (verb == "catalog")
                    {
                        state.Draft = draft with { ModId = "" }; state.DraftResources = []; state.Message = "正在获取模组列表…";
                        Update(state); _ = LoadCatalog(state.Id, state.Draft); return;
                    }
                    if (draft.AccountId.Length == 0) throw new InvalidDataException("请选择账号");
                    if (state.Definition == "ModTrend" && draft.ModId.Length == 0) throw new InvalidDataException("请更新列表并选择模组");
                    state.Pending?.Cancel(); state.Generation++; state.Refreshing = false; state.Settings = draft;
                    state.Data = null; state.Configuring = false; state.Message = null;
                }
                catch (Exception e) when (e is InvalidDataException or FormatException or JsonException)
                { state.Message = e is InvalidDataException ? e.Message : "配置格式异常，请重新选择"; Update(state); return; }
            }
            if (verb is "save" or "refresh") StartRefresh(state);
        });
    }
    private static async Task LoadCatalog(string id, WidgetSettings draft)
    {
        Resource[]? resources = null; string? error = null;
        try
        {
            var account = Store.Accounts().FirstOrDefault(x => x.Id == draft.AccountId) ?? throw new WidgetApiException("login", "请先在主客户端登录");
            using var api = new NeteaseApi(account, Store.Diagnostic);
            resources = await api.Resources(draft.Platform, CancellationToken.None);
        }
        catch (Exception e) { error = Friendly(e); Store.Diagnostic("catalog_failed type=" + e.GetType().Name); }
        Safe(() =>
        {
            if (!Instances.TryGetValue(id, out var state) || !state.Configuring || state.Draft.Key != draft.Key) return;
            state.DraftResources = resources ?? []; state.Message = error ?? "模组列表已更新，请选择并保存"; Update(state);
        });
    }
    private static void StartRefresh(Instance state)
    {
        if (state.Refreshing) return;
        state.Refreshing = true; state.Message = "正在刷新…";
        var pending = new CancellationTokenSource(TimeSpan.FromSeconds(120));
        state.Pending = pending;
        Update(state);
        _ = Refresh(state.Id, state.Definition, state.Settings, state.Generation, pending);
    }
    private static async Task Refresh(string id, string definition, WidgetSettings settings, int generation, CancellationTokenSource pending)
    {
        var ct = pending.Token;
        WidgetData? result = null; string? message = null;
        try
        {
            var account = Store.Accounts().FirstOrDefault(x => x.Id == settings.AccountId) ?? throw new WidgetApiException("login", "请在主客户端登录后重新配置");
            using var api = new NeteaseApi(account, Store.Diagnostic);
            Resource[] resources = []; Dictionary<string, double?>? overview = null; MetricSeries[] series = [];
            if (definition == "Overview") overview = await api.Overview(ct);
            else
            {
                resources = await api.Resources(settings.Platform, ct);
                // 与数据追踪页面相同：本机日期，截止昨日，不混入实时订单数据。
                var end = DateOnly.FromDateTime(DateTime.Now).AddDays(-1);
                var rows = await api.Days(settings, resources, end, ct);
                series = DayAggregator.Build(settings, resources, rows, end);
            }
            result = new WidgetData(settings.Key, account.Fingerprint, DateTimeOffset.Now, resources, overview, series);
            Store.Diagnostic($"refresh_done series={series.Length} resources={resources.Length}");
        }
        catch (Exception e) { message = Friendly(e); Store.Diagnostic("refresh_failed type=" + e.GetType().Name); }
        Safe(() =>
        {
            if (!Instances.TryGetValue(id, out var state) || state.Generation != generation || state.Settings.Key != settings.Key) return;
            state.Refreshing = false; state.Pending = null;
            var fingerprint = Store.Accounts().FirstOrDefault(x => x.Id == settings.AccountId)?.Fingerprint;
            if (result is not null && result.SessionFingerprint == fingerprint)
            {
                state.Data = result; Store.Cache(id, result); state.Message = null;
            }
            else state.Message = message ?? "登录会话已变化，请再次刷新";
            Update(state);
        });
        pending.Dispose();
    }
    private static string Friendly(Exception e) => e switch
    {
        WidgetApiException known => known.Message,
        InvalidDataException => "统计数据不完整，未更新合计，请重试",
        OperationCanceledException => "请求超时或已取消，点击刷新可重试",
        _ => "刷新失败，保留旧数据；可重试或查看诊断日志"
    };
}
