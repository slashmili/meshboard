import Foundation

@main
struct SessionCipherTests {
    static func main() throws {
        let secret = Data(repeating: 7, count: 32), salt = Data(repeating: 9, count: 32)
        let context = "meshboard/phase3/vector/sender->receiver", clear = Data([0, 0, 1, 0])
        func make(_ encrypt: Bool, _ info: String = "meshboard/phase3/vector/sender->receiver", _ key: Data? = nil, _ nonce: Data? = nil) throws -> SessionCipher {
            try SessionCipher(secret: key ?? secret, salt: nonce ?? salt, context: info, encrypt: encrypt)
        }
        func rejects(_ operation: () throws -> Void) {
            do { try operation(); fatalError("Expected rejection") } catch { /* Expected authentication/validation failure. */ }
        }
        let sender = try make(true), receiver = try make(false)
        let vectors = ["4d424501000000000000000001b1593506cf39475764eccdb4115385127f7844", "4d42450100000000000000017a1534afd35753a2733f173cc06ed3eecbc319e4"]
        for vector in vectors {
            let frame = try sender.process(clear)
            precondition(frame.map { String(format: "%02x", $0) }.joined() == vector)
            let decoded = try receiver.process(frame); precondition(decoded == clear)
        }
        let frame = try make(true).process(clear)
        for index in frame.indices {
            var changed = frame; changed[index] ^= 1
            let receiver = try make(false)
            rejects { _ = try receiver.process(changed) }
            rejects { _ = try receiver.process(frame) }
        }
        for receiver in [try make(false, context + "/other-room"), try make(false, context, Data(repeating: 0, count: 32)), try make(false, context, nil, Data(repeating: 0, count: 32))] {
            rejects { _ = try receiver.process(frame) }
        }
        let replay = try make(false); _ = try replay.process(frame)
        rejects { _ = try replay.process(frame) }
        rejects { _ = try make(false).process(clear) }
        rejects { _ = try make(false).process(frame.dropLast()) }
        rejects { _ = try make(true).process(Data()) }
        rejects { _ = try make(true).process(Data(repeating: 0, count: 16385)) }
        let maximum = Data(repeating: 42, count: 16384)
        let roundTrip = try make(false).process(make(true).process(maximum)); precondition(roundTrip == maximum)
        let closed = try make(true); closed.close(); rejects { _ = try closed.process(clear) }
        rejects { _ = try make(true, "") }
        rejects { _ = try make(true, String(repeating: "x", count: 4097)) }
        let keyA = try SessionCipher.random32(), keyB = try SessionCipher.random32()
        precondition(keyA.count == 32 && keyB.count == 32 && keyA != keyB)
        let exhausted = try make(true)
        for _ in 0..<1_048_576 { _ = try exhausted.process(Data([0])) }
        rejects { _ = try exhausted.process(clear) }
        print("Apple CryptoKit vectors, tampering, key/context binding, replay, bounds and disposal passed")
    }
}
