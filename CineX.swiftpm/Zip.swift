import Compression
import Foundation

/// Minimal zip support for .rmsc packages (see docs/rmsc-format.md). Writing stores files uncompressed (JPEGs and the
/// .glb are already compressed); reading handles stored and deflated entries, which is what Android's ZipOutputStream
/// and desktop zip tools write. No zip64, encryption or multi-disk: packages stay well under 4 GB.
enum Zip {
    enum Failure: LocalizedError {
        case notZip, unsupported(String), corrupt(String)
        var errorDescription: String? {
            switch self {
            case .notZip: return "This file isn't a zip package."
            case .unsupported(let what): return "This package uses a zip feature the app can't read (\(what))."
            case .corrupt(let what): return "This package is damaged (\(what))."
            }
        }
    }

    // MARK: Writing

    /// Streams entries into a zip file on disk, one at a time, so memory stays low.
    final class Writer {
        private let handle: FileHandle
        private var offset: UInt32 = 0
        private var central = Data()
        private var count: UInt16 = 0

        init(url: URL) throws {
            FileManager.default.createFile(atPath: url.path, contents: nil)
            handle = try FileHandle(forWritingTo: url)
        }

        func add(_ path: String, data: Data) throws {
            let name = Data(path.utf8)
            let crc = Zip.crc32(data)
            let size = UInt32(data.count)
            var local = Data()
            local.u32(0x0403_4B50); local.u16(20); local.u16(0x0800) /* UTF-8 names */; local.u16(0) /* stored */
            local.u16(0); local.u16(0x21) /* 1980-01-01 */; local.u32(crc); local.u32(size); local.u32(size)
            local.u16(UInt16(name.count)); local.u16(0)
            local.append(name)
            try handle.write(contentsOf: local)
            try handle.write(contentsOf: data)

            central.u32(0x0201_4B50); central.u16(20); central.u16(20); central.u16(0x0800); central.u16(0)
            central.u16(0); central.u16(0x21); central.u32(crc); central.u32(size); central.u32(size)
            central.u16(UInt16(name.count)); central.u16(0); central.u16(0); central.u16(0); central.u16(0)
            central.u32(0); central.u32(offset)
            central.append(name)
            offset += UInt32(local.count) + size
            count += 1
        }

        func add(_ path: String, file: URL) throws {
            try add(path, data: Data(contentsOf: file, options: .mappedIfSafe))
        }

        func finish() throws {
            var end = Data()
            end.u32(0x0605_4B50); end.u16(0); end.u16(0); end.u16(count); end.u16(count)
            end.u32(UInt32(central.count)); end.u32(offset); end.u16(0)
            try handle.write(contentsOf: central)
            try handle.write(contentsOf: end)
            try handle.close()
        }
    }

    // MARK: Reading

    /// The entries of a zip file in memory (mapped), read on demand.
    struct Reader {
        struct Entry {
            let path: String
            let method: UInt16
            let compressedSize: Int
            let size: Int
            let localOffset: Int
        }

        let data: Data
        let entries: [String: Entry]

        init(url: URL) throws {
            data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard data.count >= 22 else { throw Failure.notZip }
            // End of central directory: search backwards over a possible comment.
            var eocd = -1
            var i = data.count - 22
            while i >= max(0, data.count - 22 - 65_535) {
                if data.u32(at: i) == 0x0605_4B50 { eocd = i; break }
                i -= 1
            }
            guard eocd >= 0 else { throw Failure.notZip }
            let total = Int(data.u16(at: eocd + 10))
            var p = Int(data.u32(at: eocd + 16))
            guard total != 0xFFFF, p != 0xFFFF_FFFF else { throw Failure.unsupported("zip64") }
            var entries: [String: Entry] = [:]
            for _ in 0..<total {
                guard p + 46 <= data.count, data.u32(at: p) == 0x0201_4B50 else { throw Failure.corrupt("central directory") }
                let method = data.u16(at: p + 10)
                let compressed = Int(data.u32(at: p + 20)), size = Int(data.u32(at: p + 24))
                let nameLength = Int(data.u16(at: p + 28)), extra = Int(data.u16(at: p + 30)), comment = Int(data.u16(at: p + 32))
                let local = Int(data.u32(at: p + 42))
                guard p + 46 + nameLength <= data.count else { throw Failure.corrupt("name") }
                let path = String(decoding: data[(data.startIndex + p + 46)..<(data.startIndex + p + 46 + nameLength)], as: UTF8.self)
                if !path.hasSuffix("/") {
                    entries[path] = Entry(path: path, method: method, compressedSize: compressed, size: size, localOffset: local)
                }
                p += 46 + nameLength + extra + comment
            }
            self.entries = entries
        }

        func contains(_ path: String) -> Bool { entries[path] != nil }

        /// An entry's contents, or nil if it isn't in the package.
        func read(_ path: String) throws -> Data? {
            guard let e = entries[path] else { return nil }
            let p = e.localOffset
            guard p + 30 <= data.count, data.u32(at: p) == 0x0403_4B50 else { throw Failure.corrupt(path) }
            let start = p + 30 + Int(data.u16(at: p + 26)) + Int(data.u16(at: p + 28))
            guard start + e.compressedSize <= data.count else { throw Failure.corrupt(path) }
            let raw = data[(data.startIndex + start)..<(data.startIndex + start + e.compressedSize)]
            switch e.method {
            case 0:
                return Data(raw)
            case 8:
                guard e.size > 0 else { return Data() }
                var out = Data(count: e.size)
                let written = out.withUnsafeMutableBytes { dst in
                    raw.withUnsafeBytes { src in
                        compression_decode_buffer(dst.bindMemory(to: UInt8.self).baseAddress!, e.size,
                                                  src.bindMemory(to: UInt8.self).baseAddress!, raw.count,
                                                  nil, COMPRESSION_ZLIB)
                    }
                }
                guard written == e.size else { throw Failure.corrupt(path) }
                return out
            default:
                throw Failure.unsupported("compression method \(e.method)")
            }
        }
    }

    // MARK: CRC-32

    private static let table: [UInt32] = (0..<256).map { n -> UInt32 in
        var c = UInt32(n)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        data.withUnsafeBytes { raw in
            for b in raw.bindMemory(to: UInt8.self) { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        }
        return c ^ 0xFFFF_FFFF
    }
}

private extension Data {
    mutating func u16(_ v: UInt16) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
    mutating func u32(_ v: UInt32) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
    func u16(at i: Int) -> UInt16 { withUnsafeBytes { $0.loadUnaligned(fromByteOffset: i, as: UInt16.self).littleEndian } }
    func u32(at i: Int) -> UInt32 { withUnsafeBytes { $0.loadUnaligned(fromByteOffset: i, as: UInt32.self).littleEndian } }
}
