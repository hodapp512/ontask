// OnTask helper: long-lived process, one JSON object per line on stdin, one per line on stdout.
//
// Request:  {"id": 1, "cmd": "sample"}
// Response: {"id": 1, "ok": true, "result": {...}}  |  {"id": 1, "ok": false, "error": "..."}
// Startup:  {"event": "ready", "protocol": 1, "version": "0.1.0"}
//
// Commands are dispatched by name in `handlers`, so later phases (screenshot, ocr) just add entries.

import AppKit
import ApplicationServices
import CoreGraphics
import Foundation
import IOKit.pwr_mgt

let protocolVersion = 1
let helperVersion = "0.1.0"

// MARK: - State kept up to date by notifications

var screensAsleep = false
var systemAsleep = false

let wsCenter = NSWorkspace.shared.notificationCenter
wsCenter.addObserver(forName: NSWorkspace.screensDidSleepNotification, object: nil, queue: .main) { _ in screensAsleep = true }
wsCenter.addObserver(forName: NSWorkspace.screensDidWakeNotification, object: nil, queue: .main) { _ in screensAsleep = false }
wsCenter.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { _ in systemAsleep = true }
wsCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in systemAsleep = false }

// MARK: - Signals

func idleSeconds() -> Double {
    // ~0 is kCGAnyInputEventType
    CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: ~0)!)
}

func screenLocked() -> Bool {
    guard let dict = CGSessionCopyCurrentDictionary() as? [String: Any] else { return false }
    return (dict["CGSSessionScreenIsLocked"] as? Bool) ?? ((dict["CGSSessionScreenIsLocked"] as? Int) == 1)
}

/// True when something (video playback, a call) is holding off display sleep.
func mediaPlaying() -> Bool {
    var status: Unmanaged<CFDictionary>?
    guard IOPMCopyAssertionsStatus(&status) == kIOReturnSuccess, let dict = status?.takeRetainedValue() as? [String: Any] else {
        return false
    }
    return ((dict["PreventUserIdleDisplaySleep"] as? Int) ?? 0) > 0
}

func accessibilityTrusted() -> Bool { AXIsProcessTrusted() }

func axWindowTitle(pid: pid_t) -> String? {
    guard accessibilityTrusted() else { return nil }
    let app = AXUIElementCreateApplication(pid)
    var window: CFTypeRef?
    guard AXUIElementCopyAttributeValue(app, kAXFocusedWindowAttribute as CFString, &window) == .success, let w = window else { return nil }
    var title: CFTypeRef?
    guard AXUIElementCopyAttributeValue(w as! AXUIElement, kAXTitleAttribute as CFString, &title) == .success else { return nil }
    return title as? String
}

/// Fallback; names of other apps' windows are only visible with Screen Recording permission.
func cgWindowTitle(pid: pid_t) -> String? {
    guard let list = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] else {
        return nil
    }
    for w in list where (w[kCGWindowOwnerPID as String] as? pid_t) == pid && (w[kCGWindowLayer as String] as? Int) == 0 {
        if let name = w[kCGWindowName as String] as? String, !name.isEmpty { return name }
    }
    return nil
}

// MARK: - Browsers (AppleScript; needs one-time Automation permission per browser)

let chromiumLike: Set<String> = [
    "com.google.Chrome", "com.google.Chrome.canary", "com.brave.Browser", "com.microsoft.edgemac",
    "com.vivaldi.Vivaldi", "company.thebrowser.Browser", "org.chromium.Chromium",
]
let safariLike: Set<String> = ["com.apple.Safari", "com.apple.SafariTechnologyPreview"]

var scriptCache: [String: NSAppleScript] = [:]
var browserBackoffUntil: [String: Date] = [:]

struct BrowserInfo {
    var url: String?
    var title: String?
    var error: String?
}

func browserInfo(bundleId: String) -> BrowserInfo? {
    let tab: String
    if chromiumLike.contains(bundleId) { tab = "active tab" }
    else if safariLike.contains(bundleId) { tab = "current tab" }
    else { return nil }
    if let until = browserBackoffUntil[bundleId], until > Date() { return BrowserInfo(error: "backoff") }

    let script: NSAppleScript
    if let cached = scriptCache[bundleId] {
        script = cached
    } else {
        let titleProp = safariLike.contains(bundleId) ? "name" : "title"
        let src = """
        tell application id "\(bundleId)"
            if (count of windows) is 0 then return ""
            set t to \(tab) of front window
            return (URL of t) & linefeed & (\(titleProp) of t)
        end tell
        """
        guard let s = NSAppleScript(source: src) else { return BrowserInfo(error: "script_compile") }
        scriptCache[bundleId] = s
        script = s
    }
    var err: NSDictionary?
    let out = script.executeAndReturnError(&err)
    if let err = err {
        let code = (err[NSAppleScript.errorNumber] as? Int) ?? 0
        // -1743: automation denied. Back off so we do not re-trigger the prompt every tick.
        if code == -1743 { browserBackoffUntil[bundleId] = Date().addingTimeInterval(120) }
        return BrowserInfo(error: "applescript_\(code)")
    }
    let parts = (out.stringValue ?? "").split(separator: "\n", maxSplits: 1, omittingEmptySubsequences: false)
    guard parts.count == 2 else { return BrowserInfo() }
    return BrowserInfo(url: String(parts[0]), title: String(parts[1]))
}

// MARK: - Commands

struct HelperError: Error { let message: String }
typealias Handler = ([String: Any]) -> Result<[String: Any], HelperError>

func sample(_ req: [String: Any]) -> Result<[String: Any], HelperError> {
    let locked = screenLocked()
    var r: [String: Any] = [
        "idleSec": idleSeconds(),
        "locked": locked,
        "asleep": systemAsleep || screensAsleep,
        "mediaPlaying": mediaPlaying(),
        "accessibilityTrusted": accessibilityTrusted(),
    ]
    if let app = NSWorkspace.shared.frontmostApplication {
        r["app"] = app.localizedName ?? NSNull()
        r["bundleId"] = app.bundleIdentifier ?? NSNull()
        r["pid"] = Int(app.processIdentifier)
        var title: String? = nil
        if !locked {
            if let bid = app.bundleIdentifier, let b = browserInfo(bundleId: bid) {
                r["url"] = b.url ?? NSNull()
                if let e = b.error { r["browserError"] = e }
                title = b.title
            }
            if title == nil || title!.isEmpty {
                title = axWindowTitle(pid: app.processIdentifier) ?? cgWindowTitle(pid: app.processIdentifier)
            }
        }
        r["title"] = title ?? NSNull()
    }
    return .success(r)
}

let handlers: [String: Handler] = [
    "ping": { _ in .success(["pong": true]) },
    "sample": sample,
    // Shows the system Accessibility prompt (once) so window titles become available.
    "request_accessibility": { _ in
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        return .success(["trusted": AXIsProcessTrustedWithOptions(opts)])
    },
    // Phase 3 will add: "screenshot", "ocr".
]

func respond(_ obj: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: obj, options: []),
          var line = String(data: data, encoding: .utf8) else { return }
    line += "\n"
    FileHandle.standardOutput.write(line.data(using: .utf8)!)
}

func handle(line: String) {
    guard let data = line.data(using: .utf8),
          let req = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
        respond(["ok": false, "error": "invalid_json"])
        return
    }
    let id: Any = req["id"] ?? NSNull()
    guard let cmd = req["cmd"] as? String, let h = handlers[cmd] else {
        respond(["id": id, "ok": false, "error": "unknown_command"])
        return
    }
    switch h(req) {
    case .success(let result): respond(["id": id, "ok": true, "result": result])
    case .failure(let e): respond(["id": id, "ok": false, "error": e.message])
    }
}

// stdin is read on a background thread; commands run on the main thread (AppKit/AppleScript want it).
Thread.detachNewThread {
    while let line = readLine() {
        if line.isEmpty { continue }
        DispatchQueue.main.async { handle(line: line) }
    }
    exit(0) // parent closed the pipe
}

respond(["event": "ready", "protocol": protocolVersion, "version": helperVersion])
RunLoop.main.run()
