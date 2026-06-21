import Foundation

enum ClientError: LocalizedError {
    case invalidURL
    case http(Int)

    var errorDescription: String? {
        switch self {
        case .invalidURL: "Invalid server URL"
        case .http(let code): "HTTP error: \(code)"
        }
    }
}
