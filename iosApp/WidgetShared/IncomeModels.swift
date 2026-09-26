import Foundation

enum IncomeWidgetConstants {
    static let kind = "MCDevIncomeWidget"
    static let minimumRefreshInterval: TimeInterval = 300
    static let appGroup = "group.com.lemon.mcdevmanagermp.income"
}

enum IncomeWidgetError: Error {
    case sharedContainer, credentials, loginExpired, invalidResponse, timeout

    var message: String {
        switch self {
        case .sharedContainer: return "共享配置不可用，请检查签名"
        case .credentials: return "请打开 App 同步登录状态"
        case .loginExpired: return "登录已过期，请打开 App"
        case .invalidResponse: return "数据未取全，保留上次结果"
        case .timeout: return "刷新超时，稍后再试"
        }
    }
}

struct WidgetCredential: Codable, Equatable {
    let accountID: String
    let revision: UUID
    let cookies: [String: String]

    var cookieHeader: String {
        cookies.keys.sorted().map { "\($0)=\(cookies[$0]!)" }.joined(separator: "; ")
    }

    static func validate(_ cookies: [String: String]) throws {
        let token = CharacterSet(charactersIn: "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        guard !cookies.isEmpty, cookies.allSatisfy({ name, value in
            !name.isEmpty && name.unicodeScalars.allSatisfy(token.contains) &&
            !value.contains(";") && !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) })
        }) else { throw IncomeWidgetError.credentials }
    }
}

struct IncomeTotal: Codable, Equatable {
    var diamonds: Int64 = 0
    var points: Int64 = 0

    mutating func add(_ other: IncomeTotal) throws {
        let d = diamonds.addingReportingOverflow(other.diamonds)
        let p = points.addingReportingOverflow(other.points)
        guard !d.overflow, !p.overflow else { throw IncomeWidgetError.invalidResponse }
        diamonds = d.partialValue
        points = p.partialValue
    }
}

struct IncomeSnapshot: Codable, Equatable {
    let day: String
    let today: IncomeTotal
    let yesterday: IncomeTotal
    let updatedAt: Date

    func total(for dayKey: String) -> IncomeTotal? {
        if dayKey == day { return today }
        if dayKey == BeijingDay.key(BeijingDay.start(updatedAt).addingTimeInterval(-86400)) { return yesterday }
        return nil
    }
}

enum WidgetRefreshOutcome: String, Codable { case inFlight, succeeded, failed, discarded }

struct WidgetState: Codable {
    var accountID: String?
    var revision: UUID?
    var snapshot: IncomeSnapshot?
    // 按账号保存尝试时间；切换账号、重登、失败和进程重启均不能绕过五分钟限频。
    var attempts: [String: Date] = [:]
    var message: String?
    // Optional fields keep state.json from older installations readable.
    var refreshOutcome: WidgetRefreshOutcome?
    var refreshRevision: UUID?

    func canRefresh(accountID: String, now: Date) -> Bool {
        guard let last = attempts[accountID] else { return true }
        let elapsed = now.timeIntervalSince(last)
        // 时钟回拨也不提前解除限频。
        return elapsed >= IncomeWidgetConstants.minimumRefreshInterval
    }

    func remainingSeconds(at now: Date) -> Int {
        guard let accountID, let last = attempts[accountID] else { return 0 }
        return Int(min(86400, max(0, ceil(IncomeWidgetConstants.minimumRefreshInterval - now.timeIntervalSince(last)))))
    }

    func displayStatus(at now: Date) -> String? {
        if let message { return message }
        guard let accountID else { return "请先打开 App 登录" }
        let remaining = remainingSeconds(at: now)
        let retry = remaining > 0 ? "，约\((remaining + 59) / 60)分钟后可重试" : "，请点刷新"
        if refreshOutcome == .discarded { return "会话已更新，结果已丢弃" + retry }
        if refreshOutcome == .inFlight, let last = attempts[accountID] {
            if now.timeIntervalSince(last) < 25 { return "正在刷新收益…" }
            return "上次刷新未完成" + retry
        }
        if snapshot?.total(for: BeijingDay.key(now)) == nil {
            return remaining > 0 ? "暂无今日数据" + retry : "尚未取得今日数据，请点刷新"
        }
        return nil
    }

    // These entries only update text; they do not start network requests or bypass the gate.
    func statusTransitionDates(after now: Date) -> [Date] {
        guard let accountID, let last = attempts[accountID] else { return [] }
        var dates = [last.addingTimeInterval(IncomeWidgetConstants.minimumRefreshInterval)]
        if refreshOutcome == .inFlight { dates.append(last.addingTimeInterval(25)) }
        return dates.filter { $0 > now }
    }

    func diagnosticDetails(at now: Date = Date()) -> WidgetDiagnosticDetails {
        WidgetDiagnosticDetails(hasAccount: accountID != nil, hasSnapshot: snapshot != nil,
                                remainingSeconds: remainingSeconds(at: now), outcome: refreshOutcome)
    }
}

enum BeijingDay {
    static var calendar: Calendar {
        var result = Calendar(identifier: .gregorian)
        result.timeZone = TimeZone(identifier: "Asia/Shanghai")!
        return result
    }

    static func start(_ date: Date) -> Date { calendar.startOfDay(for: date) }
    static func key(_ date: Date) -> String {
        let f = DateFormatter()
        f.calendar = calendar
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = calendar.timeZone
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: date)
    }

    static func range(_ date: Date) -> (String, String) {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return (f.string(from: start(date)), f.string(from: start(date).addingTimeInterval(86400 - 0.001)))
    }
}
