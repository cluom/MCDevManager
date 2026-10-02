import SwiftUI
import WidgetKit
import AppIntents

struct IncomeEntry: TimelineEntry {
    let date: Date
    let state: WidgetState

    static var preview: IncomeEntry {
        let date = Date()
        return IncomeEntry(date: date, state: WidgetState(accountID: "preview", snapshot: IncomeSnapshot(
            day: BeijingDay.key(date), today: IncomeTotal(diamonds: 12880, points: 320),
            yesterday: IncomeTotal(diamonds: 10620, points: 160), updatedAt: date, todayDetails: [
                IncomeDetail(itemID: "1", name: "修仙百艺", total: IncomeTotal(diamonds: 7200, points: 200)),
                IncomeDetail(itemID: "2", name: "修仙百艺·炼器附属", total: IncomeTotal(diamonds: 3880, points: 120)),
                IncomeDetail(itemID: "3", name: "更多准心", total: IncomeTotal(diamonds: 1800))
            ])))
    }
}

struct IncomeProvider: TimelineProvider {
    func placeholder(in context: Context) -> IncomeEntry { .preview }

    func getSnapshot(in context: Context, completion: @escaping (IncomeEntry) -> Void) {
        // 编辑/预览不访问网络。
        completion(context.isPreview ? .preview : IncomeEntry(date: Date(), state: WidgetRefreshService.cached()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<IncomeEntry>) -> Void) {
        // 系统调度、回到桌面和 Cookie 同步均只读缓存；网络入口仅在手动 Intent。
        let state = WidgetRefreshService.cached(trigger: .timeline)
        let now = Date()
        let midnight = BeijingDay.start(now).addingTimeInterval(86400)
        let dates = Set([now, midnight, midnight.addingTimeInterval(86400)] + state.statusTransitionDates(after: now))
        let entries = dates.sorted().map { IncomeEntry(date: $0, state: state) }
        // 午夜只纠正日期归属，不发请求，也不沿用昨日金额冒充今日。
        completion(Timeline(entries: entries, policy: .after(midnight)))
    }
}

struct RefreshIncomeIntent: AppIntent {
    static var title: LocalizedStringResource = "刷新收益"
    static var description = IntentDescription("手动请求今日和昨日收益；请求结束后可立即再次刷新。")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        _ = await WidgetRefreshService.refreshManually {
            WidgetCenter.shared.reloadTimelines(ofKind: IncomeWidgetConstants.kind)
        }
        // 开始与结束均重读缓存；返回后系统也会重载，但不会额外联网。
        return .result()
    }
}

// Toggle 的乐观状态能在 Intent 返回前响应点击；仅表示本次请求是否正在进行。
// 外观仍是刷新按钮。WidgetKit 动画最长两秒，转一圈后用状态文案反馈等待。
private struct RefreshIncomeToggleStyle: ToggleStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.isLuminanceReduced) private var luminanceReduced

    func makeBody(configuration: Configuration) -> some View {
        Button { configuration.isOn.toggle() } label: {
            configuration.label
                .rotationEffect(.degrees(configuration.isOn ? 360 : 0))
                .animation(configuration.isOn && !reduceMotion && !luminanceReduced
                           ? .linear(duration: 0.8) : nil, value: configuration.isOn)
                .frame(width: 26, height: 26)
        }
        .buttonStyle(.plain)
        .disabled(configuration.isOn)
        .accessibilityValue(configuration.isOn ? "正在刷新" : "等待刷新")
    }
}

struct ChangeIncomePageIntent: AppIntent {
    static var title: LocalizedStringResource = "翻页查看收益"
    static var description = IntentDescription("切换总览与今日作品收益明细，仅使用缓存，不发起网络请求。")
    static var openAppWhenRun = false

    @Parameter(title: "页码") var page: Int
    @Parameter(title: "尺寸") var layout: String
    @Parameter(title: "账号") var accountID: String

    init() { page = 0; layout = IncomeWidgetLayout.medium.rawValue; accountID = "" }
    init(page: Int, layout: IncomeWidgetLayout, accountID: String) {
        self.page = page
        self.layout = layout.rawValue
        self.accountID = accountID
    }

    func perform() async throws -> some IntentResult {
        guard let layout = IncomeWidgetLayout(rawValue: layout) else { return .result() }
        try WidgetStore.live().setPage(page, layout: layout, expectedAccountID: accountID)
        WidgetCenter.shared.reloadTimelines(ofKind: IncomeWidgetConstants.kind)
        return .result()
    }
}

struct IncomeWidgetView: View {
    let entry: IncomeEntry
    @Environment(\.widgetFamily) private var family
    private let accent = Color(red: 0.40, green: 0.28, blue: 0.69)
    private var layout: IncomeWidgetLayout { family == .systemSmall ? .small : .medium }
    private var details: [IncomeDetail]? { entry.state.snapshot?.details(for: BeijingDay.key(entry.date)) }
    private var page: Int { entry.state.page(for: layout, at: entry.date) }
    private var pageCount: Int { layout.pageCount(detailCount: details?.count ?? 0) }

    private var today: IncomeTotal? { entry.state.snapshot?.total(for: BeijingDay.key(entry.date)) }
    private var yesterday: IncomeTotal? {
        entry.state.snapshot?.total(for: BeijingDay.key(BeijingDay.start(entry.date).addingTimeInterval(-86400)))
    }
    private var status: String? {
        entry.state.displayStatus(at: entry.date)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: "diamond.fill").foregroundStyle(accent)
                Text(page == 0 ? "PE 收益" : "今日收益明细").font(.caption.weight(.semibold)).lineLimit(1)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 2)
                Toggle(isOn: entry.state.isRefreshing(at: entry.date), intent: RefreshIncomeIntent()) {
                    Image(systemName: "arrow.clockwise").font(.caption.weight(.semibold))
                }
                .toggleStyle(RefreshIncomeToggleStyle())
                .disabled(!entry.state.canRefreshManually(at: entry.date))
                .opacity(entry.state.canRefreshManually(at: entry.date) || entry.state.isRefreshing(at: entry.date) ? 1 : 0.4)
                .tint(accent)
                .accessibilityLabel("刷新收益")
                .accessibilityHint(status ?? "点击请求最新收益")
            }
            if page > 0 {
                detailPage
            } else if family == .systemMedium {
                HStack(alignment: .top, spacing: 20) {
                    amount("今日", total: today)
                    Spacer(minLength: 0)
                    amount("昨日", total: yesterday)
                    Spacer(minLength: 0)
                }
            } else {
                compactAmount("今日", total: today)
                compactAmount("昨日", total: yesterday)
            }
            Spacer(minLength: 0)
            if let status, status != "点击右上角刷新收益" {
                Text(status).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.7)
            } else if let updated = entry.state.snapshot?.updatedAt {
                Text("\(updated.formatted(.dateTime.month().day().hour().minute())) 更新 · 钻石")
                    .font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.7)
            } else {
                Text("钻石流水 · 北京时间").font(.system(size: 10)).foregroundStyle(.secondary)
            }
            pageControls
        }
        .privacySensitive()
        .containerBackground(for: .widget) {
            Color(.secondarySystemGroupedBackground)
        }
    }

    @ViewBuilder
    private var detailPage: some View {
        if let details {
            if details.isEmpty {
                Text("今日暂无收益明细").font(.caption).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
            } else {
                VStack(alignment: .leading, spacing: layout == .small ? 5 : 3) {
                    ForEach(layout.details(on: page, from: details)) { detail in
                        if layout == .small {
                            VStack(alignment: .leading, spacing: 0) {
                                Text(detail.name).font(.system(size: 10)).lineLimit(1)
                                detailAmount(detail.total)
                            }
                        } else {
                            HStack(spacing: 8) {
                                Text(detail.name).font(.system(size: 12)).lineLimit(1)
                                Spacer(minLength: 0)
                                detailAmount(detail.total)
                            }
                        }
                    }
                }
            }
        } else {
            Text("请点右上角刷新\n取得今日作品明细").font(.system(size: 11)).foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, minHeight: 50, alignment: .leading)
        }
    }

    private func detailAmount(_ total: IncomeTotal) -> some View {
        HStack(spacing: 3) {
            Image(systemName: "diamond.fill").foregroundStyle(accent)
            Text(total.diamonds.formatted()).foregroundStyle(accent).fontWeight(.semibold)
            if total.points != 0 {
                Text("· 绿宝石 \(total.points.formatted())").foregroundStyle(.secondary)
            }
        }
        .font(.system(size: 10)).lineLimit(1).minimumScaleFactor(0.7).invalidatableContent()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("钻石 \(total.diamonds)，绿宝石 \(total.points)")
    }

    private var pageControls: some View {
        HStack(spacing: 2) {
            pageButton(symbol: "chevron.left", target: (page + pageCount - 1) % pageCount, label: "上一页")
            Spacer(minLength: 0)
            Text(page == 0 ? "总览 · 1/\(pageCount)" : "明细 · \(page + 1)/\(pageCount)")
                .font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                .accessibilityLabel("第 \(page + 1) 页，共 \(pageCount) 页")
            Spacer(minLength: 0)
            pageButton(symbol: "chevron.right", target: (page + 1) % pageCount, label: "下一页")
        }
    }

    private func pageButton(symbol: String, target: Int, label: String) -> some View {
        Button(intent: ChangeIncomePageIntent(page: target, layout: layout, accountID: entry.state.accountID ?? "")) {
            Image(systemName: symbol).font(.system(size: 10, weight: .semibold))
                .frame(width: 28, height: 22).contentShape(Rectangle())
        }
        .buttonStyle(.plain).tint(accent).accessibilityLabel(label)
        .accessibilityHint("只切换缓存页面，不刷新收益")
    }

    private func amount(_ title: String, total: IncomeTotal?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(total.map { $0.diamonds.formatted() } ?? "—")
                .font(.system(size: 27, weight: .bold, design: .rounded))
                .foregroundStyle(accent).lineLimit(1).minimumScaleFactor(0.6).invalidatableContent()
            Text(total.map { "绿宝石 \($0.points.formatted())" } ?? "绿宝石 —")
                .font(.system(size: 10)).foregroundStyle(.secondary)
        }
    }

    private func compactAmount(_ title: String, total: IncomeTotal?) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 3)
            Text(total.map { $0.diamonds.formatted() } ?? "—")
                .font(.system(size: 22, weight: .bold, design: .rounded))
                .foregroundStyle(accent).lineLimit(1).minimumScaleFactor(0.55).invalidatableContent()
        }
    }
}

@main
struct MCDevIncomeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: IncomeWidgetConstants.kind, provider: IncomeProvider()) { entry in
            IncomeWidgetView(entry: entry)
        }
        .configurationDisplayName("今日与昨日收益")
        .description("查看今日、昨日 PE 收益与分页作品明细，含钻石和绿宝石；翻页使用缓存，右上角手动刷新。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
