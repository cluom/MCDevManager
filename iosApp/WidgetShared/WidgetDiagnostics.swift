import Foundation
import Darwin
import os

// Only fixed enums, numbers and booleans are accepted: never pass URLs, Cookie values,
// account IDs, response bodies, localizedDescription or NSError.userInfo to this log.
enum WidgetDiagnosticEvent: String, Codable {
    case sessionSync, sessionUnchanged, sessionCleared, reserveGranted, reserveSkipped
    case completionSaved, completionDropped, refreshStarted, refreshFinished, refreshFailed
    case requestStarted, requestFinished, requestFailed, sourcesLoaded, cacheRead, exportState
}

enum WidgetDiagnosticReason: String, Codable {
    case noAccount, cooldown, accountChanged, cookiesChanged, accountMismatch, revisionMismatch
    case sharedContainer, credentials, loginExpired, invalidResponse, timeout, network
    case decoding, cancelled, storage, unknown
}

enum WidgetRefreshTrigger: String, Codable { case timeline, manual, snapshot, export }
enum WidgetEndpoint: String, Codable { case resources, lobbyResources, sales, lobbySales }
enum WidgetDecodingFailure: String, Codable { case missingField, typeMismatch, missingValue, corruptData }

struct WidgetDiagnosticDetails: Codable {
    var trigger: WidgetRefreshTrigger?
    var reason: WidgetDiagnosticReason?
    var endpoint: WidgetEndpoint?
    var hasAccount: Bool?
    var hasSnapshot: Bool?
    var accountMatches: Bool?
    var revisionMatches: Bool?
    var remainingSeconds: Int?
    var elapsedMs: Int?
    var httpStatus: Int?
    var responseBytes: Int?
    var sourceCount: Int?
    var jobCount: Int?
    var errorCode: Int?
    var outcome: WidgetRefreshOutcome?
    var requestID: UUID?
    var decodingFailure: WidgetDecodingFailure?
    var expectedCount: Int?
    var actualCount: Int?

    static func failure(_ error: Error) -> WidgetDiagnosticDetails {
        var details = WidgetDiagnosticDetails()
        if let error = error as? IncomeWidgetError {
            switch error {
            case .sharedContainer: details.reason = .sharedContainer
            case .credentials: details.reason = .credentials
            case .loginExpired: details.reason = .loginExpired
            case .invalidResponse: details.reason = .invalidResponse
            case .timeout: details.reason = .timeout
            }
        } else if let error = error as? URLError {
            details.reason = error.code == .timedOut ? .timeout : (error.code == .cancelled ? .cancelled : .network)
            details.errorCode = error.errorCode
        } else if let error = error as? DecodingError {
            details.reason = .decoding
            switch error {
            case .keyNotFound: details.decodingFailure = .missingField
            case .typeMismatch: details.decodingFailure = .typeMismatch
            case .valueNotFound: details.decodingFailure = .missingValue
            case .dataCorrupted: details.decodingFailure = .corruptData
            @unknown default: break
            }
        } else if error is CancellationError {
            details.reason = .cancelled
        } else {
            // Only a numeric code is safe; error descriptions can contain credential-bearing URLs.
            details.reason = .unknown
            details.errorCode = (error as NSError).code
        }
        return details
    }
}

final class WidgetDiagnostics: @unchecked Sendable {
    static let maximumBytes = 256 * 1024
    static let maximumLines = 512
    private let directory: URL
    private let logger = Logger(subsystem: "com.lemon.mcdevmanagermp", category: "IncomeWidget")

    init(directory: URL) { self.directory = directory }

    static func line(_ event: WidgetDiagnosticEvent, _ details: WidgetDiagnosticDetails = .init(),
                     now: Date = Date()) -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let json = (try? encoder.encode(details)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        let timestamp = ISO8601DateFormatter().string(from: now)
        let process = Bundle.main.bundleURL.pathExtension == "appex" ? "widget" : "app"
        return "\(timestamp) [DEBUG] WIDGET_DIAG v=1 process=\(process) event=\(event.rawValue) \(json)"
    }

    func record(_ event: WidgetDiagnosticEvent, _ details: WidgetDiagnosticDetails = .init()) {
        let line = Self.line(event, details)
        logger.info("\(line, privacy: .public)")
        do {
            try locked {
                var lines = try readLines()
                lines.append(line)
                lines = Array(lines.suffix(Self.maximumLines))
                var size = lines.reduce(0) { $0 + $1.utf8.count + 1 }
                while size > Self.maximumBytes, !lines.isEmpty {
                    size -= lines.removeFirst().utf8.count + 1
                }
                let data = Data((lines.joined(separator: "\n") + "\n").utf8)
                #if os(iOS)
                try data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                #else
                try data.write(to: file, options: .atomic)
                #endif
            }
        } catch {
            // Logging must never turn a successful refresh into a failed one.
            logger.warning("Widget diagnostic file unavailable")
        }
    }

    func export() throws -> String { try locked { try readLines().joined(separator: "\n") } }

    func clear() throws {
        try locked {
            if FileManager.default.fileExists(atPath: file.path) {
                try FileManager.default.removeItem(at: file)
            }
        }
    }

    private var file: URL { directory.appendingPathComponent("diagnostics.log") }

    private func readLines() throws -> [String] {
        guard FileManager.default.fileExists(atPath: file.path) else { return [] }
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        let size = try handle.seekToEnd()
        let offset = size > UInt64(Self.maximumBytes) ? size - UInt64(Self.maximumBytes) : 0
        try handle.seek(toOffset: offset)
        let data = try handle.read(upToCount: Self.maximumBytes) ?? Data()
        var lines = String(decoding: data, as: UTF8.self).split(separator: "\n").map(String.init)
        if offset > 0, !lines.isEmpty { lines.removeFirst() }
        return Array(lines.suffix(Self.maximumLines))
    }

    private func locked<T>(_ body: () throws -> T) throws -> T {
        let fd = open(directory.appendingPathComponent("diagnostics.lock").path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw IncomeWidgetError.sharedContainer }
        defer { close(fd) }
        guard flock(fd, LOCK_EX) == 0 else { throw IncomeWidgetError.sharedContainer }
        defer { flock(fd, LOCK_UN) }
        return try body()
    }
}
