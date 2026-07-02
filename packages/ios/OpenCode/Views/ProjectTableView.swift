import SwiftUI
import UIKit

/// A UITableView-backed project list: SwiftUI rows via `UIHostingConfiguration`
/// with a diffable data source and pull-to-refresh. Same "SwiftUI look, UIKit
/// underneath" approach as the message + session lists.
struct ProjectTableView: UIViewRepresentable {
    let projects: [Project]
    let onSelect: (Project) -> Void
    let onRefresh: () async -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITableView {
        let table = UITableView(frame: .zero, style: .plain)
        table.delegate = context.coordinator
        table.rowHeight = UITableView.automaticDimension
        table.estimatedRowHeight = 56
        table.register(UITableViewCell.self, forCellReuseIdentifier: "cell")
        let refresh = UIRefreshControl()
        refresh.addTarget(context.coordinator,
                          action: #selector(Coordinator.handleRefresh(_:)),
                          for: .valueChanged)
        table.refreshControl = refresh
        context.coordinator.makeDataSource(table)
        context.coordinator.apply(projects, animated: false)
        return table
    }

    func updateUIView(_ table: UITableView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.apply(projects, animated: true)
    }

    @MainActor final class Coordinator: NSObject, UITableViewDelegate {
        var parent: ProjectTableView
        private var dataSource: UITableViewDiffableDataSource<Int, Project>!
        private var didLoad = false

        init(_ parent: ProjectTableView) { self.parent = parent }

        func makeDataSource(_ table: UITableView) {
            dataSource = UITableViewDiffableDataSource<Int, Project>(tableView: table) { tv, indexPath, project in
                let cell = tv.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
                cell.contentConfiguration = UIHostingConfiguration { ProjectRow(project: project) }
                cell.accessoryType = .disclosureIndicator
                return cell
            }
            dataSource.defaultRowAnimation = .fade
        }

        func apply(_ projects: [Project], animated: Bool) {
            var snapshot = NSDiffableDataSourceSnapshot<Int, Project>()
            snapshot.appendSections([0])
            snapshot.appendItems(projects, toSection: 0)
            dataSource.apply(snapshot, animatingDifferences: animated && didLoad)
            didLoad = true
        }

        @objc func handleRefresh(_ control: UIRefreshControl) {
            Task { await parent.onRefresh(); control.endRefreshing() }
        }

        func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
            tableView.deselectRow(at: indexPath, animated: true)
            guard let project = dataSource.itemIdentifier(for: indexPath) else { return }
            parent.onSelect(project)
        }
    }
}
