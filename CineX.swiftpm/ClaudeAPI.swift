import Foundation
import Security

/// The user's Claude API key, typed into the app and kept in the iOS Keychain (handoff: never in code, chat or git).
enum ClaudeKeyStore {
    private static let service = "CineX.claude-api-key"

    static var key: String? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: service,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data, let key = String(data: data, encoding: .utf8), !key.isEmpty else { return nil }
        return key
    }

    static func save(_ key: String) {
        delete()
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let item: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                   kSecAttrService as String: service,
                                   kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                                   kSecValueData as String: Data(trimmed.utf8)]
        SecItemAdd(item as CFDictionary, nil)
    }

    static func delete() {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
    }
}

/// Minimal Claude Messages API client over HTTPS (Swift has no official Anthropic SDK).
enum ClaudeAPI {
    static let model = "claude-opus-5-5"

    enum Failure: LocalizedError {
        case noKey
        case badKey
        case rateLimited
        case declined
        case http(Int, String)
        case badReply(String)

        var errorDescription: String? {
            switch self {
            case .noKey: return "Add your Claude API key first."
            case .badKey: return "Claude didn't accept the API key. Check it and try again."
            case .rateLimited: return "Claude is busy (rate limited). Wait a minute and try again."
            case .declined: return "Claude declined to look at these photos."
            case .http(let code, let message): return "Claude request failed (\(code)): \(message)"
            case .badReply(let why): return "Claude's reply couldn't be read: \(why)"
            }
        }
    }

    /// A user message of labelled JPEG photos followed by instructions; the reply is JSON matching `schema`
    /// (structured outputs), returned as raw bytes for the caller to decode.
    static func askAboutPhotos(_ photos: [Data], instructions: String, schema: [String: Any],
                               effort: String = "low", maxTokens: Int = 8000) async throws -> Data {
        guard let key = ClaudeKeyStore.key else { throw Failure.noKey }
        var content: [[String: Any]] = []
        for (i, jpeg) in photos.enumerated() {
            content.append(["type": "text", "text": "Photo \(i):"])
            content.append(["type": "image",
                            "source": ["type": "base64", "media_type": "image/jpeg",
                                       "data": jpeg.base64EncodedString()]])
        }
        content.append(["type": "text", "text": instructions])
        var body: [String: Any] = [
            "model": model,
            "max_tokens": maxTokens,
            "output_config": ["effort": effort, "format": ["type": "json_schema", "schema": schema]],
            // If a safety check declines, the API re-runs the request on a suitable fallback model by itself.
            "fallbacks": "default",
            "messages": [["role": "user", "content": content]],
        ]

        var request = URLRequest(url: URL(string: "https://api.anthropic.com/v1/messages")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 180
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.setValue(key, forHTTPHeaderField: "x-api-key")
        request.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")
        request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        var attempt = 0
        var triedWithoutFallbacks = false
        while true {
            attempt += 1
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            switch status {
            case 200:
                return try replyJSON(data)
            case 401, 403:
                throw Failure.badKey
            case 429, 500...599 where attempt < 3:
                // Retry after the server's hint, or a short backoff.
                let wait = Double((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "retry-after") ?? "") ?? Double(attempt * 5)
                try await Task.sleep(nanoseconds: UInt64(min(30, wait) * 1_000_000_000))
            case 429:
                throw Failure.rateLimited
            case 400 where !triedWithoutFallbacks && errorMessage(data).lowercased().contains("fallback"):
                // The refusal fallback isn't accepted with this request: send it once more without it.
                triedWithoutFallbacks = true
                body["fallbacks"] = nil
                request.setValue(nil, forHTTPHeaderField: "anthropic-beta")
                request.httpBody = try JSONSerialization.data(withJSONObject: body)
            default:
                throw Failure.http(status, errorMessage(data))
            }
        }
    }

    private static func replyJSON(_ data: Data) throws -> Data {
        guard let reply = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure.badReply("not a JSON object")
        }
        let stop = reply["stop_reason"] as? String
        if stop == "refusal" { throw Failure.declined }
        if stop == "max_tokens" { throw Failure.badReply("it was cut off (too long)") }
        let blocks = reply["content"] as? [[String: Any]] ?? []
        let text = blocks.filter { $0["type"] as? String == "text" }.compactMap { $0["text"] as? String }.joined()
        guard let json = text.data(using: .utf8), !text.isEmpty else { throw Failure.badReply("no text") }
        return json
    }

    private static func errorMessage(_ data: Data) -> String {
        if let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let e = o["error"] as? [String: Any], let m = e["message"] as? String { return m }
        return String(data: data.prefix(300), encoding: .utf8) ?? "unknown error"
    }
}
