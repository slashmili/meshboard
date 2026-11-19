import Foundation
import CryptoKit
import Security

enum SessionCipherError: Error { case rejected }

/// The same bounded one-direction record format as FrameCipher/JvmFrameCipher.
final class SessionCipher {
    private var key: SymmetricKey?
    private let encrypt: Bool
    private let context: Data
    private var sequence: UInt64 = 0
    static func random32() throws -> Data {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!) }
        guard status == errSecSuccess else { throw SessionCipherError.rejected }
        return Data(bytes)
    }
    init(secret: Data, salt: Data, context: String, encrypt: Bool) throws {
        let info = Data(context.utf8)
        guard secret.count == 32, salt.count == 32, (1...4096).contains(info.count) else { throw SessionCipherError.rejected }
        self.context = info; self.encrypt = encrypt
        self.key = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: secret), salt: salt, info: info, outputByteCount: 32)
    }
    func close() { key = nil }
    func process(_ input: Data) throws -> Data {
        do {
            guard let key, sequence < 1_048_576 else { throw SessionCipherError.rejected }
            let counter = (0..<8).map { UInt8(truncatingIfNeeded: sequence >> ((7 - $0) * 8)) }
            let header = Data([77, 66, 69, 1] + counter)
            let nonce = try AES.GCM.Nonce(data: Data([0, 0, 0, 0] + counter))
            let aad = header + context
            let result: Data
            if encrypt {
                guard (1...16384).contains(input.count) else { throw SessionCipherError.rejected }
                let box = try AES.GCM.seal(input, using: key, nonce: nonce, authenticating: aad)
                result = header + box.ciphertext + box.tag
            } else {
                guard (29...16412).contains(input.count), input.prefix(12) == header else { throw SessionCipherError.rejected }
                let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: input.dropFirst(12).dropLast(16), tag: input.suffix(16))
                result = try AES.GCM.open(box, using: key, authenticating: aad)
            }
            sequence += 1
            return result
        } catch { close(); throw SessionCipherError.rejected }
    }
}
