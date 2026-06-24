package studio.eugenezakharov.opencode.ui.session

import android.graphics.Color
import android.text.Spanned
import android.text.SpannableStringBuilder
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

    private val items = mutableListOf<RenderedMessage>()

    /** Replaces the list, issuing minimal notifications. Returns true if anything changed. */
    fun submit(messages: List<MessageWithParts>): Boolean {
        val next = messages.map { render(it) }
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
            setTextColor(0xFFE6E6E6.toInt())
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        bubble.addView(header)
        bubble.addView(body)
        outer.addView(bubble)
        return MessageViewHolder(outer, bubble, role, meta, body)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    class MessageViewHolder(
        itemView: LinearLayout,
        private val bubble: LinearLayout,
        private val role: TextView,
        private val meta: TextView,
        private val body: TextView,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(message: RenderedMessage) {
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
                    is PartContent.Tool -> { h = 31 * h + c.status.hashCode(); h = 31 * h + (c.output?.length ?: 0) }
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
                when (val c = part.content) {
                    is PartContent.Text -> if (c.text.isNotEmpty()) {
                        spacer(); body.append(MarkdownRenderer.render(c.text, baseSizePx, Color.TRANSPARENT))
                    }
                    is PartContent.Tool -> {
                        spacer(); body.append(toolLine(c))
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
                        roleColor = 0xFF4CD964.toInt(),
                        metaText = tokenSummary(info),
                        body = body,
                        bubbleColor = 0x14FFFFFF,
                        signature = signature(message),
                    )
                }
            }
        }

        private fun toolLine(tool: PartContent.Tool): CharSequence {
            val mark = when (tool.status) {
                "completed" -> "✓ "
                "running" -> "… "
                "error" -> "✕ "
                "pending" -> "◷ "
                else -> "▸ "
            }
            val sb = SpannableStringBuilder(mark + (tool.title ?: tool.tool))
            tool.output?.takeIf { it.isNotEmpty() }?.let { output ->
                val snippet = if (output.length > 400) output.take(400) + "…" else output
                sb.append("\n").append(snippet)
            }
            return sb
        }

        private fun tokenSummary(info: MessageInfo.Assistant): String {
            fun fmt(n: Int) = if (n >= 1000) "${n / 1000}k" else "$n"
            return "${fmt(info.tokensInput)}→${fmt(info.tokensOutput)}"
        }
    }
}
