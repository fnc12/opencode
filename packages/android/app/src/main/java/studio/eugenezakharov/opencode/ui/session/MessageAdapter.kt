package studio.eugenezakharov.opencode.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Toast
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.recyclerview.widget.RecyclerView
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent

/** A message pre-rendered to a Spanned body plus role styling, built once per content change. */
data class RenderedMessage(
    val id: String,
    val roleText: String,
    val roleColor: Int,
    val metaText: String?,
    val body: Spanned,
    val bubbleColor: Int,
    val signature: Int,
)

/**
 * Hand-tuned conversation list backed by a [RecyclerView] (per the product
 * decision — not a Compose LazyColumn). It diffs by id and, crucially, calls
 * `notifyItemChanged(pos)` for only the rows whose content signature changed —
 * so a streaming text delta rebinds a single row, never the whole list.
 * Mirrors the iOS `MessageListView` coordinator.
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    /** A row holds the raw message + a cheap signature; the heavy Spanned body is
     *  rendered lazily in onBind (only for visible rows), so loading a 600-message
     *  session doesn't build 600 Spannables on the main thread (that ANR'd). */
    private data class Row(val id: String, val message: MessageWithParts, val signature: Int)

    private val items = mutableListOf<Row>()
    // Bounded cache of rendered bodies keyed by "id:signature" — a re-bind of an
    // unchanged row reuses its Spanned instead of re-rendering markdown.
    private val renderCache = object : android.util.LruCache<String, RenderedMessage>(300) {}

    /** Invoked with a message id when the user picks "Revert to here". */
    var onRevert: ((String) -> Unit)? = null

    /** Replaces the list, issuing minimal notifications. Returns true if anything changed. */
    fun submit(messages: List<MessageWithParts>): Boolean {
        val next = messages.map { Row(it.id, it, signature(it)) }
        val oldIds = items.map { it.id }
        val newIds = next.map { it.id }

        if (oldIds == newIds) {
            // Same set + order: only rebind rows whose signature changed.
            var any = false
            for (i in next.indices) {
                if (items[i].signature != next[i].signature) {
                    items[i] = next[i]
                    notifyItemChanged(i)
                    any = true
                }
            }
            return any
        }

        // Structure changed (insert/remove/reorder): replace and full-notify.
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
        return true
    }

    private fun rendered(row: Row): RenderedMessage {
        val key = "${row.id}:${row.signature}"
        return renderCache.get(key) ?: render(row.message).also { renderCache.put(key, it) }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = roundedBackground(dp(12))
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val role = TextView(context).apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val meta = TextView(context).apply {
            textSize = 11f
            gravity = Gravity.END
            setTextColor(0xFF888888.toInt())
        }
        header.addView(role)
        header.addView(meta)

        val body = TextView(context).apply {
            textSize = 15f
            // Dark-on-light to match the Compose surface (the host activity uses a
            // light Material3 theme regardless of the framework XML theme).
            setTextColor(if (isNightMode(context)) 0xFFE6E6E6.toInt() else 0xFF1C1C1E.toInt())
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        bubble.addView(header)
        bubble.addView(body)
        outer.addView(bubble)
        return MessageViewHolder(outer, bubble, role, meta, body) { onRevert }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(rendered(items[position]))
    }

    class MessageViewHolder(
        itemView: LinearLayout,
        private val bubble: LinearLayout,
        private val role: TextView,
        private val meta: TextView,
        private val body: TextView,
        private val revertProvider: () -> ((String) -> Unit)?,
    ) : RecyclerView.ViewHolder(itemView) {
        private var current: RenderedMessage? = null

        init {
            // Long-press a message → Copy / Revert to here.
            itemView.setOnLongClickListener { v ->
                val msg = current ?: return@setOnLongClickListener false
                val text = msg.body.toString().takeIf { it.isNotBlank() }
                val popup = android.widget.PopupMenu(v.context, v)
                if (text != null) popup.menu.add("Copy")
                popup.menu.add("Revert to here")
                popup.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        "Copy" -> {
                            val cm = v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("message", text))
                            Toast.makeText(v.context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        "Revert to here" -> android.app.AlertDialog.Builder(v.context)
                            .setTitle("Revert to this message?")
                            .setMessage("Undoes this message and everything after it, including file changes.")
                            .setPositiveButton("Revert") { _, _ -> revertProvider()?.invoke(msg.id) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    true
                }
                popup.show()
                true
            }
        }

        fun bind(message: RenderedMessage) {
            current = message
            (bubble.background as? android.graphics.drawable.GradientDrawable)
                ?.setColor(message.bubbleColor)
            role.text = message.roleText
            role.setTextColor(message.roleColor)
            meta.text = message.metaText ?: ""
            meta.visibility = if (message.metaText == null) android.view.View.GONE else android.view.View.VISIBLE
            body.text = message.body
        }
    }

    companion object {
        private fun isNightMode(context: android.content.Context): Boolean =
            (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        private fun roundedBackground(radius: Int): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = radius.toFloat()
            }

        /** A cheap fingerprint of a message's visible content (changes as text streams). */
        fun signature(message: MessageWithParts): Int {
            var h = message.id.hashCode()
            for (part in message.parts) {
                h = 31 * h + part.id.hashCode()
                when (val c = part.content) {
                    is PartContent.Text -> h = 31 * h + c.text.length
                    is PartContent.Reasoning -> h = 31 * h + c.text.length
                    is PartContent.Tool -> {
                        h = 31 * h + c.status.hashCode(); h = 31 * h + (c.title?.hashCode() ?: 0); h = 31 * h + c.tool.hashCode()
                    }
                    is PartContent.Patch -> { h = 31 * h + c.files.size; h = 31 * h + (c.hash?.hashCode() ?: 0) }
                    is PartContent.FileRef -> { h = 31 * h + (c.filename?.hashCode() ?: 0); h = 31 * h + (c.url?.hashCode() ?: 0) }
                    is PartContent.Compaction -> h = 31 * h + c.auto.hashCode()
                    is PartContent.StepStart -> h = 31 * h + (c.title?.hashCode() ?: 0)
                    else -> {}
                }
            }
            (message.info as? MessageInfo.Assistant)?.let {
                h = 31 * h + (it.completed?.hashCode() ?: 0)
                h = 31 * h + it.tokensOutput
                h = 31 * h + (it.error?.displayText?.hashCode() ?: 0)
            }
            return h
        }

        fun render(message: MessageWithParts): RenderedMessage {
            val baseSizePx = 15
            val body = SpannableStringBuilder()
            fun spacer() { if (body.isNotEmpty()) body.append("\n\n") }

            for (part in message.parts) {
                // Hidden parts (synthetic tool-call narration, server-ignored) —
                // matches the web client's `!synthetic && !ignored` filter.
                if (!part.isVisible) continue
                when (val c = part.content) {
                    is PartContent.Text -> if (c.text.isNotEmpty()) {
                        spacer(); body.append(MarkdownRenderer.render(c.text, baseSizePx, Color.TRANSPARENT))
                    }
                    is PartContent.Reasoning -> if (c.text.isNotEmpty()) {
                        spacer(); appendReasoning(body, c.text, baseSizePx)
                    }
                    is PartContent.Tool -> {
                        spacer(); body.append(toolLine(c))
                    }
                    is PartContent.Patch -> { spacer(); body.append(patchLine(c)) }
                    is PartContent.FileRef -> { spacer(); body.append(fileChip(c)) }
                    is PartContent.Compaction -> {
                        spacer()
                        val start = body.length
                        body.append(if (c.auto) "⸻  earlier context summarized  ⸻" else "⸻  context summarized  ⸻")
                        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        body.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, body.length, flag)
                        body.setSpan(ForegroundColorSpan(0xFF9A9A9A.toInt()), start, body.length, flag)
                        body.setSpan(RelativeSizeSpan(0.8f), start, body.length, flag)
                    }
                    is PartContent.StepStart -> c.title?.let { spacer(); body.append(it) }
                    else -> {}
                }
            }

            return when (val info = message.info) {
                is MessageInfo.User -> RenderedMessage(
                    id = message.id,
                    roleText = "You",
                    roleColor = 0xFF4DA3FF.toInt(),
                    metaText = null,
                    body = body,
                    bubbleColor = 0x1F4DA3FF,
                    signature = signature(message),
                )
                is MessageInfo.Assistant -> {
                    info.error?.let { spacer(); body.append("Error: ${it.displayText}") }
                    RenderedMessage(
                        id = message.id,
                        roleText = info.agent.ifEmpty { "assistant" },
                        roleColor = 0xFF1F9550.toInt(),
                        metaText = tokenSummary(info),
                        body = body,
                        // Subtle neutral tint that reads on both light and dark backgrounds.
                        bubbleColor = 0x14808080,
                        signature = signature(message),
                    )
                }
            }
        }

        /** Reasoning: a small dimmed "THINKING" label, then the thought in dimmed
         *  italic (inline code still monospaced) so it reads as meta. Matches iOS. */
        private fun appendReasoning(body: SpannableStringBuilder, text: String, baseSizePx: Int) {
            val dim = 0xFF9A9A9A.toInt()
            val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            val labelStart = body.length
            body.append("THINKING\n")
            body.setSpan(RelativeSizeSpan(0.7f), labelStart, body.length - 1, flag)
            body.setSpan(ForegroundColorSpan(dim), labelStart, body.length - 1, flag)
            val textStart = body.length
            body.append(MarkdownRenderer.render(text, baseSizePx, Color.TRANSPARENT))
            body.setSpan(StyleSpan(Typeface.ITALIC), textStart, body.length, flag)
            body.setSpan(ForegroundColorSpan(dim), textStart, body.length, flag)
        }

        private fun toolLine(tool: PartContent.Tool): CharSequence {
            val mark = when (tool.status) {
                "completed" -> "✓"
                "running" -> "…"
                "error" -> "✕"
                "pending" -> "◷"
                else -> "▸"
            }
            val (label, detail) = ToolDisplay.describe(tool)
            val sb = SpannableStringBuilder("$mark $label")
            if (!detail.isNullOrEmpty()) sb.append("  ").append(detail)
            // Never dump tool output inline — for `read` it's a whole file, for
            // `bash` a full build log. Only surface an error (matches iOS).
            if (tool.status == "error") {
                val err = tool.error ?: tool.output
                if (!err.isNullOrEmpty()) {
                    val snippet = if (err.length > 200) err.take(200) + "…" else err
                    sb.append("\n").append(snippet)
                }
            }
            return sb
        }

        /** A `patch` part: "⌥ Patch  N files" (the committed change snapshot). */
        private fun patchLine(patch: PartContent.Patch): CharSequence =
            SpannableStringBuilder("⌥ Patch  ").append(PatchDisplay.summary(patch))

        /** A `file` part: a compact context chip "📎 filename:line". */
        private fun fileChip(file: PartContent.FileRef): CharSequence =
            SpannableStringBuilder("📎 ").append(FileRefDisplay.chip(file))

        private fun tokenSummary(info: MessageInfo.Assistant): String {
            fun fmt(n: Int) = if (n >= 1000) "${n / 1000}k" else "$n"
            return "${fmt(info.tokensInput)}→${fmt(info.tokensOutput)}"
        }
    }
}
