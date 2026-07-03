import SwiftUI

/// A pocket terminal for the session: type a shell command, it runs on the
/// server (`POST /session/:id/shell`) and the output prints below. Each run is
/// also recorded in the session, so it shows up in the transcript too.
struct ShellView: View {
    let server: ServerConnection
    let session: Session
    @Environment(\.dismiss) private var dismiss

    @State private var command = ""
    @State private var log: [ShellEntry] = []
    @State private var running = false
    @FocusState private var fieldFocused: Bool

    struct ShellEntry: Identifiable {
        let id = UUID()
        let command: String
        let output: String
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 12) {
                            if log.isEmpty && !running {
                                Text("Run a shell command in \(session.directory).")
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            ForEach(log) { entry in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text("$ \(entry.command)")
                                        .font(.system(.footnote, design: .monospaced))
                                        .foregroundStyle(.green)
                                    Text(entry.output)
                                        .font(.system(.caption, design: .monospaced))
                                        .foregroundStyle(.primary)
                                        .textSelection(.enabled)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(entry.id)
                            }
                            if running {
                                HStack(spacing: 6) { ProgressView(); Text("running…").font(.caption).foregroundStyle(.secondary) }
                                    .id("running")
                            }
                        }
                        .padding()
                    }
                    .onChange(of: log.count) { _, _ in
                        if let last = log.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                    }
                }
                Divider()
                HStack(spacing: 8) {
                    Text("$").font(.system(.body, design: .monospaced)).foregroundStyle(.secondary)
                    TextField("command", text: $command)
                        .font(.system(.body, design: .monospaced))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .focused($fieldFocused)
                        .onSubmit { run() }
                        .accessibilityIdentifier("shell.field")
                    Button { run() } label: { Image(systemName: "arrow.up.circle.fill").font(.title2) }
                        .disabled(command.trimmingCharacters(in: .whitespaces).isEmpty || running)
                        .accessibilityIdentifier("shell.run")
                }
                .padding(12)
            }
            .navigationTitle("Shell")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } } }
            .onAppear { fieldFocused = true }
        }
    }

    private func run() {
        let cmd = command.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cmd.isEmpty, !running else { return }
        command = ""
        running = true
        Task {
            let output: String
            do {
                output = try await server.runShell(directory: session.directory, sessionID: session.id, command: cmd)
            } catch {
                output = "error: \(error.localizedDescription)"
            }
            log.append(ShellEntry(command: cmd, output: output))
            running = false
            fieldFocused = true
        }
    }
}
