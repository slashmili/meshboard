#if DEBUG && targetEnvironment(simulator)
import Foundation
import Network
import MeshboardShared

/// Opt-in loopback adapter for browser integration tests, never present in a device/Release binary.
final class DebugInterop {
    private let controller: AppleInterop
    private let listener: NWListener
    private var connection: NWConnection?
    private var timer: DispatchSourceTimer?
    private var buffer = Data()
    private var last = ""
    init() throws {
        controller = AppleInterop(network: NativeAppleNetwork(forceRelay: CommandLine.arguments.contains("--relay")))
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: 18766)
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            guard let self, self.connection == nil else { connection.cancel(); return }
            self.connection = connection
            connection.stateUpdateHandler = { [weak self] state in
                guard let self else { return }
                if case .ready = state { self.read(connection); self.poll() }
            }
            connection.start(queue: .main)
        }
        listener.stateUpdateHandler = { state in print("Interop listener: \(state)") }
        listener.start(queue: .main)
    }
    private func poll() {
        let timer = DispatchSource.makeTimerSource(queue: .main)
        timer.schedule(deadline: .now(), repeating: .milliseconds(50))
        timer.setEventHandler { [weak self] in
            guard let self, let connection = self.connection else { return }
            let state = self.controller.snapshot()
            if state != self.last { self.last = state; connection.send(content: Data(("MESHBOARD " + state + "\n").utf8), completion: .contentProcessed { _ in }) }
        }
        self.timer = timer; timer.resume()
    }
    private func read(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, complete, error in
            guard let self else { return }
            if let data { self.buffer.append(data) }
            if self.buffer.count > 5 * 1024 * 1024 { self.close(); return }
            while let newline = self.buffer.firstIndex(of: 10) {
                let line = self.buffer.prefix(upTo: newline); self.buffer.removeSubrange(...newline)
                guard let raw = String(data: line, encoding: .utf8) else { self.close(); return }
                self.controller.command(raw: raw)
            }
            if complete || error != nil { self.close() } else { self.read(connection) }
        }
    }
    private func close() {
        timer?.cancel(); timer = nil; connection?.cancel(); listener.cancel()
        controller.command(raw: "{\"type\":\"close\"}")
    }
}
#endif
