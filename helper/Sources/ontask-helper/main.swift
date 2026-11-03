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
import IOKit.ps
import ScreenCaptureKit
import Vision

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

/**
 Sends the browser's front tab to [url] (OnTask's "blocked" page). A tab that was open before a site was blocked keeps
 its live connection, so the hosts file alone can't stop it; this does. Same Automation permission as reading the URL.
 */
func leaveTab(_ req: [String: Any]) -> Result<[String: Any], HelperError> {
    guard let bid = req["bundleId"] as? String, let url = req["url"] as? String else { return .failure(HelperError(message: "bad_request")) }
    let tab: String
    if chromiumLike.contains(bid) { tab = "active tab" } else if safariLike.contains(bid) { tab = "current tab" } else { return .failure(HelperError(message: "not_a_browser")) }
    let escaped = url.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    let src = "tell application id \"\(bid)\" to if (count of windows) > 0 then set URL of \(tab) of front window to \"\(escaped)\""
    var err: NSDictionary?
    NSAppleScript(source: src)?.executeAndReturnError(&err)
    if let err = err { return .failure(HelperError(message: "applescript_\((err[NSAppleScript.errorNumber] as? Int) ?? 0)")) }
    return .success(["left": true])
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

// MARK: - Permissions
//
// macOS attributes these to the *responsible* process: OnTask.app when installed, the terminal (or IDE) under
// `./gradlew run`. Calling the request APIs is what adds the app to the lists in System Settings.

/// Automation status for one browser without prompting: granted / denied / not_asked / not_running.
func automationStatus(_ bundleId: String, ask: Bool) -> String {
    let target = NSAppleEventDescriptor(bundleIdentifier: bundleId)
    guard let desc = target.aeDesc else { return "unknown" }
    // 'core'/'getd' (get data) is what reading the URL needs; a wildcard event can't prompt.
    let status = AEDeterminePermissionToAutomateTarget(desc, AEEventClass(kCoreEventClass), AEEventID(kAEGetData), ask)
    switch Int(status) {
    case Int(noErr): return "granted"
    case -1743: return "denied" // errAEEventNotPermitted
    case -1744: return "not_asked" // errAEEventWouldRequireUserConsent
    case -600: return "not_running" // procNotFound
    default: return "unknown"
    }
}

func runningBrowsers() -> [NSRunningApplication] {
    NSWorkspace.shared.runningApplications.filter { a in a.bundleIdentifier.map { chromiumLike.contains($0) || safariLike.contains($0) } ?? false }
}

func permissions(_ req: [String: Any]) -> Result<[String: Any], HelperError> {
    let browsers: [[String: Any]] = runningBrowsers().map { a in
        let bid = a.bundleIdentifier ?? ""
        return ["bundleId": bid, "name": a.localizedName ?? bid, "status": automationStatus(bid, ask: false)]
    }
    return .success([
        "accessibility": AXIsProcessTrusted(),
        "screenRecording": CGPreflightScreenCaptureAccess(),
        "automation": browsers,
        "helperBundleId": Bundle.main.bundleIdentifier ?? NSNull(),
        "helperPath": Bundle.main.executablePath ?? NSNull(),
    ])
}

/// Shows the system prompt (first time only) and registers the app in Screen Recording.
func requestScreenRecording(_ req: [String: Any]) -> Result<[String: Any], HelperError> {
    if CGPreflightScreenCaptureAccess() { return .success(["granted": true]) }
    let granted = CGRequestScreenCaptureAccess()
    // Asking ScreenCaptureKit for content also adds the entry on versions where the CG call alone stays quiet.
    if !granted { SCShareableContent.getExcludingDesktopWindows(true, onScreenWindowsOnly: true) { _, _ in } }
    return .success(["granted": granted])
}

let settingsPanes: Set<String> = ["Privacy_Accessibility", "Privacy_ScreenCapture", "Privacy_Automation"]

func openSettings(_ req: [String: Any]) -> Result<[String: Any], HelperError> {
    let pane = (req["pane"] as? String) ?? ""
    guard settingsPanes.contains(pane), let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(pane)") else {
        return .failure(HelperError(message: "unknown_pane"))
    }
    return .success(["opened": NSWorkspace.shared.open(url)])
}

let handlers: [String: Handler] = [
    "ping": { _ in .success(["pong": true]) },
    "battery": battery,
    "screen_capture_status": { _ in .success(["granted": CGPreflightScreenCaptureAccess()]) },
    "request_screen_capture": requestScreenRecording,
    "request_screen_recording": requestScreenRecording,
    "permissions": permissions,
    "open_settings": openSettings,
    "sample": sample,
    "leave_tab": leaveTab,
    // Shows the system Accessibility prompt (once) and adds the app to the Accessibility list.
    "request_accessibility": { _ in
        let opts = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        return .success(["trusted": AXIsProcessTrustedWithOptions(opts)])
    },
]

// MARK: - Screen capture + OCR (phase 3). Needs Screen Recording permission.

let respondLock = NSLock()
typealias AsyncHandler = ([String: Any], @escaping (Result<[String: Any], HelperError>) -> Void) -> Void

var lastFingerprints: [UInt32: [UInt8]] = [:]

/// 16x16 grayscale thumbnail, used to notice that a screen did not change.
func fingerprint(_ img: CGImage) -> [UInt8] {
    let n = 16
    var px = [UInt8](repeating: 0, count: n * n)
    guard let ctx = CGContext(data: &px, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n,
                              space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return px }
    ctx.interpolationQuality = .low
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: n, height: n))
    return px
}

func similar(_ a: [UInt8], _ b: [UInt8]) -> Bool {
    guard a.count == b.count else { return false }
    var diff = 0
    for i in 0..<a.count { diff += abs(Int(a[i]) - Int(b[i])) }
    return diff < a.count * 2 // mean difference < 2/255
}

func recognizeText(_ img: CGImage) -> String {
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .fast
    req.usesLanguageCorrection = false
    try? VNImageRequestHandler(cgImage: img, options: [:]).perform([req])
    return (req.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
}

func pngBase64(_ img: CGImage, maxDim: Int) -> String? {
    var out = img
    let m = max(img.width, img.height)
    if m > maxDim, let ctx = CGContext(data: nil, width: img.width * maxDim / m, height: img.height * maxDim / m, bitsPerComponent: 8, bytesPerRow: 0,
                                       space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) {
        ctx.interpolationQuality = .medium
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: ctx.width, height: ctx.height))
        if let scaled = ctx.makeImage() { out = scaled }
    }
    let rep = NSBitmapImageRep(cgImage: out)
    return rep.representation(using: .png, properties: [:])?.base64EncodedString()
}

/// Captures every display. Screens are never written to disk; the images live only for the duration of this call.
func captureDisplays(_ done: @escaping (Result<[(UInt32, CGImage)], HelperError>) -> Void) {
    guard CGPreflightScreenCaptureAccess() else { done(.failure(HelperError(message: "screen_recording_denied"))); return }
    SCShareableContent.getExcludingDesktopWindows(true, onScreenWindowsOnly: true) { content, err in
        guard let content = content, !content.displays.isEmpty else { done(.failure(HelperError(message: err?.localizedDescription ?? "no_displays"))); return }
        var results: [(UInt32, CGImage)] = []
        let group = DispatchGroup()
        let lock = NSLock()
        for d in content.displays {
            group.enter()
            let cfg = SCStreamConfiguration()
            cfg.width = d.width; cfg.height = d.height
            cfg.showsCursor = false
            SCScreenshotManager.captureImage(contentFilter: SCContentFilter(display: d, excludingWindows: []), configuration: cfg) { img, _ in
                if let img = img { lock.lock(); results.append((d.displayID, img)); lock.unlock() }
                group.leave()
            }
        }
        group.notify(queue: .global()) { done(results.isEmpty ? .failure(HelperError(message: "capture_failed")) : .success(results)) }
    }
}

/// {"cmd":"ocr","skipIfUnchanged":true,"maxChars":4000} -> text for all displays; "unchanged":true when nothing moved.
let ocr: AsyncHandler = { req, done in
    let skip = (req["skipIfUnchanged"] as? Bool) ?? false
    let maxChars = (req["maxChars"] as? Int) ?? 4000
    captureDisplays { res in
        switch res {
        case .failure(let e): done(.failure(e))
        case .success(let shots):
            var changed = false
            for (id, img) in shots {
                let fp = fingerprint(img)
                if let old = lastFingerprints[id], similar(old, fp) {} else { changed = true }
                lastFingerprints[id] = fp
            }
            if skip && !changed { done(.success(["unchanged": true, "displays": shots.count])); return }
            var text = shots.map { recognizeText($0.1) }.filter { !$0.isEmpty }.joined(separator: "\n---\n")
            if text.count > maxChars { text = String(text.prefix(maxChars)) }
            done(.success(["text": text, "displays": shots.count, "unchanged": false]))
        }
    }
}

/// {"cmd":"screenshot","maxDim":1280} -> base64 PNG per display (for the vision tier).
let screenshot: AsyncHandler = { req, done in
    let maxDim = (req["maxDim"] as? Int) ?? 1280
    captureDisplays { res in
        switch res {
        case .failure(let e): done(.failure(e))
        case .success(let shots):
            done(.success(["images": shots.compactMap { pngBase64($0.1, maxDim: maxDim) }, "displays": shots.count]))
        }
    }
}

func battery(_ req: [String: Any]) -> Result<[String: Any], HelperError> {
    guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
          let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef] else { return .success(["hasBattery": false]) }
    for ps in list {
        guard let d = IOPSGetPowerSourceDescription(info, ps)?.takeUnretainedValue() as? [String: Any],
              (d[kIOPSTypeKey] as? String) == kIOPSInternalBatteryType else { continue }
        let cur = d[kIOPSCurrentCapacityKey] as? Int ?? 0, max = d[kIOPSMaxCapacityKey] as? Int ?? 100
        return .success([
            "hasBattery": true, "percent": max > 0 ? cur * 100 / max : 0,
            "charging": (d[kIOPSIsChargingKey] as? Bool) ?? false,
            "onBattery": (d[kIOPSPowerSourceStateKey] as? String) == kIOPSBatteryPowerValue,
        ])
    }
    return .success(["hasBattery": false])
}

/// {"cmd":"request_automation","bundleId":"com.google.Chrome"}: asks for Automation of one browser. Blocks until the
/// user answers, so it runs off the main thread.
let requestAutomation: AsyncHandler = { req, done in
    guard let bid = req["bundleId"] as? String, chromiumLike.contains(bid) || safariLike.contains(bid) else {
        done(.failure(HelperError(message: "unknown_browser"))); return
    }
    DispatchQueue.global().async {
        let status = automationStatus(bid, ask: true)
        DispatchQueue.main.async { browserBackoffUntil[bid] = nil; done(.success(["status": status])) }
    }
}

let asyncHandlers: [String: AsyncHandler] = ["ocr": ocr, "screenshot": screenshot, "request_automation": requestAutomation]

func respond(_ obj: [String: Any]) {
    guard let data = try? JSONSerialization.data(withJSONObject: obj, options: []),
          var line = String(data: data, encoding: .utf8) else { return }
    line += "\n"
    respondLock.lock(); defer { respondLock.unlock() }
    FileHandle.standardOutput.write(line.data(using: .utf8)!)
}

func handle(line: String) {
    guard let data = line.data(using: .utf8),
          let req = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
        respond(["ok": false, "error": "invalid_json"])
        return
    }
    let id: Any = req["id"] ?? NSNull()
    if let cmd = req["cmd"] as? String, let ah = asyncHandlers[cmd] {
        ah(req) { result in
            switch result {
            case .success(let r): respond(["id": id, "ok": true, "result": r])
            case .failure(let e): respond(["id": id, "ok": false, "error": e.message])
            }
        }
        return
    }
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
