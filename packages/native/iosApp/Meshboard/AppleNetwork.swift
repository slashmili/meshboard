import Foundation
import UIKit
import CoreImage.CIFilterBuiltins
import WebRTC
import MeshboardShared

private enum NetworkError: Error { case invalid }
private let fragmentChannel = "meshboard.y-webrtc.fragments-v1"
private let fragmentSDP = "a=meshboard-fragments:1"
private let maxYMessage = 2 * 1024 * 1024 + 1024
private func object(_ value: Any?) throws -> [String: Any] {
    guard let value = value as? [String: Any] else { throw NetworkError.invalid }; return value
}
private func text(_ value: Any?) throws -> String {
    guard let value = value as? String else { throw NetworkError.invalid }; return value
}
private func exact(_ value: [String: Any], _ keys: String...) throws {
    guard Set(value.keys) == Set(keys) else { throw NetworkError.invalid }
}
private func json(_ value: [String: Any]) -> String {
    String(data: try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), encoding: .utf8)!
}
private func uuid(_ value: String) -> Bool {
    value.range(of: "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$", options: .regularExpression) != nil
}

/// All mutable session state lives on the main queue; native callbacks enqueue copies.
final class NativeAppleNetwork: NSObject, AppleNetwork, URLSessionWebSocketDelegate {
    fileprivate let crdtPreview = AppleCrdtModeKt.appleCrdtPreviewEnabled()
    private var events: AppleNetworkEvents?
    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 10
        config.urlCache = nil
        return URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }()
    private lazy var factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        RTCSetMinDebugLogLevel(.error)
        return RTCPeerConnectionFactory()
    }()
    private var configuration = RTCConfiguration()
    private var socket: URLSessionWebSocketTask?
    private var fetch: URLSessionDataTask?
    private var receiveTask: Task<Void, Never>?
    private var signalQueue: [String] = []
    private var signalBytes = 0
    private var signalSending = false
    private var generation = 0
    private var origin = ""
    private var room = ""
    private var selfID = ""
    private var desired = Set<String>()
    private var attempts: [String: Int] = [:]
    private var peers: [String: ApplePeer] = [:]
    private var reconnect: DispatchWorkItem?
    private var reconnectDelay = 1.0
    private let forceRelay: Bool
    #if DEBUG && targetEnvironment(simulator)
    // Only connected by the opt-in test adapter; never log SDP, ICE addresses or content.
    var diagnostic: ((String) -> Void)?
    #endif
    fileprivate func diagnose(_ event: String, _ peer: ApplePeer) {
        #if DEBUG && targetEnvironment(simulator)
        guard let diagnostic else { return }
        diagnostic(json(["event": event, "peer": peer.id,
                         "ageMs": Int((ProcessInfo.processInfo.systemUptime - peer.started) * 1000),
                         "connection": peer.pc.connectionState.rawValue,
                         "ice": peer.pc.iceConnectionState.rawValue,
                         "signaling": peer.pc.signalingState.rawValue,
                         "open": peer.open]))
        #endif
    }
    init(forceRelay: Bool = false) { self.forceRelay = forceRelay; super.init() }
    func listen(events: AppleNetworkEvents?) { self.events = events; if events == nil { session.invalidateAndCancel() } }
    func normalizeOrigin(value: String) -> String? {
        guard value.count <= 2048, let parts = URLComponents(string: value.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = parts.scheme, let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, (parts.port ?? 443) > 0, (parts.port ?? 443) <= 65535,
              scheme == "https" || (scheme == "http" && ["localhost", "127.0.0.1"].contains(host)) else { return nil }
        var base = URLComponents(); base.scheme = scheme; base.host = host; base.port = parts.port
        return base.string
    }
    func start(origin: String, room: String) {
        guard !crdtPreview || previewAllowed(origin: origin) else {
            events?.signaling(active: false, error: "CRDT preview is local-only and requires the iPad simulator."); return
        }
        stop(); self.origin = origin; self.room = room
        if crdtPreview { selfID = UUID().uuidString.lowercased() }
        let current = generation
        var request = URLRequest(url: URL(string: origin + "/api/rtc-config")!)
        request.timeoutInterval = 8
        fetch = session.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self, self.generation == current else { return }
                do {
                    guard error == nil, (response as? HTTPURLResponse)?.statusCode == 200,
                          let data, data.count <= 16_384 else { throw NetworkError.invalid }
                    let config = try object(JSONSerialization.jsonObject(with: data))
                    try exact(config, "iceServers", "iceTransportPolicy", "localDevelopment")
                    let policy = try text(config["iceTransportPolicy"])
                    guard ["all", "relay"].contains(policy), config["localDevelopment"] is Bool,
                          let servers = config["iceServers"] as? [[String: Any]], servers.count <= 8 else { throw NetworkError.invalid }
                    if self.crdtPreview && config["localDevelopment"] as? Bool != true {
                        self.events?.signaling(active: false, error: "CRDT preview requires a local-development server."); return
                    }
                    let rtc = RTCConfiguration(); rtc.sdpSemantics = .unifiedPlan
                    rtc.iceTransportPolicy = self.forceRelay || policy == "relay" ? .relay : .all
                    rtc.iceServers = try servers.map { server in
                        guard Set(server.keys).isSubset(of: ["urls", "username", "credential"]),
                              let urls = server["urls"] as? [String], (1...8).contains(urls.count),
                              urls.allSatisfy({ $0.range(of: "^(stun|stuns|turn|turns):[^\\s]+$", options: .regularExpression) != nil }) else { throw NetworkError.invalid }
                        return RTCIceServer(urlStrings: urls, username: server["username"] as? String ?? "", credential: server["credential"] as? String ?? "")
                    }
                    self.configuration = rtc; self.connect()
                } catch { self.events?.signaling(active: false, error: "Could not load connection settings. Check the app address and retry.") }
            }
        }
        fetch?.resume()
    }
    func stop() {
        generation += 1; reconnect?.cancel(); reconnect = nil; fetch?.cancel(); fetch = nil
        receiveTask?.cancel(); receiveTask = nil
        signalQueue.removeAll(); signalBytes = 0; signalSending = false
        let old = socket; socket = nil; old?.cancel(with: .goingAway, reason: nil)
        desired.removeAll(); attempts.removeAll(); selfID = ""; reconnectDelay = 1
        for id in Array(peers.keys) { drop(id) }
    }
    private func connect() {
        let url = origin.replacingOccurrences(of: "https:", with: "wss:").replacingOccurrences(of: "http:", with: "ws:") + (crdtPreview ? "/signal-y-webrtc" : "/signal")
        let ws = session.webSocketTask(with: URL(string: url)!); ws.maximumMessageSize = 32_768
        socket = ws; ws.resume()
        receiveTask = Task { [weak self, weak ws] in
            guard let ws else { return }
            do {
                while !Task.isCancelled {
                    let message = try await ws.receive()
                    await MainActor.run {
                        guard let self, self.socket === ws else { return }
                        do {
                            guard case let .string(raw) = message, raw.utf8.count <= 32_768 else { throw NetworkError.invalid }
                            try self.receiveSignal(raw)
                        } catch {
                            self.lost(ws, rejected: true)
                            ws.cancel(with: .policyViolation, reason: nil)
                        }
                    }
                }
            } catch { await MainActor.run { self?.lost(ws, rejected: false) } }
        }
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        guard socket === webSocketTask else { return }
        if crdtPreview {
            sendSignal(["type": "subscribe", "topics": ["meshboard-crdt:" + room]])
            announce(); reconnectDelay = 1; events?.signaling(active: true, error: nil)
        } else { sendSignal(["v": 1, "type": "join", "room": room]) }
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        lost(webSocketTask, rejected: closeCode == .policyViolation)
    }
    // Do not follow a configuration redirect to an unexpected origin.
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    private func lost(_ ws: URLSessionWebSocketTask, rejected: Bool) {
        guard socket === ws else { return }; socket = nil
        receiveTask?.cancel(); receiveTask = nil; ws.cancel(with: .goingAway, reason: nil)
        signalQueue.removeAll(); signalBytes = 0; signalSending = false
        events?.signaling(active: false, error: rejected ? "The connection service declined this session." : "Reconnecting to the connection service…")
        guard !rejected else { return }
        let current = generation
        let work = DispatchWorkItem { [weak self] in guard let self, self.generation == current else { return }; self.connect() }
        reconnect?.cancel(); reconnect = work
        DispatchQueue.main.asyncAfter(deadline: .now() + reconnectDelay, execute: work)
        reconnectDelay = min(8, reconnectDelay * 2)
    }
    private func sendSignal(_ value: [String: Any]) {
        guard let ws = socket else { return }
        let raw = json(value)
        guard raw.utf8.count <= 32_768, signalBytes + raw.utf8.count <= 256 * 1024 else { lost(ws, rejected: true); return }
        signalQueue.append(raw); signalBytes += raw.utf8.count; flushSignals()
    }
    private func flushSignals() {
        guard !signalSending, let ws = socket, let raw = signalQueue.first else { return }
        signalSending = true
        ws.send(.string(raw)) { [weak self, weak ws] error in
            DispatchQueue.main.async {
                guard let self, let ws, self.socket === ws else { return }
                if error != nil { self.lost(ws, rejected: false); return }
                self.signalQueue.removeFirst(); self.signalBytes -= raw.utf8.count
                self.signalSending = false; self.flushSignals()
            }
        }
    }
    fileprivate func signal(_ peer: ApplePeer, _ payload: [String: Any]) {
        guard peers[peer.id] === peer else { return }
        if crdtPreview {
            if peer.token == nil { peer.token = Date().timeIntervalSince1970 * 1000 + Double.random(in: 0..<1) }
            publish(["type": "signal", "from": selfID, "to": peer.id, "token": peer.token!,
                     "signal": payload["description"] ?? ["type": "candidate", "candidate": payload["candidate"]!]])
        } else { sendSignal(["v": 1, "type": "signal", "to": peer.id, "payload": payload]) }
    }
    private func publish(_ data: [String: Any]) { sendSignal(["type": "publish", "topic": "meshboard-crdt:" + room, "data": data]) }
    private func announce() { publish(["type": "announce", "from": selfID]) }
    private func receiveSignal(_ raw: String) throws {
        let value = try object(JSONSerialization.jsonObject(with: Data(raw.utf8)))
        if crdtPreview { try receiveYSignal(value); return }
        try receiveLegacySignal(value)
    }
    private func receiveYSignal(_ value: [String: Any]) throws {
        let type = try text(value["type"])
        if type == "pong" { return }
        if type == "error" {
            let old = socket; socket = nil; old?.cancel(with: .goingAway, reason: nil)
            events?.signaling(active: false, error: try text(value["code"]) == "room-full" ? "This board already has 8 participants." : "The connection service declined this session.")
            return
        }
        guard type == "publish", try text(value["topic"]) == "meshboard-crdt:" + room else { throw NetworkError.invalid }
        let data = try object(value["data"]), id = try text(data["from"])
        guard uuid(id) else { throw NetworkError.invalid }
        if id == selfID { return }
        if let to = data["to"], try text(to) != selfID { return }
        if peers.count >= 7 && peers[id] == nil { return }
        desired.insert(id)
        if try text(data["type"]) == "announce" { _ = ensure(id, initiator: true); return }
        guard try text(data["type"]) == "signal" else { throw NetworkError.invalid }
        let signal = try object(data["signal"]), signalType = try text(signal["type"])
        if signalType == "offer" {
            if peers[id]?.open == true { return }
            guard let remote = data["token"] as? NSNumber, CFGetTypeID(remote) != CFBooleanGetTypeID(), remote.doubleValue.isFinite else { throw NetworkError.invalid }
            if let token = peers[id]?.token {
                if token > remote.doubleValue { return }
                drop(id) // Match simple-peer's glare policy: recreate the losing offerer.
            }
        }
        guard let peer = ensure(id, initiator: false) else { return }
        var payload: [String: Any]
        if signalType == "offer" || signalType == "answer" {
            peer.remoteFragments = try text(signal["sdp"]).components(separatedBy: "\r\n").contains(fragmentSDP)
            if signalType == "answer" { peer.token = nil }
            payload = ["description": signal]
        } else if signalType == "candidate" { payload = ["candidate": try object(signal["candidate"])] }
        else { throw NetworkError.invalid }
        try receiveLegacySignal(["v": 1, "type": "signal", "from": id, "payload": payload])
    }
    private func receiveLegacySignal(_ value: [String: Any]) throws {
        guard let version = value["v"] as? NSNumber, CFGetTypeID(version) != CFBooleanGetTypeID(), version == 1 else { throw NetworkError.invalid }
        switch try text(value["type"]) {
        case "welcome":
            try exact(value, "v", "type", "self", "peers")
            let id = try text(value["self"])
            guard uuid(id), let ids = value["peers"] as? [String], ids.count <= 7, ids.allSatisfy(uuid), !ids.contains(id), Set(ids).count == ids.count else { throw NetworkError.invalid }
            if !selfID.isEmpty && selfID != id { for id in Array(peers.keys) { drop(id) } }
            selfID = id; desired = Set(ids); attempts.removeAll(); reconnectDelay = 1
            events?.signaling(active: true, error: nil)
            for id in ids { _ = ensure(id) }
        case "peer-joined":
            try exact(value, "v", "type", "peer"); let id = try text(value["peer"])
            guard uuid(id), id != selfID, desired.count < 7 else { throw NetworkError.invalid }
            desired.insert(id); _ = ensure(id)
        case "peer-left":
            try exact(value, "v", "type", "peer"); let id = try text(value["peer"]); desired.remove(id)
            guard let peer = peers[id] else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self, weak peer] in
                guard let self, let peer, self.socket != nil, !self.desired.contains(id), self.peers[id] === peer else { return }; self.drop(id)
            }
        case "signal":
            try exact(value, "v", "type", "from", "payload"); let id = try text(value["from"])
            guard desired.contains(id), let peer = ensure(id) else { return }
            let payload = try object(value["payload"])
            if payload["description"] != nil {
                try exact(payload, "description"); let d = try object(payload["description"]); try exact(d, "type", "sdp")
                let type = try text(d["type"]); let sdp = try text(d["sdp"])
                guard ["offer", "answer"].contains(type), sdp.hasPrefix("v=0"), sdp.utf8.count <= 24_000 else { throw NetworkError.invalid }
                peer.remote(type: type == "offer" ? .offer : .answer, sdp: sdp)
            } else {
                try exact(payload, "candidate"); let c = try object(payload["candidate"])
                guard Set(c.keys).isSubset(of: ["candidate", "sdpMid", "sdpMLineIndex", "usernameFragment"]) else { throw NetworkError.invalid }
                let sdp = try text(c["candidate"]); guard sdp.count <= 2048 else { throw NetworkError.invalid }
                if sdp.isEmpty { return }
                let mid = c["sdpMid"] as? String
                let index = c["sdpMLineIndex"] as? Int ?? 0
                guard (0...16).contains(index), (mid?.count ?? 0) <= 64 else { throw NetworkError.invalid }
                try peer.candidate(RTCIceCandidate(sdp: sdp, sdpMLineIndex: Int32(index), sdpMid: mid))
            }
        case "error":
            try exact(value, "v", "type", "code")
            events?.signaling(active: false, error: try text(value["code"]) == "room-full" ? "This board already has 8 participants." : "The connection service declined this session.")
        default: throw NetworkError.invalid
        }
    }
    private func ensure(_ id: String, initiator: Bool? = nil) -> ApplePeer? {
        if let peer = peers[id] { return peer }
        guard desired.contains(id) else { return nil }
        let count = attempts[id, default: 0]
        guard count < 3 else { events?.signaling(active: socket != nil, error: "A peer could not connect. Check the network and retry."); return nil }
        attempts[id] = count + 1
        let peer = ApplePeer(id: id, owner: self)
        guard let pc = factory.peerConnection(with: configuration, constraints: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil), delegate: peer) else { return nil }
        peer.pc = pc; peers[id] = peer
        diagnose("created", peer)
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self, weak peer] in
            if let peer, !peer.open { self?.failed(peer, reason: "connection-timeout") }
        }
        if initiator ?? (!crdtPreview && selfID < id) {
            if crdtPreview { peer.token = Date().timeIntervalSince1970 * 1000 + Double.random(in: 0..<1) }
            let config = RTCDataChannelConfiguration(); config.isOrdered = true
            if let channel = pc.dataChannel(forLabel: crdtPreview ? fragmentChannel : "meshboard.v1", configuration: config) { peer.attach(channel) }
            peer.describe(offer: true)
        }
        return peer
    }
    fileprivate func valid(_ peer: ApplePeer) -> Bool { peers[peer.id] === peer }
    fileprivate func opened(_ peer: ApplePeer) {
        guard valid(peer) else { return }; diagnose("opened", peer)
        attempts[peer.id] = 0; events?.opened(peer: peer.id); peer.route()
    }
    fileprivate func received(_ peer: ApplePeer, _ raw: String) { if valid(peer) { events?.received(peer: peer.id, frame: raw) } }
    fileprivate func receivedBinary(_ peer: ApplePeer, _ data: Data) {
        if valid(peer) { events?.receivedBinary(peer: peer.id, base64: data.base64EncodedString()) }
    }
    fileprivate func route(_ peer: ApplePeer, _ relay: Bool) { if valid(peer) { events?.route(peer: peer.id, relayed: relay) } }
    fileprivate func failed(_ peer: ApplePeer, reason: String = "connection-failed") {
        guard valid(peer) else { return }; diagnose(reason, peer); drop(peer.id)
        if crdtPreview { desired.remove(peer.id); attempts.removeValue(forKey: peer.id) }
        let current = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            guard let self, self.generation == current, self.socket != nil else { return }
            if self.crdtPreview { if self.peers.count < 7 { self.announce() } }
            else { _ = self.ensure(peer.id) }
        }
    }
    private func drop(_ id: String) {
        guard let peer = peers.removeValue(forKey: id) else { return }
        diagnose("closed", peer)
        peer.channel?.delegate = nil; peer.channel?.close(); peer.pc.delegate = nil; peer.pc.close()
        events?.closed(peer: id)
    }
    func reject(peer: String) { desired.remove(peer); drop(peer) }
    func send(peer: String, frame: String) -> Bool { !crdtPreview && (peers[peer]?.send(Data(frame.utf8)) ?? false) }
    func peerId() -> String { selfID }
    func fragments(peer: String) -> Bool { peers[peer]?.channel?.label == fragmentChannel && peers[peer]?.remoteFragments == true }
    func sendBinary(peer: String, base64: String) -> Bool {
        guard crdtPreview, base64.utf8.count <= (maxYMessage + 2) / 3 * 4, let data = Data(base64Encoded: base64) else { return false }
        return peers[peer]?.send(data) ?? false
    }
    func previewAllowed(origin: String) -> Bool {
        #if DEBUG && targetEnvironment(simulator)
        return crdtPreview && ["localhost", "127.0.0.1"].contains(URL(string: origin)?.host ?? "")
        #else
        return false
        #endif
    }
    func qrPng(value: String) -> String {
        let filter = CIFilter.qrCodeGenerator(); filter.message = Data(value.utf8); filter.correctionLevel = "M"
        guard let output = filter.outputImage, let cg = CIContext().createCGImage(output.transformed(by: CGAffineTransform(scaleX: 6, y: 6)), from: output.extent.applying(CGAffineTransform(scaleX: 6, y: 6))) else { return "" }
        return UIImage(cgImage: cg).pngData()!.base64EncodedString()
    }
}

private final class ApplePeer: NSObject, RTCPeerConnectionDelegate, RTCDataChannelDelegate {
    let id: String
    let started = ProcessInfo.processInfo.systemUptime
    weak var owner: NativeAppleNetwork?
    var pc: RTCPeerConnection!
    var channel: RTCDataChannel?
    var open = false
    var token: Double?
    var remoteFragments = false
    private var remoteSet = false
    private var candidates: [RTCIceCandidate] = []
    private var queue: [Data] = []
    private var queueBytes = 0
    init(id: String, owner: NativeAppleNetwork) { self.id = id; self.owner = owner }
    private func main(_ block: @escaping (ApplePeer, NativeAppleNetwork) -> Void) {
        DispatchQueue.main.async { [weak self] in guard let self, let owner = self.owner, owner.valid(self) else { return }; block(self, owner) }
    }
    func remote(type: RTCSdpType, sdp: String) {
        owner?.diagnose(type == .offer ? "remote-offer" : "remote-answer", self)
        pc.setRemoteDescription(RTCSessionDescription(type: type, sdp: sdp)) { [weak self] error in self?.main { peer, owner in
            guard error == nil else { owner.failed(peer, reason: "remote-description-failed"); return }
            peer.remoteSet = true; for c in peer.candidates { peer.pc.add(c) }; peer.candidates.removeAll()
            if type == .offer { peer.describe(offer: false) }
        } }
    }
    func candidate(_ candidate: RTCIceCandidate) throws {
        if remoteSet { pc.add(candidate) } else { guard candidates.count < 128 else { throw NetworkError.invalid }; candidates.append(candidate) }
    }
    func describe(offer: Bool) {
        let completion: (RTCSessionDescription?, Error?) -> Void = { [weak self] description, error in self?.main { peer, owner in
            guard error == nil, let description else { owner.failed(peer, reason: "description-creation-failed"); return }
            peer.pc.setLocalDescription(description) { [weak peer] error in peer?.main { peer, owner in
                guard error == nil else { owner.failed(peer, reason: "local-description-failed"); return }
                owner.diagnose(offer ? "local-offer" : "local-answer", peer)
                let sdp = owner.crdtPreview ? description.sdp.replacingOccurrences(of: "\r\nm=", with: "\r\n" + fragmentSDP + "\r\nm=", range: description.sdp.range(of: "\r\nm=")) : description.sdp
                owner.signal(peer, ["description": ["type": offer ? "offer" : "answer", "sdp": sdp]])
            } }
        } }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        if offer { pc.offer(for: constraints, completionHandler: completion) } else { pc.answer(for: constraints, completionHandler: completion) }
    }
    func attach(_ channel: RTCDataChannel) {
        guard self.channel == nil, let owner else { channel.close(); return }
        guard (owner.crdtPreview ? (channel.isOrdered && channel.isReliable) : channel.label == "meshboard.v1") else { channel.close(); return }
        self.channel = channel; channel.delegate = self; dataChannelDidChangeState(channel)
    }
    func send(_ data: Data) -> Bool {
        guard open, data.count <= (owner?.crdtPreview == true ? maxYMessage : 16_384), queueBytes + data.count <= 8 * 1024 * 1024 else { return false }
        queue.append(data); queueBytes += data.count; flush(); return true
    }
    private func flush() {
        guard open, let channel else { return }
        while let first = queue.first, channel.bufferedAmount < 256 * 1024 {
            guard channel.sendData(RTCDataBuffer(data: first, isBinary: owner?.crdtPreview == true)) else { owner?.failed(self, reason: "data-send-failed"); return }
            queue.removeFirst(); queueBytes -= first.count
        }
    }
    func route() {
        pc.statistics { [weak self] report in self?.main { peer, owner in
            for transport in report.statistics.values where transport.type == "transport" {
                guard let id = transport.values["selectedCandidatePairId"] as? String, let pair = report.statistics[id] else { continue }
                let relay = ["localCandidateId", "remoteCandidateId"].contains { key in
                    guard let id = pair.values[key] as? String else { return false }
                    return report.statistics[id]?.values["candidateType"] as? String == "relay"
                }
                owner.route(peer, relay)
            }
        } }
    }
    func dataChannelDidChangeState(_ dataChannel: RTCDataChannel) { main { peer, owner in
        switch dataChannel.readyState {
        case .open: if !peer.open { peer.open = true; owner.opened(peer); peer.flush() }
        case .closed: owner.failed(peer, reason: "data-channel-closed")
        default: break
        }
    } }
    func dataChannel(_ dataChannel: RTCDataChannel, didReceiveMessageWith buffer: RTCDataBuffer) {
        let data = buffer.data; let binary = buffer.isBinary
        main { peer, owner in
            if owner.crdtPreview {
                guard binary, !data.isEmpty, data.count <= maxYMessage else { owner.reject(peer: peer.id); return }
                owner.receivedBinary(peer, data); return
            }
            guard !binary, data.count <= 16_384, let raw = String(data: data, encoding: .utf8) else { owner.reject(peer: peer.id); return }
            owner.received(peer, raw)
        }
    }
    func dataChannel(_ dataChannel: RTCDataChannel, didChangeBufferedAmount amount: UInt64) { main { peer, _ in peer.flush() } }
    func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) { main { peer, owner in
        owner.signal(peer, ["candidate": ["candidate": candidate.sdp, "sdpMid": candidate.sdpMid as Any? ?? NSNull(), "sdpMLineIndex": candidate.sdpMLineIndex]])
    } }
    func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) { main { peer, _ in peer.attach(dataChannel) } }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) { main { peer, owner in
        owner.diagnose("connection-state-\(newState.rawValue)", peer)
        if newState == .failed { owner.failed(peer) }; if newState == .connected { peer.route() }
    } }
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
}
