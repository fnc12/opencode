import SwiftUI

struct MessageRow: View {
    let message: MessageWithParts

    var body: some View {
        switch message.info {
        case .user:
            UserMessageRow(parts: message.parts)
        case .assistant(let info):
            AssistantMessageRow(info: info, parts: message.parts)
        }
    }
}
