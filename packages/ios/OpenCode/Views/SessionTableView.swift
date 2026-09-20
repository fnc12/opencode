import SwiftUI
import UIKit

/// A UITableView-backed session list: SwiftUI rows hosted via `UIHostingConfiguration`
/// with UIKit cell reuse, a diffable data source, and native swipe / context actions.
/// Keeps the modern SwiftUI look while avoiding SwiftUI `List`'s per-row dynamic
/// bindings — the same "SwiftUI on top, UIKit underneath" approach as the message list.
struct SessionTableView: UIViewRepresentable {
    let sessions: [Session]
    /// Session ids currently generating — rows show a "working" indicator.
    var busyIDs: Set<String> = []
    let onSelect: (Session) -> Void
    let onDelete: (Session) -> Void
    let onRename: (Session) -> Void
    let onRefresh: () async -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITableView {
        let table = UITableView(frame: .zero, style: .plain)
        table.delegate = context.coordinator
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 64
        table.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator,
                          action: #selector(Coordinator.handleRefresh(_:)),
                          for: .valueChanged)
        table.refreshControl = refresh
        context.coordinator.makeDataSource(table)
        context.coordinator.apply(sessions, busyIDs: busyIDs, animated: false)
        return table
    }

    func updateUIView(_ table: UITableView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.apply(sessions, busyIDs: busyIDs, animated: true)
    }

    @MainActor final class Coordinator: NSObject, UITableViewDelegate {
        var parent: SessionTableView
        private var dataSource: UITableViewDiffableDataSource<Int, Session>!
        private var didLoad = false
        /// Busy ids from the last apply, so we can reconfigure only the rows whose
        /// working state actually flipped (Session equality is id-only, so a
        /// snapshot alone never re-renders a row).
        private var lastBusyIDs: Set<String> = []

        init(_ parent: SessionTableView) { self.parent = parent }

        func makeDataSource(_ table: UITableView) {
            dataSource = UITableViewDiffableDataSource<Int, Session>(tableView: table) { [weak self] tv, indexPath, session in
                let cell = tv.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
                let busy = self?.parent.busyIDs.contains(session.id) ?? false
                cell.contentConfiguration = UIHostingConfiguration { SessionRow(session: session, isBusy: busy) }
                cell.accessoryType = .disclosureIndicator
                return cell
            }
            dataSource.defaultRowAnimation = .fade
        }

        func apply(_ sessions: [Session], busyIDs: Set<String>, animated: Bool) {
            var snapshot = NSDiffableDataSourceSnapshot<Int, Session>()
            snapshot.appendSections([0])
            snapshot.appendItems(sessions, toSection: 0)
            // Rows whose busy state changed need to re-render their hosted content.
            if didLoad {
                let flipped = sessions.filter { busyIDs.contains($0.id) != lastBusyIDs.contains($0.id) }
                if !flipped.isEmpty { snapshot.reconfigureItems(flipped) }
            }
            lastBusyIDs = busyIDs
            dataSource.apply(snapshot, animatingDifferences: animated && didLoad)
            didLoad = true
        }

        @objc func handleRefresh(_ control: UIRefreshControl) {
            Task { await parent.onRefresh(); control.endRefreshing() }
        }

        func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
            tableView.deselectRow(at: indexPath, animated: true)
            guard let session = dataSource.itemIdentifier(for: indexPath) else { return }
            parent.onSelect(session)
        }

        func tableView(_ tableView: UITableView,
                       trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath) -> UISwipeActionsConfiguration? {
            guard let session = dataSource.itemIdentifier(for: indexPath) else { return nil }
            let onDelete = parent.onDelete, onRename = parent.onRename
            let delete = UIContextualAction(style: .destructive, title: "Delete") { _, _, done in
                onDelete(session); done(true)
            }
            delete.image = UIImage(systemName: "trash")
            let rename = UIContextualAction(style: .normal, title: "Rename") { _, _, done in
                onRename(session); done(true)
            }
            rename.image = UIImage(systemName: "pencil")
            rename.backgroundColor = .systemBlue
            return UISwipeActionsConfiguration(actions: [delete, rename])
        }

        func tableView(_ tableView: UITableView,
                       contextMenuConfigurationForRowAt indexPath: IndexPath,
                       point: CGPoint) -> UIContextMenuConfiguration? {
            guard let session = dataSource.itemIdentifier(for: indexPath) else { return nil }
            let onDelete = parent.onDelete, onRename = parent.onRename
            return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { _ in
                UIMenu(children: [
                    UIAction(title: "Rename", image: UIImage(systemName: "pencil")) { _ in onRename(session) },
                    UIAction(title: "Delete", image: UIImage(systemName: "trash"),
                             attributes: .destructive) { _ in onDelete(session) },
                ])
            }
        }
    }
}
