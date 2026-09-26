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
            yesterday: IncomeTotal(diamonds: 10620, points: 160), updatedAt: date)))
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

struct IncomeWidgetView: View {
    let entry: IncomeEntry
    @Environment(\.widgetFamily) private var family
    private let accent = Color(red: 0.40, green: 0.28, blue: 0.69)

    private var today: IncomeTotal? { entry.state.snapshot?.total(for: BeijingDay.key(entry.date)) }
    private var yesterday: IncomeTotal? {
        entry.state.snapshot?.total(for: BeijingDay.key(BeijingDay.start(entry.date).addingTimeInterval(-86400)))
    }
    private var status: String? {
        entry.state.displayStatus(at: entry.date)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 10) {
            HStack {
                Image(systemName: "diamond.fill").foregroundStyle(accent)
                Text("PE 收益").font(.caption.weight(.semibold))
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
            if family == .systemMedium {
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
            if let status {
                Text(status).font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(2)
            }
            if let updated = entry.state.snapshot?.updatedAt {
                Text("\(updated.formatted(.dateTime.month().day().hour().minute())) 更新 · 钻石")
                    .font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.7)
            } else {
                Text("钻石流水 · 北京时间").font(.system(size: 10)).foregroundStyle(.secondary)
            }
        }
        .privacySensitive()
        .containerBackground(for: .widget) {
            Color(.secondarySystemGroupedBackground)
        }
    }

    private func amount(_ title: String, total: IncomeTotal?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(total.map { $0.diamonds.formatted() } ?? "—")
                .font(.system(size: 27, weight: .bold, design: .rounded))
                .foregroundStyle(accent).lineLimit(1).minimumScaleFactor(0.6).invalidatableContent()
            Text(total.map { "积分 \($0.points.formatted())" } ?? "积分 —")
                .font(.system(size: 10)).foregroundStyle(.secondary)
        }
    }

    private func compactAmount(_ title: String, total: IncomeTotal?) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Spacer(minLength: 3)
            Text(total.map { $0.diamonds.formatted() } ?? "—")
                .font(.system(size: 23, weight: .bold, design: .rounded))
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
        .description("手动刷新当前账号的今日与昨日 PE 钻石流水，含作品销售与联机大厅内购；没有刷新冷却。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
