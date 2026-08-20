package studio.eugenezakharov.opencode.ui.screens

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import studio.eugenezakharov.opencode.api.models.Session

/**
 * RecyclerView adapter for the session list — native views under the hood
 * (same approach as the message list), long-press shows a Rename/Delete popup.
 */
class SessionListAdapter(
    private val primaryColor: Int,
    private val secondaryColor: Int,
    private val addColor: Int,
    private val delColor: Int,
    private val onClick: (Session) -> Unit,
    private val onRename: (Session) -> Unit,
    private val onDelete: (Session) -> Unit,
) : RecyclerView.Adapter<SessionListAdapter.VH>() {

    private val items = mutableListOf<Session>()
    private var busy: Set<String> = emptySet()

    private companion object {
        // Green "Working…" label, matching the assistant role green.
        val WORKING_COLOR = 0xFF1F9550.toInt()
    }

    fun submit(sessions: List<Session>, busyIds: Set<String> = emptySet()) {
        val oldBusy = busy
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = sessions.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].id == sessions[n].id
            // Rebind when the row's content OR its busy state changes.
            override fun areContentsTheSame(o: Int, n: Int) =
                items[o] == sessions[n] &&
                    oldBusy.contains(items[o].id) == busyIds.contains(sessions[n].id)
        })
        items.clear(); items.addAll(sessions)
        busy = busyIds
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
        val title = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(primaryColor)
            maxLines = 2
        }
        // Meta row: a small spinner (shown only while the session is generating)
        // followed by the summary/time text.
        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(3), 0, 0)
        }
        val spinner = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
            val s = px(14)
            layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = px(6) }
            visibility = View.GONE
        }
        val meta = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(spinner)
        metaRow.addView(meta)
        root.addView(title)
        root.addView(metaRow)
        return VH(root, title, meta, spinner)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], busy.contains(items[position].id))

    inner class VH(
        itemView: LinearLayout,
        private val title: TextView,
        private val meta: TextView,
        private val spinner: ProgressBar,
    ) : RecyclerView.ViewHolder(itemView) {
        private var current: Session? = null

        init {
            itemView.setOnClickListener { current?.let(onClick) }
            itemView.setOnLongClickListener { v ->
                val s = current ?: return@setOnLongClickListener false
                PopupMenu(v.context, v).apply {
                    menu.add("Rename"); menu.add("Delete")
                    setOnMenuItemClickListener { item ->
                        when (item.title) {
                            "Rename" -> onRename(s)
                            "Delete" -> onDelete(s)
                        }
                        true
                    }
                }.show()
                true
            }
        }

        fun bind(session: Session, isBusy: Boolean) {
            current = session
            title.text = session.title.ifEmpty { "Untitled" }

            // While generating, show a live spinner + "Working…" in place of the
            // static summary/time (matches iOS).
            if (isBusy) {
                spinner.visibility = View.VISIBLE
                val sb = android.text.SpannableStringBuilder()
                val start = sb.length
                sb.append("Working…")
                sb.setSpan(android.text.style.ForegroundColorSpan(WORKING_COLOR), start, sb.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                meta.text = sb
                return
            }

            spinner.visibility = View.GONE
            val sb = android.text.SpannableStringBuilder()
            fun span(text: String, color: Int) {
                val start = sb.length
                sb.append(text)
                sb.setSpan(android.text.style.ForegroundColorSpan(color), start, sb.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            session.summary?.let { s ->
                if (s.additions > 0) span("+${s.additions} ", addColor)
                if (s.deletions > 0) span("-${s.deletions} ", delColor)
                if (s.files > 0) span("${s.files} files", secondaryColor)
            }
            if (sb.isNotEmpty()) span("   •   ", secondaryColor)
            span(relativeTime(session.time.updated), secondaryColor)
            meta.text = sb
        }
    }
}
