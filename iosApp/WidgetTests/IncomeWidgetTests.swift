import Foundation
import XCTest
@testable import IncomeWidgetCore

private final class MemoryVault: WidgetCredentialVault {
    var value: WidgetCredential?
    func read() throws -> WidgetCredential? { value }
    func write(_ value: WidgetCredential) throws { self.value = value }
    func delete() throws { value = nil }
}

// 本地模拟实时收益页面用到的接口；禁止测试携带凭据访问实际网络。
private final class RealtimeIncomeURLProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var captured: [URLRequest] = []

    static func reset() { lock.lock(); defer { lock.unlock() }; captured = [] }
    static func requests() -> [URLRequest] { lock.lock(); defer { lock.unlock() }; return captured }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        Self.lock.lock()
        Self.captured.append(request)
        Self.lock.unlock()
        let url = request.url!
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        let begin = components.queryItems?.first { $0.name == "begin_time" }?.value
        let today = begin == "2026-09-26T16:00:00.000Z"
        let status: Int
        let body: String
        switch components.percentEncodedPath {
        case "/items/categories/pe/":
            status = 200
            body = #"{"status":"ok","data":{"count":1,"item":[{"item_id":"123","online_time":"2026-08-01"}]}}"#
        case "/goods/pe/summary":
            status = 200
            body = #"{"status":"ok","data":{"count":1,"items":[{"item_id":"123"}]}}"#
        case "/items/categories/pe/123/incomes/":
            status = 200
            body = "{\"status\":\"ok\",\"data\":{\"total_diamonds\":\(today ? 12 : 7),\"total_points\":\(today ? 3 : 2)}}"
        case "/items/categories/pe/123/lobby_incomes/":
            status = 200
            body = "{\"status\":\"ok\",\"data\":{\"total_diamonds\":\(today ? 5 : 4),\"total_points\":\(today ? 1 : 0)}}"
        default:
            // 复现日志中的缺尾斜杠响应；旧路径必须让端到端测试失败。
            status = 308
            body = "<html>Permanent Redirect</html>"
        }
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

final class IncomeWidgetTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    private func fixture() throws -> (WidgetStore, URL, MemoryVault) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let vault = MemoryVault()
        let store = try WidgetStore(directory: dir, vault: vault)
        addTeardownBlock { try FileManager.default.removeItem(at: dir) }
        return (store, dir, vault)
    }

    private func login(_ store: WidgetStore, id: String = "7", value: String = "abc%2Bdef==") throws {
        let json = String(data: try JSONEncoder().encode(["S_INFO": value]), encoding: .utf8)!
        _ = try store.synchronize(accountID: id, cookiesJSON: json)
    }

    private func snapshot(_ date: Date) -> IncomeSnapshot {
        IncomeSnapshot(day: BeijingDay.key(date), today: IncomeTotal(diamonds: 50, points: 3),
                       yesterday: IncomeTotal(diamonds: 40, points: 2), updatedAt: date)
    }

    func testBeijingDayBoundaries() {
        let f = ISO8601DateFormatter()
        let date = f.date(from: "2026-09-25T16:30:00Z")!
        XCTAssertEqual(BeijingDay.key(date), "2026-09-26")
        let range = BeijingDay.range(date)
        XCTAssertEqual(range.0, "2026-09-25T16:00:00.000Z")
        XCTAssertEqual(range.1, "2026-09-26T15:59:59.999Z")
    }

    func testFailureAndRestartAllowImmediateRetry() throws {
        let (store, dir, vault) = try fixture()
        try login(store)
        let credential = try XCTUnwrap(store.reserve(now: now))
        try store.complete(reservation: credential, snapshot: nil, message: "网络失败")
        let restarted = try WidgetStore(directory: dir, vault: vault)
        XCTAssertNotNil(try restarted.reserve(now: now.addingTimeInterval(1)))
    }

    func testReloginAllowsImmediateRetry() throws {
        let (store, _, _) = try fixture()
        try login(store)
        _ = try store.reserve(now: now)
        _ = try store.synchronize(accountID: "", cookiesJSON: "")
        try login(store)
        XCTAssertNotNil(try store.reserve(now: now.addingTimeInterval(1)))
    }

    func testSeparateStoresCannotReserveTwice() throws {
        let (store, dir, vault) = try fixture()
        try login(store)
        let second = try WidgetStore(directory: dir, vault: vault)
        let queue = DispatchQueue(label: "widget-test", attributes: .concurrent)
        let lock = NSLock()
        var reservations = 0
        queue.sync {
            DispatchQueue.concurrentPerform(iterations: 20) { index in
                let result = try? (index % 2 == 0 ? store : second).reserve(now: self.now)
                if result != nil { lock.lock(); reservations += 1; lock.unlock() }
            }
        }
        XCTAssertEqual(reservations, 1)
    }

    func testFailedRefreshPreservesWholeSnapshot() throws {
        let (store, _, _) = try fixture()
        try login(store)
        let credential = try XCTUnwrap(store.reserve(now: now))
        try store.complete(reservation: credential, snapshot: snapshot(now), message: nil)
        let retry = try XCTUnwrap(store.reserve(now: now.addingTimeInterval(1)))
        try store.complete(reservation: retry, snapshot: nil, message: "数据未取全")
        XCTAssertEqual(try store.read().snapshot, snapshot(now))
        XCTAssertEqual(try store.read().message, "数据未取全")
    }

    func testAccountSwitchAndLogoutRejectLateResponses() throws {
        let (store, _, vault) = try fixture()
        try login(store)
        let old = try XCTUnwrap(store.reserve(now: now))
        try login(store, id: "8", value: "second")
        try store.complete(reservation: old, snapshot: snapshot(now), message: nil)
        XCTAssertEqual(try store.read().accountID, "8")
        XCTAssertNil(try store.read().snapshot)
        let second = try XCTUnwrap(store.reserve(now: now))
        _ = try store.synchronize(accountID: "", cookiesJSON: "")
        try store.complete(reservation: second, snapshot: snapshot(now), message: nil)
        XCTAssertNil(try store.read().accountID)
        XCTAssertNil(try store.read().snapshot)
        XCTAssertNil(vault.value)
    }

    func testCookieUpdateRejectsLateResponseAndAllowsRetry() throws {
        let (store, _, _) = try fixture()
        try login(store)
        let old = try XCTUnwrap(store.reserve(now: now))
        try login(store, value: "updated")
        try store.complete(reservation: old, snapshot: nil, message: "登录已过期")
        XCTAssertNil(try store.read().message)
        XCTAssertNotNil(try store.reserve(now: now.addingTimeInterval(1)))
    }

    func testCookieRotationPreservesFailureUntilNextSuccessfulRefresh() throws {
        let (store, _, _) = try fixture()
        try login(store)
        let first = try XCTUnwrap(store.reserve(now: now))
        try store.complete(reservation: first, snapshot: nil, message: "数据未取全，保留上次结果")
        try login(store, value: "rotated")
        XCTAssertEqual(try store.read().message, "数据未取全，保留上次结果")
        XCTAssertEqual(try store.read().refreshOutcome, .failed)
        let next = try XCTUnwrap(store.reserve(now: now.addingTimeInterval(1)))
        try store.complete(reservation: next, snapshot: snapshot(now), message: nil)
        XCTAssertNil(try store.read().message)
        XCTAssertEqual(try store.read().snapshot, snapshot(now))
    }

    func testAbandonedRefreshRecoversWithoutAutomaticRetry() throws {
        let (store, _, _) = try fixture()
        try login(store)
        XCTAssertEqual(try store.read().displayStatus(at: now), "尚未取得今日数据，请点刷新")
        _ = try store.reserve(now: now)
        XCTAssertEqual(try store.read().displayStatus(at: now), "正在刷新收益…")
        XCTAssertEqual(try store.read().displayStatus(at: now.addingTimeInterval(25)), "上次刷新未完成，请点刷新")
        XCTAssertFalse(try store.read().canRefreshManually(at: now.addingTimeInterval(24)))
        XCTAssertTrue(try store.read().canRefreshManually(at: now.addingTimeInterval(25)))
        XCTAssertEqual(try store.read().statusTransitionDates(after: now).sorted(),
                       [now.addingTimeInterval(25)])
        XCTAssertTrue(try store.read().statusTransitionDates(after: now.addingTimeInterval(25)).isEmpty)
    }

    func testManualButtonOnlyDisablesWhileRequestIsInFlight() throws {
        let (store, _, _) = try fixture()
        XCTAssertFalse(try store.read().canRefreshManually(at: now))
        try login(store)
        XCTAssertTrue(try store.read().canRefreshManually(at: now))
        let credential = try XCTUnwrap(store.reserve(now: now))
        XCTAssertTrue(try store.read().isRefreshing(at: now))
        XCTAssertFalse(try store.read().canRefreshManually(at: now))
        XCTAssertFalse(try store.read().isRefreshing(at: now.addingTimeInterval(25)))
        try store.complete(reservation: credential, snapshot: snapshot(now), message: nil)
        let state = try store.read()
        XCTAssertFalse(state.isRefreshing(at: now))
        XCTAssertTrue(state.canRefreshManually(at: now))
        XCTAssertEqual(state.displayStatus(at: now), "点击右上角刷新收益")
        XCTAssertTrue(state.canRefreshManually(at: now.addingTimeInterval(-1)))
        XCTAssertTrue(state.statusTransitionDates(after: now).isEmpty)
    }

    func testStartingManualRetryClearsOldErrorButRetainsSnapshot() throws {
        let (store, _, _) = try fixture()
        try login(store)
        let first = try XCTUnwrap(store.reserve(now: now))
        try store.complete(reservation: first, snapshot: snapshot(now), message: nil)
        let retry = try XCTUnwrap(store.reserve(now: now.addingTimeInterval(1)))
        try store.complete(reservation: retry, snapshot: nil, message: "旧错误")
        _ = try store.reserve(now: now.addingTimeInterval(2))
        let state = try store.read()
        XCTAssertNil(state.message)
        XCTAssertEqual(state.snapshot, snapshot(now))
        XCTAssertEqual(state.displayStatus(at: now.addingTimeInterval(2)), "正在刷新收益…")
    }

    func testSystemCacheReadsAndCookieSyncDoNotReserveOrLoad() throws {
        let (store, _, _) = try fixture()
        try login(store)
        for index in 0..<5 {
            try login(store, value: "rotated-\(index)")
            let state = WidgetRefreshService.cached(store: store, trigger: .timeline)
            XCTAssertTrue(state.attempts.isEmpty)
            XCTAssertNil(state.refreshOutcome)
            XCTAssertNil(state.snapshot)
        }
        let log = try store.diagnostics.export()
        XCTAssertTrue(log.contains("event=cacheRead"))
        for event in ["refreshStarted", "reserveGranted", "requestStarted"] {
            XCTAssertFalse(log.contains("event=\(event)"))
        }
    }

    func testManualRefreshPublishesLoadingThenResultAndAllowsImmediateRetry() async throws {
        let (store, _, _) = try fixture()
        try login(store)
        var loads = 0
        var states: [WidgetState] = []
        let result = await WidgetRefreshService.refreshManually(store: store, now: now, load: { credential, date in
            loads += 1
            XCTAssertEqual(credential.accountID, "7")
            XCTAssertEqual(date, self.now)
            XCTAssertEqual(try store.read().refreshOutcome, .inFlight)
            let skipped = await WidgetRefreshService.refreshManually(store: store, now: date, load: { _, _ in
                XCTFail("An in-flight request must not be duplicated")
                throw IncomeWidgetError.invalidResponse
            })
            XCTAssertEqual(skipped.refreshOutcome, .inFlight)
            return self.snapshot(date)
        }, onStateChange: { states.append(WidgetRefreshService.cached(store: store)) })
        XCTAssertEqual(loads, 1)
        XCTAssertEqual(states.map(\.refreshOutcome), [.inFlight, .succeeded])
        XCTAssertEqual(result.snapshot, snapshot(now))
        let retried = await WidgetRefreshService.refreshManually(store: store, now: now, load: { _, date in
            loads += 1
            return self.snapshot(date)
        })
        XCTAssertEqual(loads, 2)
        XCTAssertEqual(retried.snapshot, result.snapshot)
        let log = try store.diagnostics.export()
        XCTAssertTrue(log.contains("\"trigger\":\"manual\""))
        XCTAssertTrue(log.contains("event=reserveSkipped"))
        XCTAssertTrue(log.contains("alreadyInFlight"))
    }

    func testManualFailureStopsLoadingAndPreservesSnapshot() async throws {
        let (store, _, _) = try fixture()
        try login(store)
        let credential = try XCTUnwrap(store.reserve(now: now))
        try store.complete(reservation: credential, snapshot: snapshot(now), message: nil)
        var outcomes: [WidgetRefreshOutcome?] = []
        let result = await WidgetRefreshService.refreshManually(store: store, now: now.addingTimeInterval(1), load: { _, _ in
            throw IncomeWidgetError.timeout
        }, onStateChange: { outcomes.append(WidgetRefreshService.cached(store: store).refreshOutcome) })
        XCTAssertEqual(outcomes, [.inFlight, .failed])
        XCTAssertEqual(result.snapshot, snapshot(now))
        XCTAssertEqual(result.message, IncomeWidgetError.timeout.message)
        XCTAssertFalse(result.isRefreshing(at: now.addingTimeInterval(1)))
        XCTAssertTrue(result.canRefreshManually(at: now.addingTimeInterval(1)))
    }

    func testMissingCredentialsAreVisibleOnCacheOnlyTimeline() async throws {
        let (store, _, vault) = try fixture()
        try login(store)
        vault.value = nil
        var notifications = 0
        _ = await WidgetRefreshService.refreshManually(store: store, now: now, load: { _, _ in
            XCTFail("Missing credentials must not load the network")
            throw IncomeWidgetError.invalidResponse
        }, onStateChange: { notifications += 1 })
        let state = WidgetRefreshService.cached(store: store, trigger: .timeline)
        XCTAssertEqual(state.message, IncomeWidgetError.credentials.message)
        XCTAssertEqual(state.refreshOutcome, .failed)
        XCTAssertTrue(state.attempts.isEmpty)
        XCTAssertEqual(notifications, 1)
    }

    func testDiscardedAttemptHasDiagnosticReasonAndCannotOverwriteNewAttempt() throws {
        let (store, _, _) = try fixture()
        try login(store)
        let old = try XCTUnwrap(store.reserve(now: now))
        try login(store, value: "rotated")
        try store.complete(reservation: old, snapshot: snapshot(now), message: nil)
        XCTAssertEqual(try store.read().refreshOutcome, .discarded)
        XCTAssertNil(try store.read().snapshot)
        XCTAssertEqual(try store.read().displayStatus(at: now), "会话已更新，结果已丢弃，请点刷新")
        let diagnostic = try store.diagnostics.export()
        XCTAssertTrue(diagnostic.contains("event=completionDropped"))
        XCTAssertTrue(diagnostic.contains("revisionMismatch"))
        let current = try XCTUnwrap(store.reserve(now: now.addingTimeInterval(1)))
        try store.complete(reservation: old, snapshot: nil, message: "old-error")
        XCTAssertEqual(try store.read().refreshOutcome, .inFlight)
        try store.complete(reservation: current, snapshot: snapshot(now), message: nil)
        XCTAssertEqual(try store.read().refreshOutcome, .succeeded)
    }

    func testFailuresAndOldStateRemainReadable() throws {
        let legacy = Data(#"{"accountID":"7","attempts":{}}"#.utf8)
        let state = try JSONDecoder().decode(WidgetState.self, from: legacy)
        XCTAssertNil(state.refreshOutcome)
        XCTAssertEqual(state.displayStatus(at: now), "尚未取得今日数据，请点刷新")
        let (store, _, _) = try fixture()
        try login(store)
        let credential = try XCTUnwrap(store.reserve(now: now))
        try store.complete(reservation: credential, snapshot: nil, message: "刷新超时，稍后再试")
        XCTAssertEqual(try store.read().displayStatus(at: now), "刷新超时，稍后再试")
        XCTAssertEqual(try store.read().refreshOutcome, .failed)
    }

    func testOldAttemptCannotOverwriteNewAttemptUsingSameCredentials() throws {
        let (store, _, _) = try fixture()
        try login(store)
        let old = try XCTUnwrap(store.reserve(now: now))
        // 旧进程超时后，新点击取得新占用；Cookie 和账号都没有改变。
        let current = try XCTUnwrap(store.reserve(now: now.addingTimeInterval(25)))
        XCTAssertEqual(old.credential, current.credential)
        XCTAssertNotEqual(old.attemptID, current.attemptID)
        try store.complete(reservation: old, snapshot: snapshot(now), message: nil)
        XCTAssertNil(try store.read().snapshot)
        XCTAssertEqual(try store.read().refreshOutcome, .inFlight)
        try store.complete(reservation: current, snapshot: snapshot(now.addingTimeInterval(25)), message: nil)
        try store.complete(reservation: old, snapshot: nil, message: "旧错误")
        XCTAssertNil(try store.read().message)
        XCTAssertEqual(try store.read().snapshot, snapshot(now.addingTimeInterval(25)))
        XCTAssertTrue(try store.diagnostics.export().contains("attemptMismatch"))
    }

    func testLegacyAttemptTimeDoesNotImposeCooldown() {
        let state = WidgetState(accountID: "7", attempts: ["7": now], refreshOutcome: .failed)
        XCTAssertTrue(state.canRefreshManually(at: now))
        XCTAssertTrue(state.statusTransitionDates(after: now).isEmpty)
    }

    func testDiagnosticExportIsBoundedAndContainsNoSessionValues() throws {
        let (store, dir, vault) = try fixture()
        try login(store, id: "PRIVATE_ACCOUNT_123", value: "SECRET_COOKIE_123")
        for _ in 0..<(WidgetDiagnostics.maximumLines + 10) {
            store.diagnostics.record(.refreshStarted, WidgetDiagnosticDetails(trigger: .manual))
        }
        let reopened = try WidgetStore(directory: dir, vault: vault)
        let text = try reopened.diagnostics.export()
        XCTAssertEqual(text.split(separator: "\n").count, WidgetDiagnostics.maximumLines)
        XCTAssertLessThanOrEqual(text.utf8.count, WidgetDiagnostics.maximumBytes)
        XCTAssertFalse(text.contains("PRIVATE_ACCOUNT_123"))
        XCTAssertFalse(text.contains("SECRET_COOKIE_123"))
        XCTAssertFalse(text.contains("S_INFO"))
        try reopened.diagnostics.clear()
        XCTAssertEqual(try store.diagnostics.export(), "")
        XCTAssertEqual(try store.read().accountID, "PRIVATE_ACCOUNT_123")
        XCTAssertEqual(vault.value?.cookies["S_INFO"], "SECRET_COOKIE_123")
    }

    func testDiagnosticConcurrentWritersDoNotLoseRecords() throws {
        let (store, dir, _) = try fixture()
        let second = WidgetDiagnostics(directory: dir)
        DispatchQueue.concurrentPerform(iterations: 40) { index in
            (index % 2 == 0 ? store.diagnostics : second).record(.requestStarted,
                WidgetDiagnosticDetails(endpoint: .sales, requestID: UUID()))
        }
        XCTAssertEqual(try store.diagnostics.export().split(separator: "\n").count, 40)
    }

    func testDiagnosticErrorsNeverExportRawDescriptions() {
        let privateURL = "https://example.test/?Cookie=SECRET_VALUE"
        let network = URLError(.timedOut, userInfo: [NSLocalizedDescriptionKey: privateURL])
        let details = WidgetDiagnosticDetails.failure(network)
        XCTAssertEqual(details.reason, .timeout)
        XCTAssertEqual(details.errorCode, URLError.timedOut.rawValue)
        let line = WidgetDiagnostics.line(.requestFailed, details)
        XCTAssertFalse(line.contains("SECRET_VALUE"))
        XCTAssertFalse(line.contains("https"))
        let decoding = DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: privateURL))
        let decodingDetails = WidgetDiagnosticDetails.failure(decoding)
        XCTAssertEqual(decodingDetails.decodingFailure, .corruptData)
        XCTAssertFalse(WidgetDiagnostics.line(.requestFailed, decodingDetails).contains("SECRET_VALUE"))
    }

    func testCookieWireValueIsUnchangedAndNotInStateFile() throws {
        let (store, dir, _) = try fixture()
        try login(store)
        let credential = try XCTUnwrap(store.reserve(now: now))
        XCTAssertEqual(credential.credential.cookieHeader, "S_INFO=abc%2Bdef==")
        let content = try String(contentsOf: dir.appendingPathComponent("state.json"), encoding: .utf8)
        XCTAssertFalse(content.contains("S_INFO"))
        XCTAssertFalse(content.contains("abc%2Bdef"))
        XCTAssertThrowsError(try WidgetCredential.validate(["bad\r\n": "token"]))
        XCTAssertThrowsError(try WidgetCredential.validate(["S_INFO": "token\r\nOther: bad"]))
    }

    func testMidnightDoesNotMislabelOldMoneyAsToday() {
        let cached = snapshot(now)
        let tomorrow = BeijingDay.start(now).addingTimeInterval(86400)
        XCTAssertNil(cached.total(for: BeijingDay.key(tomorrow)))
        XCTAssertEqual(cached.total(for: BeijingDay.key(now))?.diamonds, 50)
        XCTAssertNil(cached.total(for: BeijingDay.key(now.addingTimeInterval(-172800))))
    }

    func testAPIRequiresCompleteTotalsAndHandlesExpiredStatus() throws {
        let good = Data(#"{"status":"ok","data":{"total_diamonds":12,"total_points":3}}"#.utf8)
        let total: RealtimeTotal = try IncomeAPI.decode(good)
        XCTAssertEqual(total.total_diamonds, 12)
        for json in [#"{"status":"ok","data":{}}"#, #"{"status":"ok","data":null}"#,
                     #"{"status":"error","data":{"total_diamonds":0,"total_points":0}}"#] {
            XCTAssertThrowsError(try IncomeAPI.decode(Data(json.utf8)) as RealtimeTotal)
        }
        XCTAssertThrowsError(try IncomeAPI.decode(Data(#"{"status":"no_login","data":"login"}"#.utf8)) as RealtimeTotal) { error in
            guard case IncomeWidgetError.loginExpired = error else { return XCTFail("Expected loginExpired") }
        }
    }

    func testRealtimePageEndpointsLoadBothDaysWithoutRedirects() async throws {
        RealtimeIncomeURLProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RealtimeIncomeURLProtocol.self]
        let api = IncomeAPI(configuration: configuration)
        let date = ISO8601DateFormatter().date(from: "2026-09-26T18:00:00Z")!
        let credential = WidgetCredential(accountID: "test-account", revision: UUID(), cookies: ["S_INFO": "abc%2Bdef=="])
        let result = try await api.load(credential: credential, now: date)
        XCTAssertEqual(result.day, "2026-09-27")
        XCTAssertEqual(result.today, IncomeTotal(diamonds: 17, points: 4))
        XCTAssertEqual(result.yesterday, IncomeTotal(diamonds: 11, points: 2))
        let requests = RealtimeIncomeURLProtocol.requests()
        XCTAssertEqual(requests.count, 6) // 两个列表 + 两类收益各查两天。
        let paths = requests.map { URLComponents(url: $0.url!, resolvingAgainstBaseURL: false)!.percentEncodedPath }
        XCTAssertEqual(paths.filter { $0 == "/items/categories/pe/" }.count, 1)
        XCTAssertEqual(paths.filter { $0 == "/goods/pe/summary" }.count, 1)
        XCTAssertEqual(paths.filter { $0 == "/items/categories/pe/123/incomes/" }.count, 2)
        XCTAssertEqual(paths.filter { $0 == "/items/categories/pe/123/lobby_incomes/" }.count, 2)
        for request in requests {
            XCTAssertEqual(request.httpMethod, "GET")
            XCTAssertEqual(request.url?.scheme, "https")
            XCTAssertEqual(request.url?.host, "mc-launcher.webapp.163.com")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Cookie"), "S_INFO=abc%2Bdef==")
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            let parameters = Dictionary(uniqueKeysWithValues: query.map { ($0.name, $0.value!) })
            if parameters["begin_time"] != nil {
                let begin = parameters["begin_time"]!
                XCTAssertTrue(["2026-09-26T16:00:00.000Z", "2026-09-25T16:00:00.000Z"].contains(begin))
                XCTAssertEqual(parameters["end_time"], begin == "2026-09-26T16:00:00.000Z"
                    ? "2026-09-27T15:59:59.999Z" : "2026-09-26T15:59:59.999Z")
            } else {
                XCTAssertEqual(parameters, ["start": "0", "span": "2147483647"])
            }
        }
    }

    func testResourceSelectionMatchesAppAndDoesNotMergeTwoSources() throws {
        let resources = ResourceList(count: 4, item: [
            ResourceRow(item_id: "1", online_time: "2026-08-01"),
            ResourceRow(item_id: "1", online_time: "2026-08-01"),
            ResourceRow(item_id: "2", online_time: "UNKNOWN"),
            ResourceRow(item_id: "3", online_time: "")])
        let sources = try IncomeAPI.sources(resources: resources, lobby: LobbyList(count: 1, items: [LobbyRow(item_id: "1")]))
        XCTAssertEqual(sources.count, 2)
        XCTAssertEqual(Set(sources.map(\.lobby)), [true, false])
        XCTAssertThrowsError(try IncomeAPI.sources(resources: ResourceList(count: 2, item: []), lobby: LobbyList(count: 0, items: [])))
        XCTAssertThrowsError(try IncomeAPI.sources(resources: ResourceList(count: 0, item: []), lobby: LobbyList(count: 1, items: [LobbyRow(item_id: "../bad")])))
    }

    func testIntegerAggregationAndOverflow() throws {
        var value = IncomeTotal(diamonds: 3_000_000_000, points: 6)
        try value.add(IncomeTotal(diamonds: 5, points: 2))
        XCTAssertEqual(value.diamonds, 3_000_000_005)
        XCTAssertEqual(value.points, 8)
        XCTAssertThrowsError(try value.add(IncomeTotal(diamonds: Int64.max, points: 0)))
    }
}
