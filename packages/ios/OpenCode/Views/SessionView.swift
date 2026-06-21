import SwiftUI

struct SessionView: View {
    let session: Session
    var server: ServerConnection
    @State private var messages: [MessageWithParts] = []
    @State private var loading = true
    @State private var error: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Loading messages...")
            } else if let error {
                ContentUnavailableView("Error", systemImage: "exclamationmark.triangle", description: Text(error))
            } else if messages.isEmpty {
                ContentUnavailableView("No Messages", systemImage: "bubble.left", description: Text("This session has no messages yet"))
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 16) {
                            ForEach(messages) { message in
                                MessageRow(message: message)
                                    .id(message.id)
                            }
                        }
                        .padding()
                    }
                    .onAppear {
                        if let last = messages.last {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                }
            }
        }
        .navigationTitle(session.title.isEmpty ? "Untitled" : session.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
    }

    private func load() async {
        do {
            messages = try await server.messages(directory: session.directory, sessionID: session.id)
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }
}
