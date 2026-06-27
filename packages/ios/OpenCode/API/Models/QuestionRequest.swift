import Foundation

/// A pending question from the agent (`question.v2.asked` event and `GET
/// /question`). The agent is blocked until the user answers; each question can
/// be single- or multi-select, and the reply is an array of selected labels per
/// question.
struct QuestionRequest: Decodable, Identifiable, Equatable {
    let id: String
    let sessionID: String
    let questions: [QuestionItem]

    private enum CodingKeys: String, CodingKey { case id, sessionID, questions }
}

struct QuestionItem: Decodable, Equatable, Identifiable {
    let question: String
    let header: String
    let options: [QuestionOption]
    let multiple: Bool?
    let custom: Bool?

    var allowsMultiple: Bool { multiple == true }
    /// Stable id for ForEach (header+question are effectively unique per request).
    var id: String { header + "|" + question }
}

struct QuestionOption: Decodable, Equatable, Identifiable {
    let label: String
    let description: String

    var id: String { label }
}
