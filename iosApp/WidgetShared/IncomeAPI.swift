import Foundation

// 独立的轻量客户端；不把 Compose/Kotlin 运行时加载进 Widget 扩展。
final class IncomeAPI: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private static let origin = URL(string: "https://mc-launcher.webapp.163.com/")!

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        // Cookie 仅发送给固定 API 主机，禁止重定向到登录页或第三方。
        completionHandler(nil)
    }

    func load(credential: WidgetCredential, now: Date) async throws -> IncomeSnapshot {
        try WidgetCredential.validate(credential.cookies)
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 20
        config.httpMaximumConnectionsPerHost = 4
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        return try await withThrowingTaskGroup(of: IncomeSnapshot.self) { group in
            group.addTask { try await self.fetch(session: session, credential: credential, now: now) }
            group.addTask {
                try await Task.sleep(nanoseconds: 20_000_000_000)
                throw IncomeWidgetError.timeout
            }
            defer { group.cancelAll() }
            guard let result = try await group.next() else { throw IncomeWidgetError.invalidResponse }
            return result
        }
    }

    private func fetch(session: URLSession, credential: WidgetCredential, now: Date) async throws -> IncomeSnapshot {
        let listQuery = [URLQueryItem(name: "start", value: "0"), URLQueryItem(name: "span", value: "2147483647")]
        async let normal: ResourceList = get("items/categories/pe", query: listQuery, session: session, credential: credential)
        async let lobby: LobbyList = get("goods/pe/summary", query: listQuery, session: session, credential: credential)
        let (resources, lobbyResources) = try await (normal, lobby)
        let sources = try Self.sources(resources: resources, lobby: lobbyResources)
        var totals = [IncomeTotal(), IncomeTotal()]
        // 最多四个并发请求，整个刷新有 20 秒截止时间；任一失败不保存残缺总额。
        let jobs = sources.flatMap { source in [0, 1].map { (source, $0) } }
        try await withThrowingTaskGroup(of: (Int, IncomeTotal).self) { group in
            var next = 0
            func enqueue(_ index: Int) {
                let (source, dayOffset) = jobs[index]
                group.addTask {
                    try Task.checkCancellation()
                    let day = BeijingDay.start(now).addingTimeInterval(Double(-dayOffset) * 86400)
                    let (begin, end) = BeijingDay.range(day)
                    let result: RealtimeTotal = try await self.get(source.path, query: [
                        URLQueryItem(name: "begin_time", value: begin),
                        URLQueryItem(name: "end_time", value: end)
                    ], session: session, credential: credential)
                    return (dayOffset, IncomeTotal(diamonds: result.total_diamonds, points: result.total_points))
                }
            }
            while next < min(4, jobs.count) { enqueue(next); next += 1 }
            while let (index, total) = try await group.next() {
                try totals[index].add(total)
                if next < jobs.count { enqueue(next); next += 1 }
            }
        }
        return IncomeSnapshot(day: BeijingDay.key(now), today: totals[0], yesterday: totals[1], updatedAt: now)
    }

    private func get<T: Decodable>(_ path: String, query: [URLQueryItem], session: URLSession,
                                    credential: WidgetCredential) async throws -> T {
        var components = URLComponents(url: Self.origin.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query
        var request = URLRequest(url: components.url!)
        request.setValue(credential.cookieHeader, forHTTPHeaderField: "Cookie")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw IncomeWidgetError.invalidResponse }
        if http.statusCode == 401 { throw IncomeWidgetError.loginExpired }
        guard (200..<300).contains(http.statusCode) else { throw IncomeWidgetError.invalidResponse }
        return try Self.decode(data)
    }

    static func decode<T: Decodable>(_ data: Data) throws -> T {
        let result = try JSONDecoder().decode(Envelope<T>.self, from: data)
        if ["401", "no_login"].contains(result.status) { throw IncomeWidgetError.loginExpired }
        guard ["200", "201", "ok", "OK", "Ok"].contains(result.status), let value = result.data else {
            throw IncomeWidgetError.invalidResponse
        }
        return value
    }

    static func sources(resources: ResourceList, lobby: LobbyList) throws -> [IncomeSource] {
        guard resources.count == resources.item.count, lobby.count == lobby.items.count else {
            throw IncomeWidgetError.invalidResponse
        }
        let normal = resources.item.filter { !($0.online_time ?? "").isEmpty && $0.online_time != "UNKNOWN" }
            .map { IncomeSource(id: $0.item_id, lobby: false) }
        let goods = lobby.items.map { IncomeSource(id: $0.item_id, lobby: true) }
        let sources = normal + goods
        guard sources.allSatisfy({ !$0.id.isEmpty && $0.id.utf8.allSatisfy { (48...57).contains($0) } }) else {
            throw IncomeWidgetError.invalidResponse
        }
        // 同一作品的销售和大厅内购是两个来源；仅对同来源重复行去重。
        return Array(Set(sources)).sorted { $0.path < $1.path }
    }
}

struct IncomeSource: Hashable {
    let id: String
    let lobby: Bool
    var path: String { "items/categories/pe/\(id)/\(lobby ? "lobby_incomes" : "incomes")/" }
}
struct ResourceList: Decodable { let count: Int; let item: [ResourceRow] }
struct ResourceRow: Decodable { let item_id: String; let online_time: String? }
struct LobbyList: Decodable { let count: Int; let items: [LobbyRow] }
struct LobbyRow: Decodable { let item_id: String }
struct RealtimeTotal: Decodable { let total_diamonds: Int64; let total_points: Int64 }

private struct Envelope<T: Decodable>: Decodable {
    let status: String
    let data: T?
    private enum CodingKeys: String, CodingKey { case status, data }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        if let string = try? c.decode(String.self, forKey: .status) { status = string }
        else { status = String(try c.decode(Int.self, forKey: .status)) }
        // 失败响应的 data 可能是字符串；不能让解码错误掩盖 no_login。
        if ["200", "201", "ok", "OK", "Ok"].contains(status) {
            data = try c.decodeIfPresent(T.self, forKey: .data)
        } else { data = nil }
    }
}
