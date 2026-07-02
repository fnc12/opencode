package studio.eugenezakharov.opencode.ui.screens

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import studio.eugenezakharov.opencode.api.models.Project

/** RecyclerView adapter for the project list — native views under the hood. */
class ProjectListAdapter(
    private val primaryColor: Int,
    private val secondaryColor: Int,
    private val onClick: (Project) -> Unit,
) : RecyclerView.Adapter<ProjectListAdapter.VH>() {

    private val items = mutableListOf<Project>()

    fun submit(projects: List<Project>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = projects.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].id == projects[n].id
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == projects[n]
        })
        items.clear(); items.addAll(projects)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            )
            setPadding(px(16), px(12), px(16), px(12))
            isClickable = true
            val tv = TypedValue()
            ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        val name = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
            maxLines = 1
        }
        val path = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(secondaryColor)
            maxLines = 1
        }
        root.addView(name)
        root.addView(path)
        return VH(root, name, path)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(
        itemView: LinearLayout,
        private val name: TextView,
        private val path: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        private var current: Project? = null

        init { itemView.setOnClickListener { current?.let(onClick) } }

        fun bind(project: Project) {
            current = project
            name.text = project.name ?: project.worktree.substringAfterLast('/').ifEmpty { "Unknown" }
            path.text = shortenProjectPath(project.worktree)
        }
    }
}

/** Collapses "/Users/<name>" to "~". Shared with the project list screen. */
fun shortenProjectPath(path: String): String {
    val marker = "/Users/"
    val idx = path.indexOf(marker)
    if (idx < 0) return path
    val afterUsers = path.substring(idx + marker.length)
    val slash = afterUsers.indexOf('/')
    return if (slash >= 0) "~" + afterUsers.substring(slash) else path
}
