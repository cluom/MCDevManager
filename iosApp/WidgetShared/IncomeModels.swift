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

struct WidgetState: Codable {
    var accountID: String?
    var revision: UUID?
    var snapshot: IncomeSnapshot?
    // 按账号保存尝试时间；切换账号、重登、失败和进程重启均不能绕过五分钟限频。
    var attempts: [String: Date] = [:]
    var message: String?

    func canRefresh(accountID: String, now: Date) -> Bool {
        guard let last = attempts[accountID] else { return true }
        let elapsed = now.timeIntervalSince(last)
        // 时钟回拨也不提前解除限频。
        return elapsed >= IncomeWidgetConstants.minimumRefreshInterval
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
