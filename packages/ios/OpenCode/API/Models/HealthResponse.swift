import Foundation

struct HealthResponse: Decodable {
    let healthy: Bool
    let version: String
}
