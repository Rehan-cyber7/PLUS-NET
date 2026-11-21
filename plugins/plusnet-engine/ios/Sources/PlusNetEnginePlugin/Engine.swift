import Foundation
import Network
import UIKit

/// The connections a download can use at once. NWPathMonitor reports each interface type on its own,
/// so Wi-Fi and cellular show up as separate, simultaneously usable networks.
final class Nets {
    static let ids = ["wifi", "cellular", "ethernet"]
    static let names = ["Wi-Fi", "Mobile data", "Ethernet"]
    private static let types: [NWInterface.InterfaceType] = [.wifi, .cellular, .wiredEthernet]

    var onChange: (() -> Void)?
    private var up: [String: Bool] = [:]
    private let lock = NSLock()
    private var monitors: [NWPathMonitor] = []

    func start() {
        for (i, id) in Nets.ids.enumerated() {
            let m = NWPathMonitor(requiredInterfaceType: Nets.types[i])
            m.pathUpdateHandler = { [weak self] path in
                guard let self = self else { return }
                // requiresConnection: the radio is idle but comes up when something asks (mobile data while on Wi-Fi)
                let on = path.status != .unsatisfied
                self.lock.lock(); let was = self.up[id]; self.up[id] = on; self.lock.unlock()
                if was != on { self.onChange?() }
            }
            m.start(queue: DispatchQueue(label: "plusnet.path.\(id)"))
            monitors.append(m)
        }
    }

    func online(_ id: String) -> Bool { lock.lock(); defer { lock.unlock() }; return up[id] ?? false }

    func interface(_ id: String) -> NWInterface.InterfaceType? {
        Nets.ids.firstIndex(of: id).map { Nets.types[$0] }
    }
}

/// Every download, the networks they share, and the queue.
final class Engine {
    static let shared = Engine()
    static let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]

    private static let maxActive = 3  // the rest wait in the queue

    let nets = Nets()
    var onUpdate: (([String: Any]) -> Void)?
    var onRemoved: ((String) -> Void)?
    var onNetworks: (([[String: Any]]) -> Void)?

    private var jobs: [String: Job] = [:]
    private var limiters: [String: Limiter] = [:]
    private let lock = NSLock()
    private let defaults = UserDefaults.standard
    private let historyFile: URL
    private var lastTick = DispatchTime.now().uptimeNanoseconds
    private var idleLocked = false
    private(set) var active = 0
    private let timer = DispatchSource.makeTimerSource(queue: DispatchQueue(label: "plusnet.tick"))

    private init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        historyFile = support.appendingPathComponent("history.json")
        for id in Nets.ids {
            let l = Limiter()
            l.bytesPerSec = Int64(defaults.integer(forKey: "limit.\(id)"))
            limiters[id] = l
        }
        nets.onChange = { [weak self] in self?.onNetworks?(self?.networks() ?? []) }
        nets.start()
        loadHistory()
        timer.schedule(deadline: .now() + 0.5, repeating: 0.5)
        timer.setEventHandler { [weak self] in self?.tick() }
        timer.resume()
    }

    // ---- what the UI asks for ----

    func limiter(_ netID: String) -> Limiter { limiters[netID] ?? Limiter() }

    func setLimit(_ netID: String, _ bps: Int64) {
        limiters[netID]?.bytesPerSec = bps
        defaults.set(Int(max(0, bps)), forKey: "limit.\(netID)")
    }

    func limits() -> [String: Any] {
        var o: [String: Any] = [:]
        for id in Nets.ids { o[id] = limiter(id).bytesPerSec }
        return o
    }

    func networks() -> [[String: Any]] {
        Nets.ids.enumerated().map { ["id": $1, "name": Nets.names[$0], "online": nets.online($1)] }
    }

    func probe(_ link: String) async throws -> [String: Any] {
        let via = Nets.ids.first { nets.online($0) }.flatMap { nets.interface($0) }
        return try await ProbeResult.run(link, via: via).json
    }

    func start(url: String, name: String?, use: [String]) -> String {
        let j = Job(eng: self, id: UUID().uuidString, url: url, name: name, use: use)
        lock.lock(); jobs[j.id] = j; lock.unlock()
        changed(j)
        return j.id
    }

    func job(_ id: String) -> Job? { lock.lock(); defer { lock.unlock() }; return jobs[id] }

    func list() -> [[String: Any]] { all().map { $0.snap() } }

    func remove(_ id: String, deleteFile: Bool) {
        lock.lock(); let j = jobs.removeValue(forKey: id); lock.unlock()
        guard let job = j else { return }
        job.discard(deleteFile: deleteFile)
        saveHistory()
        onRemoved?(id)
        schedule()
    }

    // ---- called by jobs ----

    func emit(_ j: Job) { onUpdate?(j.snap()) }

    func changed(_ j: Job) {
        emit(j)
        schedule()
    }

    // ---- queue and ticker ----

    private func all() -> [Job] { lock.lock(); defer { lock.unlock() }; return Array(jobs.values) }

    private func schedule() {
        lock.lock(); defer { lock.unlock() }
        let list = Array(jobs.values)
        var running = list.filter { $0.isDownloading }.count
        for j in list where running < Engine.maxActive && j.status == "queued" {
            j.begin()
            running += 1
            emit(j)
        }
        active = running
    }

    private func tick() {
        let now = DispatchTime.now().uptimeNanoseconds
        let dt = Double(now - lastTick) / 1e9
        lastTick = now
        var running = 0
        for j in all() where j.isDownloading {
            j.sample(dt)
            emit(j)
            running += 1
        }
        // iOS suspends an app that is out of sight: keep the screen from locking while a download runs
        let wantLock = running > 0
        if wantLock != idleLocked {
            idleLocked = wantLock
            DispatchQueue.main.async { UIApplication.shared.isIdleTimerDisabled = wantLock }
        }
    }

    // ---- finished downloads survive a restart; ones in progress start over ----

    func saveHistory() {
        let rows = all().filter { $0.isCompleted }.compactMap { $0.history() }
        if let data = try? JSONSerialization.data(withJSONObject: rows) { try? data.write(to: historyFile) }
    }

    private func loadHistory() {
        guard let data = try? Data(contentsOf: historyFile),
              let rows = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return }
        for r in rows {
            // the user may have deleted the file from the Files app
            if let j = Job.restore(self, r), let f = j.finalPath, FileManager.default.fileExists(atPath: f.path) { jobs[j.id] = j }
        }
    }
}
