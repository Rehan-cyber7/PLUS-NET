import Foundation
import Network

struct Stat {
    let id: String
    let idx: Int  // its place in Nets.ids, which the cell map uses
    var enabled = false
    var failed = false
    var error: String?
    var speed: Int64 = 0
    var last: Int64 = 0
    var bytes: Int64 = 0
    var streams = 0
    var fails = 0
}

private final class Pos { var v: Int64; init(_ v: Int64) { self.v = v } }

/// One download. A run (start, or resume after a pause) polls once in a while for networks that
/// have come or gone, and keeps a few streams open on each usable one. Every stream takes the next
/// waiting chunk, so a faster network ends up doing more of the file. Same design as Job.java.
final class Job: @unchecked Sendable {
    private static let streamsPerNet = 4
    private static let maxFails = 6

    let eng: Engine
    let id: String
    let url: String
    private let lock = NSLock()

    private(set) var status = "queued"  // queued | downloading | paused | completed | error
    private(set) var error: String?
    private(set) var fileName: String?
    private(set) var total: Int64 = -1
    private(set) var createdAt = Date().timeIntervalSince1970 * 1000
    private(set) var finishedAt = 0.0
    private var ranges = false
    private var finalURL: URL?
    private var stats: [Stat] = []
    private var map: ChunkMap?  // nil while unprobed, and when the server can't do ranges
    private var finalBytes: Int64 = 0
    private var plain: Int64 = 0  // bytes of a download that isn't split
    private var singleDone = false
    private var removed = false
    private var live = 0
    private var runID = 0
    private var task: Task<Void, Never>?
    private var part: URL?   // the file while it downloads: "name.plusnet"
    private var fd: Int32 = -1
    private var sized = false

    init(eng: Engine, id: String, url: String, name: String?, use: [String]) {
        self.eng = eng
        self.id = id
        self.url = url
        if let n = name, !n.trimmingCharacters(in: .whitespaces).isEmpty { fileName = ProbeResult.clean(n) }
        for (i, nid) in Nets.ids.enumerated() {
            var s = Stat(id: nid, idx: i)
            s.enabled = use.contains(nid)
            stats.append(s)
        }
    }

    @discardableResult private func sync<T>(_ f: () -> T) -> T { lock.lock(); defer { lock.unlock() }; return f() }

    // ---- state for the UI ----

    var isDownloading: Bool { sync { status == "downloading" } }
    var isCompleted: Bool { sync { status == "completed" } }
    var finalPath: URL? { sync { fileName.map { Engine.docs.appendingPathComponent($0) } } }

    private func done() -> Int64 {
        sync { status == "completed" ? finalBytes : (map?.bytes ?? plain) }
    }

    func speed() -> Int64 {
        sync { status == "downloading" ? stats.reduce(0) { $0 + $1.speed } : 0 }
    }

    func sample(_ dt: Double) {
        sync {
            for i in stats.indices {
                stats[i].speed = Int64(Double(stats[i].speed) * 0.6 + Double(stats[i].bytes - stats[i].last) / dt * 0.4)
                stats[i].last = stats[i].bytes
            }
        }
    }

    func snap() -> [String: Any] {
        let (st, down, copy, err, name) = sync { (status, status == "downloading", stats, error, fileName) }
        let nets: [[String: Any]] = copy.compactMap { s in
            let on = eng.nets.online(s.id)
            guard s.enabled || on || s.bytes > 0 else { return nil }
            let state = !s.enabled ? "off" : s.failed ? "failed" : on ? "on" : "offline"
            var row: [String: Any] = ["id": s.id, "enabled": s.enabled, "state": state, "bytes": s.bytes, "speed": down ? s.speed : 0]
            row["error"] = s.error ?? NSNull()
            return row
        }
        var o: [String: Any] = [
            "id": id, "url": url, "fileName": name ?? "…", "status": st, "total": total, "done": done(),
            "speed": speed(), "ranges": ranges || finalURL == nil,  // unknown until probed: don't warn yet
            "cells": map?.cells(max: 120) ?? "", "createdAt": createdAt, "finishedAt": finishedAt, "nets": nets
        ]
        o["error"] = err ?? NSNull()
        return o
    }

    func history() -> [String: Any]? {
        guard let name = fileName else { return nil }
        var by: [String: Int64] = [:]
        for s in sync({ stats }) { by[s.id] = s.bytes }
        return ["id": id, "url": url, "fileName": name, "total": total, "done": finalBytes,
                "createdAt": createdAt, "finishedAt": finishedAt, "bytes": by]
    }

    static func restore(_ eng: Engine, _ o: [String: Any]) -> Job? {
        guard let id = o["id"] as? String, let url = o["url"] as? String, let name = o["fileName"] as? String else { return nil }
        let j = Job(eng: eng, id: id, url: url, name: name, use: [])
        j.status = "completed"
        j.ranges = true
        j.total = (o["total"] as? NSNumber)?.int64Value ?? -1
        j.finalBytes = (o["done"] as? NSNumber)?.int64Value ?? 0
        j.createdAt = (o["createdAt"] as? NSNumber)?.doubleValue ?? j.createdAt
        j.finishedAt = (o["finishedAt"] as? NSNumber)?.doubleValue ?? 0
        if let by = o["bytes"] as? [String: NSNumber] {
            for i in j.stats.indices { j.stats[i].bytes = by[j.stats[i].id]?.int64Value ?? 0 }
        }
        return j
    }

    // ---- commands ----

    func pause() {
        let was = sync { status }
        if was == "downloading" { task?.cancel() } else if was != "queued" { return }
        sync { status = "paused" }
        eng.changed(self)
    }

    func resume() {
        let ok = sync { status == "paused" || status == "error" }
        if !ok { return }
        sync {
            for i in stats.indices { stats[i].failed = false; stats[i].fails = 0 }
            error = nil
            status = "queued"
        }
        eng.changed(self)
    }

    func setNetwork(_ netID: String, _ on: Bool) {
        sync {
            for i in stats.indices where stats[i].id == netID {
                stats[i].enabled = on
                stats[i].failed = false  // switching it off and on is how a refused network is tried again
                stats[i].fails = 0
            }
        }
        eng.emit(self)
    }

    /// Stops the run and drops the file, unless it is a finished one the user wants to keep.
    func discard(deleteFile: Bool) {
        sync { removed = true }
        task?.cancel()
        let fm = FileManager.default
        if let p = part { try? fm.removeItem(at: p) }
        if isCompleted && deleteFile, let f = finalPath { try? fm.removeItem(at: f) }
    }

    /// Called by the engine when a slot is free.
    func begin() {
        let prev = task
        let gen: Int = sync {
            runID += 1
            status = "downloading"
            error = nil
            return runID
        }
        task = Task { [self] in
            _ = await prev?.value  // a paused run still winding down
            await run(gen)
        }
    }

    // ---- the run ----

    private func run(_ gen: Int) async {
        var failure: String?
        do {
            eng.emit(self)
            if sync({ finalURL }) == nil { try await probe() }
            try openFile()
            try await withThrowingTaskGroup(of: Void.self) { group in
                while !Task.isCancelled && !self.complete() {
                    self.spawn(&group)
                    if self.sync({ self.live }) == 0 && self.anyEnabled() && self.allFailed() { throw HTTPError(self.lastError()) }
                    try await Task.sleep(nanoseconds: 300_000_000)
                }
                group.cancelAll()
            }
        } catch is CancellationError {
            // stopped
        } catch {
            if !Task.isCancelled { failure = error.localizedDescription }
        }
        closeFile()
        finish(gen, failure)
    }

    private func probe() async throws {
        let via = sync { stats.first { $0.enabled && !$0.failed && eng.nets.online($0.id) }?.id }
        let p = try await ProbeResult.run(url, via: via.flatMap { eng.nets.interface($0) })
        sync {
            finalURL = p.finalURL
            total = p.total
            ranges = p.ranges
            if fileName == nil { fileName = p.fileName }
            map = p.ranges ? ChunkMap(total: p.total) : nil
        }
    }

    private func complete() -> Bool { sync { ranges ? (map?.complete ?? false) : singleDone } }
    private func enabled(_ i: Int) -> Bool { sync { stats[i].enabled } }
    /// Whether the download's mode lets this network work right now: together, all usable ones; otherwise
    /// only the best usable one, the others taking over when it is gone and giving way when it is back.
    private func allowed(_ i: Int) -> Bool {
        if eng.together { return true }
        for id in Nets.priority {
            if let j = Nets.ids.firstIndex(of: id), usable(j) { return j == i }
        }
        return false
    }

    private func usable(_ i: Int) -> Bool {
        let ok = sync { stats[i].enabled && !stats[i].failed }
        return ok && eng.nets.online(Nets.ids[i])
    }
    private func anyEnabled() -> Bool { sync { stats.contains { $0.enabled } } }
    private func allFailed() -> Bool { sync { stats.allSatisfy { !$0.enabled || $0.failed } } }
    private func lastError() -> String { sync { stats.first { $0.enabled && $0.error != nil }?.error ?? "Every network failed" } }

    private func spawn(_ group: inout ThrowingTaskGroup<Void, Error>) {
        if !sync({ ranges }) {  // one stream, on the first network that works
            if sync({ live == 0 && !singleDone }), let i = stats.indices.first(where: { usable($0) && allowed($0) }) {
                sync { live += 1; stats[i].streams += 1 }
                group.addTask { await self.single(i) }
            }
            return
        }
        guard let map = sync({ self.map }), map.hasWaiting else { return }
        for i in stats.indices where usable(i) && allowed(i) && sync({ stats[i].streams }) == 0 {
            for _ in 0..<Job.streamsPerNet {
                sync { live += 1; stats[i].streams += 1 }
                group.addTask { await self.work(i, map) }
            }
        }
    }

    private func work(_ i: Int, _ map: ChunkMap) async {
        defer { sync { stats[i].streams -= 1; live -= 1 } }
        while !Task.isCancelled && enabled(i) && allowed(i) {
            guard let c = map.claim() else { return }
            do {
                try await fetch(i, c, map)
                map.finish(c, net: i)
                sync { stats[i].fails = 0; stats[i].error = nil }
            } catch {
                map.release(c)  // what was written stays: the next try starts after it
                if Task.isCancelled || !enabled(i) || !allowed(i) || !eng.nets.online(Nets.ids[i]) { return }  // spawn() restarts it when it's back
                let n: Int = sync {
                    stats[i].error = error.localizedDescription
                    stats[i].fails += 1
                    return stats[i].fails
                }
                if n >= Job.maxFails { sync { stats[i].failed = true }; return }
                try? await Task.sleep(nanoseconds: UInt64(min(8000, 500 << n)) * 1_000_000)
            }
        }
    }

    private func fetch(_ i: Int, _ c: Int, _ map: ChunkMap) async throws {
        let from = map.from(c), to = map.to(c)
        if from > to { return }  // all of it was written already
        guard let url = sync({ finalURL }) else { return }
        let pos = Pos(from)
        let reply = try await HTTP.get(url, via: eng.nets.interface(Nets.ids[i]), range: "bytes=\(from)-\(to)", bodyStatus: [206]) { data in
            // never take more than was asked for, even if the server sends more
            let part = data.prefix(Int(to + 1 - pos.v))
            try await self.store(Data(part), i, at: pos.v, split: true)
            pos.v += Int64(part.count)
            map.add(c, part.count)
        }
        guard reply.status == 206 else {
            throw HTTPError(reply.status == 200 ? "The server stopped allowing range requests" : ProbeResult.describe(reply.status))
        }
        if pos.v != to + 1 { throw HTTPError("The connection closed early") }
    }

    private func single(_ i: Int) async {
        defer { sync { stats[i].streams -= 1; live -= 1 } }
        guard let url = sync({ finalURL }) else { return }
        let pos = Pos(0)
        sync { plain = 0 }  // no ranges: a retry starts the file again
        do {
            let reply = try await HTTP.get(url, via: eng.nets.interface(Nets.ids[i]), bodyStatus: [200]) { data in
                try await self.store(data, i, at: pos.v, split: false)
                pos.v += Int64(data.count)
                self.sync { self.plain += Int64(data.count) }
            }
            if reply.status != 200 { throw HTTPError(ProbeResult.describe(reply.status)) }
            if total >= 0 && pos.v != total { throw HTTPError("The connection closed early") }
            sync { singleDone = true }
        } catch {
            if Task.isCancelled || !enabled(i) { return }
            sync {
                stats[i].error = error.localizedDescription
                stats[i].fails += 1
                if stats[i].fails >= Job.maxFails { stats[i].failed = true }
            }
        }
    }

    /// Writes a piece of the body at its offset, under the network's speed cap.
    private func store(_ d: Data, _ i: Int, at off: Int64, split: Bool) async throws {
        // a split download hands over mid-chunk when the preferred network returns; one that isn't split can't
        if Task.isCancelled || !enabled(i) || (split && !allowed(i)) { throw HTTPError("Stopped") }
        let wait = eng.limiter(Nets.ids[i]).reserve(d.count)
        if wait > 0 { try await Task.sleep(nanoseconds: wait) }
        let handle = fd
        try d.withUnsafeBytes { raw in
            var written = 0
            while written < raw.count {
                let n = pwrite(handle, raw.baseAddress!.advanced(by: written), raw.count - written, off_t(off) + off_t(written))
                if n < 0 { throw HTTPError("Couldn't write the file (\(String(cString: strerror(errno))))") }
                written += n
            }
        }
        sync { stats[i].bytes += Int64(d.count) }
    }

    private func finish(_ gen: Int, _ failure: String?) {
        if sync({ removed || gen != runID }) { return }  // removed, or a newer run has taken over
        if complete() {
            do {
                try seal()
                sync { status = "completed" }
            } catch {
                sync { self.error = error.localizedDescription; status = "error" }
            }
        } else if let f = failure {
            sync { error = f; status = "error" }
        } else {
            sync { status = "paused" }
        }
        sync { for i in stats.indices { stats[i].speed = 0 } }
        if isCompleted { eng.saveHistory() }
        eng.changed(self)
    }

    // ---- the file: "name.plusnet" in the app's Documents folder (shown in the Files app), renamed when done ----

    private func openFile() throws {
        if part == nil {
            let name = Job.unique(sync({ fileName }) ?? "download", in: Engine.docs)
            sync { fileName = name }
            part = Engine.docs.appendingPathComponent(name + ".plusnet")
        }
        fd = open(part!.path, O_RDWR | O_CREAT, 0o644)
        if fd < 0 { throw HTTPError("Couldn't create the file") }
        if !sized && total > 0 {
            if ftruncate(fd, off_t(total)) != 0 { throw HTTPError("Not enough free space") }  // claims the space now
            sized = true
        }
    }

    private func closeFile() {
        if fd >= 0 { close(fd); fd = -1 }
    }

    private func seal() throws {
        guard let p = part, let f = finalPath else { throw HTTPError("The file is gone") }
        try FileManager.default.moveItem(at: p, to: f)
        part = nil
        sync {
            finalBytes = map?.bytes ?? plain
            if total < 0 { total = finalBytes }
            finishedAt = Date().timeIntervalSince1970 * 1000
        }
    }

    private static func unique(_ name: String, in dir: URL) -> String {
        let fm = FileManager.default
        let ext = (name as NSString).pathExtension
        let base = (name as NSString).deletingPathExtension
        var candidate = name
        var n = 1
        while fm.fileExists(atPath: dir.appendingPathComponent(candidate).path)
            || fm.fileExists(atPath: dir.appendingPathComponent(candidate + ".plusnet").path) {
            n += 1
            candidate = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
        }
        return candidate
    }
}
