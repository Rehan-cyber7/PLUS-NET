import Capacitor
import UIKit

/// The JS side of the engine: thin on purpose, every decision is made in Engine and Job.
@objc(PlusNetEnginePlugin)
public class PlusNetEnginePlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "PlusNetEnginePlugin"
    public let jsName = "PlusNetEngine"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "networks", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "probe", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "start", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pause", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resume", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "remove", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setNetwork", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "setLimit", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "list", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openFile", returnType: CAPPluginReturnPromise)
    ]

    private let eng = Engine.shared
    private var background: UIBackgroundTaskIdentifier = .invalid

    override public func load() {
        eng.onUpdate = { [weak self] snap in self?.notifyListeners("update", data: snap) }
        eng.onRemoved = { [weak self] id in self?.notifyListeners("removed", data: ["id": id]) }
        eng.onNetworks = { [weak self] list in self?.notifyListeners("networks", data: ["networks": list]) }

        // Out of sight, iOS gives an app about 30 seconds more. Ask for them while a download runs.
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self, self.eng.active > 0, self.background == .invalid else { return }
            self.background = UIApplication.shared.beginBackgroundTask { [weak self] in self?.endBackground() }
        }
        nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            self?.endBackground()
        }
    }

    private func endBackground() {
        if background != .invalid { UIApplication.shared.endBackgroundTask(background) }
        background = .invalid
    }

    @objc func networks(_ call: CAPPluginCall) {
        call.resolve(["networks": eng.networks()])
    }

    @objc func probe(_ call: CAPPluginCall) {
        guard let url = call.getString("url") else { return call.reject("Missing link") }
        Task {
            do { call.resolve(try await eng.probe(url)) } catch { call.reject(error.localizedDescription) }
        }
    }

    @objc func start(_ call: CAPPluginCall) {
        guard let url = call.getString("url") else { return call.reject("Missing link") }
        let use = call.getArray("networks", String.self) ?? []
        call.resolve(["id": eng.start(url: url, name: call.getString("fileName"), use: use)])
    }

    @objc func pause(_ call: CAPPluginCall) {
        eng.job(call.getString("id") ?? "")?.pause()
        call.resolve()
    }

    @objc func resume(_ call: CAPPluginCall) {
        eng.job(call.getString("id") ?? "")?.resume()
        call.resolve()
    }

    @objc func remove(_ call: CAPPluginCall) {
        eng.remove(call.getString("id") ?? "", deleteFile: call.getBool("deleteFile") ?? false)
        call.resolve()
    }

    @objc func setNetwork(_ call: CAPPluginCall) {
        eng.job(call.getString("id") ?? "")?.setNetwork(call.getString("network") ?? "", call.getBool("enabled") ?? false)
        call.resolve()
    }

    @objc func setLimit(_ call: CAPPluginCall) {
        eng.setLimit(call.getString("network") ?? "", Int64(call.getDouble("bytesPerSec") ?? 0))
        call.resolve()
    }

    @objc func list(_ call: CAPPluginCall) {
        call.resolve(["downloads": eng.list(), "limits": eng.limits()])
    }

    @objc func openFile(_ call: CAPPluginCall) {
        guard let file = eng.job(call.getString("id") ?? "")?.finalPath, FileManager.default.fileExists(atPath: file.path) else {
            return call.reject("The file is gone")
        }
        // The share sheet is how iOS offers "Open in…", Save to Files, AirDrop.
        DispatchQueue.main.async {
            let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
            sheet.popoverPresentationController?.sourceView = self.bridge?.viewController?.view
            self.bridge?.viewController?.present(sheet, animated: true)
            call.resolve()
        }
    }
}
