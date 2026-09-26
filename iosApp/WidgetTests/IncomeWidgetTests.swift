import Foundation
import XCTest
@testable import IncomeWidgetCore

private final class MemoryVault: WidgetCredentialVault {
    var value: WidgetCredential?
    func read() throws -> WidgetCredential? { value }
    func write(_ value: WidgetCredential) throws { self.value = value }
    func delete() throws { value = nil }
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

    func testFiveMinuteGateIncludingFailuresAndRestart() throws {
        let (store, dir, vault) = try fixture()
        try login(store)
        let credential = try XCTUnwrap(store.reserve(now: now))
        try store.complete(credential: credential, snapshot: nil, message: "网络失败")
        let restarted = try WidgetStore(directory: dir, vault: vault)
        XCTAssertNil(try restarted.reserve(now: now.addingTimeInterval(299)))
        XCTAssertNil(try restarted.reserve(now: now.addingTimeInterval(-1000)))
        XCTAssertNotNil(try restarted.reserve(now: now.addingTimeInterval(300)))
    }

    func testReloginDoesNotResetCooldown() throws {
        let (store, _, _) = try fixture()
        try login(store)
        _ = try store.reserve(now: now)
        _ = try store.synchronize(accountID: "", cookiesJSON: "")
        try login(store)
        XCTAssertNil(try store.reserve(now: now.addingTimeInterval(20)))
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
        try store.complete(credential: credential, snapshot: snapshot(now), message: nil)
        try store.complete(credential: credential, snapshot: nil, message: "数据未取全")
        XCTAssertEqual(try store.read().snapshot, snapshot(now))
        XCTAssertEqual(try store.read().message, "数据未取全")
    }

    func testAccountSwitchAndLogoutRejectLateResponses() throws {
        let (store, _, vault) = try fixture()
        try login(store)
        let old = try XCTUnwrap(store.reserve(now: now))
        try login(store, id: "8", value: "second")
        try store.complete(credential: old, snapshot: snapshot(now), message: nil)
        XCTAssertEqual(try store.read().accountID, "8")
        XCTAssertNil(try store.read().snapshot)
        let second = try XCTUnwrap(store.reserve(now: now))
        _ = try store.synchronize(accountID: "", cookiesJSON: "")
        try store.complete(credential: second, snapshot: snapshot(now), message: nil)
        XCTAssertNil(try store.read().accountID)
        XCTAssertNil(try store.read().snapshot)
        XCTAssertNil(vault.value)
    }

    func testCookieUpdateRejectsLateResponseWithoutBypassingCooldown() throws {
        let (store, _, _) = try fixture()
        try login(store)
        let old = try XCTUnwrap(store.reserve(now: now))
        try login(store, value: "updated")
        try store.complete(credential: old, snapshot: nil, message: "登录已过期")
        XCTAssertNil(try store.read().message)
        XCTAssertNil(try store.reserve(now: now.addingTimeInterval(50)))
    }

    func testCookieWireValueIsUnchangedAndNotInStateFile() throws {
        let (store, dir, _) = try fixture()
        try login(store)
        let credential = try XCTUnwrap(store.reserve(now: now))
        XCTAssertEqual(credential.cookieHeader, "S_INFO=abc%2Bdef==")
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
