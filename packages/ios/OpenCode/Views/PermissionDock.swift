import SwiftUI

/// A pending permission request shown above the composer. The agent is blocked
/// until the user answers — approve once, always, or reject — which is the core
/// reason to control a session from the phone.
struct PermissionDock: View {
    let request: PermissionRequest
    /// Called with the reply: "once", "always", or "reject".
    let onReply: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Permission needed", systemImage: "hand.raised.fill")
                .font(.caption.bold())
                .foregroundStyle(.orange)

            Text(request.summary)
                .font(.callout)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 10) {
                Button(role: .destructive) { onReply("reject") } label: {
                    Text("Reject").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("permission.reject")

                Button { onReply("always") } label: {
                    Text("Always").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("permission.always")

                Button { onReply("once") } label: {
                    Text("Allow").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("permission.allow")
            }
        }
        .padding(12)
        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.orange.opacity(0.35)))
        .padding(.horizontal, 12)
        .padding(.top, 8)
    }
}
