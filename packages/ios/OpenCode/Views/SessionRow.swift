import SwiftUI

struct SessionRow: View {
    let session: Session

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(session.title.isEmpty ? "Untitled" : session.title)
                .font(.system(.body, weight: .medium))
                .lineLimit(2)

            HStack(spacing: 12) {
                if let summary = session.summary {
                    HStack(spacing: 4) {
                        if summary.additions > 0 {
                            Text("+\(summary.additions)")
                                .foregroundStyle(.green)
                        }
                        if summary.deletions > 0 {
                            Text("-\(summary.deletions)")
                                .foregroundStyle(.red)
                        }
                        if summary.files > 0 {
                            Text("\(summary.files) files")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .font(.caption)
                }

                Spacer()

                Text(session.time.updatedDate, style: .relative)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}
