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
        // 编辑/预览不访问网络，不消耗账号限频。
        completion(context.isPreview ? .preview : IncomeEntry(date: Date(), state: WidgetRefreshService.cached()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<IncomeEntry>) -> Void) {
        Task {
            let state = await WidgetRefreshService.refresh()
            let now = Date()
            let midnight = BeijingDay.start(now).addingTimeInterval(86400)
            // 午夜即使系统延迟联网，也先纠正今日/昨日标签，不沿用昨日金额冒充今日。
            let entries = [IncomeEntry(date: now, state: state), IncomeEntry(date: midnight, state: state),
                           IncomeEntry(date: midnight.addingTimeInterval(86400), state: state)]
            // 仅向系统提出下次刷新建议，实际时间由 WidgetKit 决定，并非后台定时器。
            completion(Timeline(entries: entries, policy: .after(min(now.addingTimeInterval(900), midnight))))
        }
    }
}

struct RefreshIncomeIntent: AppIntent {
    static var title: LocalizedStringResource = "刷新收益"
    static var description = IntentDescription("刷新今日和昨日收益，五分钟内使用缓存。")
    static var openAppWhenRun = false

    func perform() async throws -> some IntentResult {
        _ = await WidgetRefreshService.refresh()
        // 返回后系统会重新获取 timeline；同一个持久化限频器会拦截重复请求。
        return .result()
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
        if let message = entry.state.message { return message }
        if entry.state.accountID == nil { return "请先打开 App 登录" }
        if today == nil { return "等待刷新 · 五分钟内不重复请求" }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: family == .systemSmall ? 6 : 10) {
            HStack {
                Image(systemName: "diamond.fill").foregroundStyle(accent)
                Text("PE 收益").font(.caption.weight(.semibold))
                Spacer(minLength: 2)
                Button(intent: RefreshIncomeIntent()) {
                    Image(systemName: "arrow.clockwise").font(.caption.weight(.semibold))
                        .frame(width: 26, height: 26)
                }
                .buttonStyle(.plain)
                .tint(accent)
                .accessibilityLabel("刷新收益，五分钟内不重复请求")
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
        .description("当前登录账号的 PE 钻石流水，含作品销售与联机大厅内购。五分钟内不重复请求。")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}
