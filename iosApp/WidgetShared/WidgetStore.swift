import Foundation
import Security
import Darwin

protocol WidgetCredentialVault {
    func read() throws -> WidgetCredential?
    func write(_ value: WidgetCredential) throws
    func delete() throws
}

private struct KeychainCredentialVault: WidgetCredentialVault {
    let group: String
    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "MCDevManager.IncomeWidget",
         kSecAttrAccount as String: "active-session",
         kSecAttrAccessGroup as String: group]
    }
    func read() throws -> WidgetCredential? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw IncomeWidgetError.credentials }
        return try JSONDecoder().decode(WidgetCredential.self, from: data)
    }
    func write(_ value: WidgetCredential) throws {
        let attrs: [String: Any] = [
            kSecValueData as String: try JSONEncoder().encode(value),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        let updated = SecItemUpdate(query as CFDictionary, attrs as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw IncomeWidgetError.credentials }
        let status = SecItemAdd(query.merging(attrs) { _, value in value } as CFDictionary, nil)
        guard status == errSecSuccess else { throw IncomeWidgetError.credentials }
    }
    func delete() throws {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw IncomeWidgetError.credentials }
    }
}

final class WidgetStore: @unchecked Sendable {
    private let directory: URL
    private let vault: WidgetCredentialVault
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    let diagnostics: WidgetDiagnostics

    static func live() throws -> WidgetStore {
        let configured = Bundle.main.object(forInfoDictionaryKey: "IncomeWidgetAppGroup") as? String
            ?? IncomeWidgetConstants.appGroup
        // AltStore 在重签主程序及扩展时，把实际授权的分组写入 ALTAppGroups。
        let resigned = Bundle.main.object(forInfoDictionaryKey: "ALTAppGroups") as? [String] ?? []
        let candidates = resigned.filter { $0 == configured || $0.hasPrefix(configured + ".") } + [configured]
        for group in candidates {
            if let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) {
                return try WidgetStore(directory: container.appendingPathComponent("IncomeWidget", isDirectory: true),
                                       vault: KeychainCredentialVault(group: group))
            }
        }
        throw IncomeWidgetError.sharedContainer
    }

    init(directory: URL, vault: WidgetCredentialVault) throws {
        self.directory = directory
        self.vault = vault
        self.diagnostics = WidgetDiagnostics(directory: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var url = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }

    // 独立的文件锁供多个尺寸的小组件、AppIntent 和主程序共同使用；网络期间不持锁。
    private func locked<T>(_ body: () throws -> T) throws -> T {
        let fd = open(directory.appendingPathComponent("state.lock").path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw IncomeWidgetError.sharedContainer }
        defer { close(fd) }
        guard flock(fd, LOCK_EX) == 0 else { throw IncomeWidgetError.sharedContainer }
        defer { flock(fd, LOCK_UN) }
        return try body()
    }
    private func readState() throws -> WidgetState {
        let url = directory.appendingPathComponent("state.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return WidgetState() }
        return try decoder.decode(WidgetState.self, from: Data(contentsOf: url))
    }
    private func save(_ state: WidgetState) throws {
        let data = try encoder.encode(state)
        #if os(iOS)
        try data.write(to: directory.appendingPathComponent("state.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: directory.appendingPathComponent("state.json"), options: .atomic)
        #endif
    }

    func read() throws -> WidgetState { try locked { try readState() } }

    /// 只读缓存并保存展示页码；不读取凭据、不请求网络，也不覆盖并发刷新状态。
    func setPage(_ page: Int, layout: IncomeWidgetLayout, expectedAccountID: String, now: Date = Date()) throws {
        try locked {
            var state = try readState()
            guard (state.accountID ?? "") == expectedAccountID else { return }
            let count = state.snapshot?.details(for: BeijingDay.key(now))?.count ?? 0
            var pages = state.presentationPages ?? [:]
            pages[layout.rawValue] = layout.clamp(page, detailCount: count)
            state.presentationPages = pages
            try save(state)
        }
    }

    /// 返回是否需要让系统重载显示。会话仅单向从主程序导出，扩展从不回写主程序数据库。
    func synchronize(accountID: String, cookiesJSON: String) throws -> Bool {
        try locked {
            var state = try readState()
            let changedAccount = state.accountID != (accountID.isEmpty ? nil : accountID)
            if changedAccount || accountID.isEmpty {
                // 先撤销可读状态，再操作钥匙串；中途失败也不会露出旧账号的数据。
                state.accountID = nil
                state.revision = nil
                state.snapshot = nil
                state.message = "请打开 App 同步登录状态"
                state.refreshOutcome = nil
                state.refreshRevision = nil
                state.refreshAttemptID = nil
                state.presentationPages = nil
                try save(state)
            }
            if accountID.isEmpty {
                try vault.delete()
                diagnostics.record(.sessionCleared, state.diagnosticDetails())
                return changedAccount
            }
            let cookies = try decoder.decode([String: String].self, from: Data(cookiesJSON.utf8))
            try WidgetCredential.validate(cookies)
            let old = try vault.read()
            if !changedAccount, let old, old.accountID == accountID, old.cookies == cookies,
               state.revision == old.revision {
                diagnostics.record(.sessionUnchanged, state.diagnosticDetails())
                return false
            }
            let credential = WidgetCredential(accountID: accountID, revision: UUID(), cookies: cookies)
            try vault.write(credential)
            state.accountID = accountID
            state.revision = credential.revision
            // 正常的 Cookie 轮换不等于刷新成功，不能抹掉上次失败原因。
            // 切换账号才清除旧账号提示；同账号的错误由下一次刷新结果更新。
            if changedAccount { state.message = nil }
            try save(state)
            var details = state.diagnosticDetails()
            details.reason = changedAccount ? .accountChanged : .cookiesChanged
            diagnostics.record(.sessionSync, details)
            return true
        }
    }

    /// 只合并正在进行的请求；成功或失败后可立即重试，没有固定冷却。
    func reserve(now: Date) throws -> WidgetRefreshReservation? {
        try locked {
            var state = try readState()
            guard let accountID = state.accountID, state.canRefresh(accountID: accountID, now: now) else {
                var details = state.diagnosticDetails(at: now)
                details.reason = state.accountID == nil ? .noAccount : .alreadyInFlight
                diagnostics.record(.reserveSkipped, details)
                return nil
            }
            let credential: WidgetCredential
            do {
                guard let stored = try vault.read(), stored.accountID == accountID,
                      stored.revision == state.revision else { throw IncomeWidgetError.credentials }
                credential = stored
            } catch {
                // timeline 只读缓存，因此请求前的凭据错误也须在同一账号锁内持久化。
                state.message = IncomeWidgetError.credentials.message
                state.refreshOutcome = .failed
                state.refreshRevision = state.revision
                state.refreshAttemptID = nil
                try save(state)
                throw IncomeWidgetError.credentials
            }
            state.attempts[accountID] = now
            state.message = nil
            state.refreshOutcome = .inFlight
            state.refreshRevision = credential.revision
            let reservation = WidgetRefreshReservation(credential: credential, attemptID: UUID())
            state.refreshAttemptID = reservation.attemptID
            try save(state)
            diagnostics.record(.reserveGranted, state.diagnosticDetails(at: now))
            return reservation
        }
    }

    func complete(reservation: WidgetRefreshReservation, snapshot: IncomeSnapshot?, message: String?) throws {
        try locked {
            var state = try readState()
            let credential = reservation.credential
            let attemptMatches = state.refreshAttemptID == reservation.attemptID && state.refreshOutcome == .inFlight
            // 旧账号或旧 Cookie 发出的请求晚回来，不能覆盖切换/退出后的状态。
            guard state.accountID == credential.accountID, state.revision == credential.revision, attemptMatches else {
                // Only annotate the rejected attempt, never overwrite a newer attempt/account.
                if state.accountID == credential.accountID, attemptMatches {
                    state.refreshOutcome = .discarded
                    try save(state)
                }
                var details = state.diagnosticDetails()
                details.accountMatches = state.accountID == credential.accountID
                details.revisionMatches = state.revision == credential.revision
                details.reason = details.accountMatches != true ? .accountMismatch
                    : (details.revisionMatches != true ? .revisionMismatch : .attemptMismatch)
                diagnostics.record(.completionDropped, details)
                return
            }
            if let snapshot { state.snapshot = snapshot }
            state.message = message
            state.refreshOutcome = snapshot != nil ? .succeeded : .failed
            try save(state)
            diagnostics.record(.completionSaved, state.diagnosticDetails())
        }
    }
}
