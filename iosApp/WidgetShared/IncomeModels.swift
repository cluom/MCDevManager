import Foundation

enum IncomeWidgetConstants {
    static let kind = "MCDevIncomeWidget"
    // 网络整批截止 20 秒，额外 5 秒用于进程终止后的占用恢复；不是点击冷却。
    static let inFlightExpiryInterval: TimeInterval = 25
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

struct WidgetRefreshReservation {
    let credential: WidgetCredential
    let attemptID: UUID
}

struct IncomeDetail: Codable, Equatable, Identifiable {
    let itemID: String
    let name: String
    var total: IncomeTotal
    var id: String { itemID }
}

struct IncomeSnapshot: Codable, Equatable {
    let day: String
    let today: IncomeTotal
    let yesterday: IncomeTotal
    let updatedAt: Date
    // nil 表示旧缓存没有明细，空数组表示已查询但今日没有产生收益的作品。
    var todayDetails: [IncomeDetail]? = nil

    func details(for dayKey: String) -> [IncomeDetail]? {
        dayKey == day ? todayDetails : nil
    }

    func total(for dayKey: String) -> IncomeTotal? {
        if dayKey == day { return today }
        if dayKey == BeijingDay.key(BeijingDay.start(updatedAt).addingTimeInterval(-86400)) { return yesterday }
        return nil
    }
}

enum IncomeWidgetLayout: String {
    case small, medium
    var rowsPerPage: Int { self == .small ? 2 : 3 }

    func pageCount(detailCount: Int) -> Int {
        1 + max(1, detailCount / rowsPerPage + (detailCount % rowsPerPage == 0 ? 0 : 1))
    }

    func clamp(_ page: Int, detailCount: Int) -> Int {
        min(max(0, page), pageCount(detailCount: detailCount) - 1)
    }

    func details(on page: Int, from details: [IncomeDetail]) -> [IncomeDetail] {
        let page = clamp(page, detailCount: details.count)
        guard page > 0 else { return [] }
        return Array(details.dropFirst((page - 1) * rowsPerPage).prefix(rowsPerPage))
    }
}

enum WidgetRefreshOutcome: String, Codable { case inFlight, succeeded, failed, discarded }

struct WidgetState: Codable {
    var accountID: String?
    var revision: UUID?
    var snapshot: IncomeSnapshot?
    // 保留旧字段兼容已有 state.json；仅用于识别请求是否仍在进行，不再用于冷却。
    var attempts: [String: Date] = [:]
    var message: String?
    // Optional fields keep state.json from older installations readable.
    var refreshOutcome: WidgetRefreshOutcome?
    var refreshRevision: UUID?
    var refreshAttemptID: UUID?
    // 小号与中号分别记录页码；翻页不改变请求状态或缓存金额。
    var presentationPages: [String: Int]?

    func page(for layout: IncomeWidgetLayout, at date: Date) -> Int {
        let count = snapshot?.details(for: BeijingDay.key(date))?.count ?? 0
        return layout.clamp(presentationPages?[layout.rawValue] ?? 0, detailCount: count)
    }

    func canRefresh(accountID: String, now: Date) -> Bool {
        self.accountID == accountID && !isRefreshing(at: now)
    }

    func canRefreshManually(at now: Date) -> Bool {
        guard let accountID else { return false }
        return canRefresh(accountID: accountID, now: now)
    }

    func isRefreshing(at now: Date) -> Bool {
        guard refreshOutcome == .inFlight, let accountID, let last = attempts[accountID] else { return false }
        let elapsed = now.timeIntervalSince(last)
        // 批量网络超时为 20 秒；进程被系统终止或时钟回拨时不能永久占用按钮。
        return elapsed >= 0 && elapsed < IncomeWidgetConstants.inFlightExpiryInterval
    }

    func displayStatus(at now: Date) -> String? {
        if isRefreshing(at: now) { return "正在刷新收益…" }
        if let message { return message }
        guard accountID != nil else { return "请先打开 App 登录" }
        let retry = "，请点刷新"
        if refreshOutcome == .discarded { return "会话已更新，结果已丢弃" + retry }
        if refreshOutcome == .inFlight {
            return "上次刷新未完成" + retry
        }
        if snapshot?.total(for: BeijingDay.key(now)) == nil {
            return "尚未取得今日数据，请点刷新"
        }
        return "点击右上角刷新收益"
    }

    // 仅在系统终止刷新进程后恢复按钮，不自动重试网络。
    func statusTransitionDates(after now: Date) -> [Date] {
        guard isRefreshing(at: now), let accountID, let last = attempts[accountID] else { return [] }
        return [last.addingTimeInterval(IncomeWidgetConstants.inFlightExpiryInterval)]
    }

    func diagnosticDetails(at now: Date = Date()) -> WidgetDiagnosticDetails {
        WidgetDiagnosticDetails(hasAccount: accountID != nil, hasSnapshot: snapshot != nil, outcome: refreshOutcome)
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
