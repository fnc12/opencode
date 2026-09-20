package studio.eugenezakharov.opencode.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.util.Base64
import android.widget.ImageView
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import studio.eugenezakharov.opencode.api.models.MessageInfo
import studio.eugenezakharov.opencode.api.models.MessageWithParts
import studio.eugenezakharov.opencode.api.models.PartContent
import studio.eugenezakharov.opencode.api.models.QuestionRequest

/** One rendered piece of a message body: a run of styled text, or a GFM table. */
sealed interface MsgBlock {
    data class Text(val span: CharSequence) : MsgBlock
    data class Table(val rows: List<List<String>>) : MsgBlock
    /** An image attachment (a pasted screenshot), decoded from the file part's
     *  `data:` URL — shown inline and tappable for a full-screen view. */
    data class Image(val bitmap: Bitmap) : MsgBlock
}

/** A run inside a single text part: inline-markdown body, or a lifted GFM table. */
sealed interface TextRun {
    data class Body(val text: String) : TextRun
    data class TableRun(val rows: List<List<String>>) : TextRun
}

/** A message pre-rendered to body blocks plus role styling, built once per content change. */
data class RenderedMessage(
    val id: String,
    val roleText: String,
    val roleColor: Int,
    val metaText: String?,
    val blocks: List<MsgBlock>,
    val plain: String,
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
class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
    /** Invoked with the full message when a row is tapped (opens its detail). */
    var onSelect: ((MessageWithParts) -> Unit)? = null

    // --- Footer (the pending question dock) --------------------------------
    // The dock rides the transcript as its LAST ROW so it scrolls WITH the
    // messages (never floating over them). Mirrors iOS `SessionFooter`.
    private var footerQuestions: List<QuestionRequest> = emptyList()
    var onQuestionReply: ((QuestionRequest, List<List<String>>) -> Unit)? = null
    var onQuestionReject: ((QuestionRequest) -> Unit)? = null
    /** Selections hoisted OUT of the composable (keyed by request id → question key
     *  → labels) so they survive the ComposeView being recycled when scrolled off. */
    private val footerSelections = mutableMapOf<String, SnapshotStateMap<String, Set<String>>>()

    private val hasFooter get() = footerQuestions.isNotEmpty()

    /** Set / update / clear the footer question dock. Returns true if it changed. */
    fun setFooter(questions: List<QuestionRequest>): Boolean {
        val had = hasFooter
        val idsChanged = footerQuestions.map { it.id } != questions.map { it.id }
        footerQuestions = questions
        footerSelections.keys.retainAll(questions.map { it.id }.toSet())
        val pos = items.size
        return when {
            !had && hasFooter -> { notifyItemInserted(pos); true }
            had && !hasFooter -> { notifyItemRemoved(pos); true }
            had && hasFooter && idsChanged -> { notifyItemChanged(pos); true }
            else -> false
        }
    }

    /** Replaces the list, issuing minimal notifications. Returns true if anything changed. */
    fun submit(messages: List<MessageWithParts>): Boolean {
        val next = messages.filter { it.hasRenderableContent }.map { Row(it.id, it, signature(it)) }
        val old = items.toList()

        // Nothing changed at all — skip the diff.
        if (old.size == next.size &&
            old.indices.all { old[it].id == next[it].id && old[it].signature == next[it].signature }
        ) {
            return false
        }

        // Diff by id (same row) + signature (same content), then dispatch granular
        // updates. Unlike the old notifyDataSetChanged() for structure changes,
        // this lets the RecyclerView's item animator slide/fade a newly-arrived
        // row (a Shell/Tool step) in instead of snapping the whole list. A row
        // whose content changed (streaming text) still rebinds just itself.
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == next[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o].signature == next[n].signature
        }, /* detectMoves = */ false)
        items.clear()
        items.addAll(next)
        diff.dispatchUpdatesTo(this)
        return true
    }

    private fun rendered(row: Row): RenderedMessage {
        val key = "${row.id}:${row.signature}"
        return renderCache.get(key) ?: render(row.message).also { renderCache.put(key, it) }
    }

    override fun getItemCount(): Int = items.size + if (hasFooter) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (hasFooter && position == items.size) TYPE_FOOTER else TYPE_MESSAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_FOOTER) {
            val compose = ComposeView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
            }
            return FooterViewHolder(compose)
        }
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
        // Holds any blocks after the first text run (tables, trailing text) so the
        // common single-text-block message keeps using just `body` (cheap).
        val extra = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        bubble.addView(header)
        bubble.addView(body)
        bubble.addView(extra)
        outer.addView(bubble)
        return MessageViewHolder(
            outer, bubble, role, meta, body, extra,
            revertProvider = { onRevert },
            selectProvider = { { pos -> items.getOrNull(pos)?.let { onSelect?.invoke(it.message) } } },
        )
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FooterViewHolder) {
            holder.composeView.setContent {
                SessionFooter(
                    questions = footerQuestions,
                    selectionsFor = { req -> footerSelections.getOrPut(req.id) { mutableStateMapOf() } },
                    onReply = { req, answers -> onQuestionReply?.invoke(req, answers) },
                    onReject = { req -> onQuestionReject?.invoke(req) },
                )
            }
            return
        }
        (holder as MessageViewHolder).bind(rendered(items[position]))
    }

    class FooterViewHolder(val composeView: ComposeView) : RecyclerView.ViewHolder(composeView)

    class MessageViewHolder(
        itemView: LinearLayout,
        private val bubble: LinearLayout,
        private val role: TextView,
        private val meta: TextView,
        private val body: TextView,
        private val extra: LinearLayout,
        private val revertProvider: () -> ((String) -> Unit)?,
        private val selectProvider: () -> ((Int) -> Unit)?,
    ) : RecyclerView.ViewHolder(itemView) {
        private var current: RenderedMessage? = null

        init {
            // Tap a message → open its detail screen.
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) selectProvider()?.invoke(pos)
            }
            // Long-press a message → Copy / Revert to here.
            itemView.setOnLongClickListener { v ->
                val msg = current ?: return@setOnLongClickListener false
                val text = msg.plain.takeIf { it.isNotBlank() }
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

            // First text block reuses the persistent `body` TextView (common,
            // cheap path). Any tables / trailing text go into `extra`.
            val first = message.blocks.firstOrNull()
            if (first is MsgBlock.Text) {
                body.visibility = android.view.View.VISIBLE
                body.text = first.span
            } else {
                body.visibility = android.view.View.GONE
                body.text = ""
            }
            val rest = if (first is MsgBlock.Text) message.blocks.drop(1) else message.blocks
            extra.removeAllViews()
            if (rest.isEmpty()) {
                extra.visibility = android.view.View.GONE
            } else {
                extra.visibility = android.view.View.VISIBLE
                val ctx = extra.context
                for (b in rest) {
                    when (b) {
                        is MsgBlock.Text -> extra.addView(makeBodyTextView(ctx, b.span))
                        is MsgBlock.Table -> extra.addView(buildTableView(ctx, b.rows))
                        is MsgBlock.Image -> extra.addView(makeImageView(ctx, b.bitmap))
                    }
                }
            }
        }
    }

    companion object {
        private const val TYPE_MESSAGE = 0
        private const val TYPE_FOOTER = 1

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
            val blocks = ArrayList<MsgBlock>()
            var cur = SpannableStringBuilder()
            fun spacer() { if (cur.isNotEmpty()) cur.append("\n\n") }
            fun flushText() {
                if (cur.isNotEmpty()) { blocks.add(MsgBlock.Text(cur)); cur = SpannableStringBuilder() }
            }

            for (part in message.parts) {
                // Hidden parts (synthetic tool-call narration, server-ignored) —
                // matches the web client's `!synthetic && !ignored` filter.
                if (!part.isVisible) continue
                when (val c = part.content) {
                    is PartContent.Text -> if (c.text.isNotEmpty()) {
                        // Lift GFM tables into their own blocks (rendered as a real
                        // grid); the rest is inline markdown.
                        for (run in splitTextRuns(c.text)) {
                            when (run) {
                                is TextRun.Body -> { spacer(); cur.append(MarkdownRenderer.render(run.text, baseSizePx, Color.TRANSPARENT)) }
                                is TextRun.TableRun -> { flushText(); blocks.add(MsgBlock.Table(run.rows)) }
                            }
                        }
                    }
                    is PartContent.Reasoning -> if (c.text.isNotEmpty()) {
                        spacer(); appendReasoning(cur, c.text, baseSizePx)
                    }
                    is PartContent.Tool -> {
                        spacer(); cur.append(toolLine(c))
                    }
                    is PartContent.Patch -> { spacer(); cur.append(patchLine(c)) }
                    is PartContent.FileRef -> {
                        // A pasted screenshot / image attachment renders inline
                        // (tappable for full-screen). Non-images fall back to the chip.
                        val bmp = imageAttachment(c)
                        if (bmp != null) { flushText(); blocks.add(MsgBlock.Image(bmp)) }
                        else { spacer(); cur.append(fileChip(c)) }
                    }
                    is PartContent.Compaction -> {
                        spacer()
                        val start = cur.length
                        cur.append(if (c.auto) "⸻  earlier context summarized  ⸻" else "⸻  context summarized  ⸻")
                        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        cur.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, cur.length, flag)
                        cur.setSpan(ForegroundColorSpan(0xFF9A9A9A.toInt()), start, cur.length, flag)
                        cur.setSpan(RelativeSizeSpan(0.8f), start, cur.length, flag)
                    }
                    is PartContent.StepStart -> c.title?.let { spacer(); cur.append(it) }
                    else -> {}
                }
            }
            val info = message.info
            if (info is MessageInfo.Assistant) info.error?.let { spacer(); cur.append("Error: ${it.displayText}") }
            flushText()
            val plain = plainOf(blocks)

            return when (info) {
                is MessageInfo.User -> RenderedMessage(
                    id = message.id,
                    roleText = "You",
                    roleColor = 0xFF4DA3FF.toInt(),
                    metaText = null,
                    blocks = blocks,
                    plain = plain,
                    bubbleColor = 0x1F4DA3FF,
                    signature = signature(message),
                )
                is MessageInfo.Assistant -> {
                    RenderedMessage(
                        id = message.id,
                        roleText = info.agent.ifEmpty { "assistant" },
                        roleColor = 0xFF1F9550.toInt(),
                        metaText = tokenSummary(info),
                        blocks = blocks,
                        plain = plain,
                        // Subtle neutral tint that reads on both light and dark backgrounds.
                        bubbleColor = 0x14808080,
                        signature = signature(message),
                    )
                }
            }
        }

        private fun plainOf(blocks: List<MsgBlock>): String = blocks.joinToString("\n\n") { b ->
            when (b) {
                is MsgBlock.Text -> b.span.toString()
                is MsgBlock.Table -> b.rows.joinToString("\n") { it.joinToString(" | ") }
                is MsgBlock.Image -> "🖼 image"
            }
        }

        /** Decodes an image `file` part to a Bitmap for inline display, or null if
         *  it isn't an image or the bytes can't be read. Attachments arrive with the
         *  pixels embedded in a `data:image/…;base64,…` URL (no fetch needed). */
        fun imageAttachment(file: PartContent.FileRef): Bitmap? {
            val isImage = file.mime?.startsWith("image/") == true ||
                file.url?.startsWith("data:image/") == true
            val url = file.url
            if (!isImage || url == null) return null
            return decodeDataUrlImage(url)
        }

        /** `data:[<mime>][;base64],<payload>` → Bitmap. Only base64 data URLs are
         *  supported (what the server sends); returns null otherwise. */
        fun decodeDataUrlImage(url: String): Bitmap? {
            if (!url.startsWith("data:")) return null
            val comma = url.indexOf(',')
            if (comma < 0 || !url.substring(0, comma).contains(";base64")) return null
            return try {
                val bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /** Splits a text part into inline-markdown runs and GFM table runs. */
        fun splitTextRuns(text: String): List<TextRun> {
            val res = ArrayList<TextRun>()
            val lines = text.split("\n")
            val buf = StringBuilder()
            fun flush() {
                val t = buf.toString().trim('\n')
                if (t.isNotBlank()) res.add(TextRun.Body(t))
                buf.setLength(0)
            }
            var i = 0
            while (i < lines.size) {
                if (i + 1 < lines.size && isTableRow(lines[i]) && isSeparatorRow(lines[i + 1])) {
                    flush()
                    val rows = ArrayList<List<String>>()
                    var j = i
                    while (j < lines.size && isTableRow(lines[j])) {
                        if (!isSeparatorRow(lines[j])) rows.add(splitCells(lines[j]))
                        j++
                    }
                    res.add(TextRun.TableRun(rows))
                    i = j
                } else {
                    buf.append(lines[i]).append("\n"); i++
                }
            }
            flush()
            return res
        }

        private fun isTableRow(line: String) = line.contains('|') && line.isNotBlank()
        private fun isSeparatorRow(line: String): Boolean {
            val cells = splitCells(line)
            return cells.isNotEmpty() && cells.all { c -> c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' || it == ' ' } }
        }
        private fun splitCells(line: String): List<String> {
            var s = line.trim()
            if (s.startsWith("|")) s = s.substring(1)
            if (s.endsWith("|")) s = s.substring(0, s.length - 1)
            return s.split("|").map { it.trim() }
        }

        /** Reasoning: a small dimmed "THINKING" label, then the thought in dimmed
         *  italic (inline code still monospaced) so it reads as meta. Matches iOS. */
        /** Reasoning is collapsed to a one-line "💭 Thinking" marker on the phone —
         *  the full chain of thought is on the message detail screen. */
        private fun appendReasoning(body: SpannableStringBuilder, text: String, baseSizePx: Int) {
            val dim = 0xFF9A9A9A.toInt()
            val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            val start = body.length
            body.append("💭 Thinking")
            body.setSpan(ForegroundColorSpan(dim), start, body.length, flag)
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

private fun isNight(ctx: Context): Boolean =
    (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

private fun bodyColor(ctx: Context): Int = if (isNight(ctx)) 0xFFE6E6E6.toInt() else 0xFF1C1C1E.toInt()

/** A plain body TextView for a text block that follows a table. */
/** An inline image attachment: an aspect-fit thumbnail capped so a tall screenshot
 *  doesn't take over the transcript, with rounded corners and a tap that opens the
 *  full-screen, zoomable viewer. */
private fun makeImageView(ctx: Context, bitmap: Bitmap): android.view.View {
    val d = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * d).toInt()
    return ImageView(ctx).apply {
        setImageBitmap(bitmap)
        adjustViewBounds = true
        maxHeight = px(260)
        scaleType = ImageView.ScaleType.FIT_START
        setPadding(0, px(6), 0, 0)
        val radius = px(10).toFloat()
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                // Inset the top by the padding so the rounded rect hugs the image, not the pad.
                outline.setRoundRect(0, view.paddingTop, view.width, view.height, radius)
            }
        }
        contentDescription = "Image attachment"
        isClickable = true
        setOnClickListener { showImageViewer(ctx, bitmap) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
}

private fun makeBodyTextView(ctx: Context, span: CharSequence): TextView {
    val d = ctx.resources.displayMetrics.density
    return TextView(ctx).apply {
        text = span
        textSize = 15f
        setTextColor(bodyColor(ctx))
        setPadding(0, (6 * d).toInt(), 0, 0)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }
}

/**
 * A GFM table rendered as a REAL grid (not ASCII pipes): a bold header with a
 * rule, aligned columns (TableLayout auto-sizes them), zebra rows, an outer
 * rounded border, and horizontal scroll so wide tables stay aligned.
 */
private fun buildTableView(ctx: Context, rows: List<List<String>>): android.view.View {
    val d = ctx.resources.displayMetrics.density
    fun px(v: Int) = (v * d).toInt()
    val night = isNight(ctx)
    val line = if (night) 0x40FFFFFF else 0x22000000
    val zebra = if (night) 0x0DFFFFFF else 0x07000000
    val ncol = rows.maxOf { it.size }

    val table = android.widget.TableLayout(ctx)
    for ((r, row) in rows.withIndex()) {
        val tr = android.widget.TableRow(ctx)
        if (r > 0 && r % 2 == 1) tr.setBackgroundColor(zebra)
        for (col in 0 until ncol) {
            val cell = TextView(ctx).apply {
                text = MarkdownRenderer.render(row.getOrElse(col) { "" }, 15, Color.TRANSPARENT)
                setTextColor(bodyColor(ctx))
                textSize = 14f
                if (r == 0) setTypeface(typeface, Typeface.BOLD)
                setPadding(px(12), px(8), px(12), px(8))
                setSingleLine(true)
                maxLines = 1
            }
            tr.addView(cell)
        }
        table.addView(tr)
        if (r == 0) {
            table.addView(
                android.view.View(ctx).apply {
                    setBackgroundColor(line)
                    layoutParams = android.widget.TableLayout.LayoutParams(
                        android.widget.TableLayout.LayoutParams.MATCH_PARENT, px(1),
                    )
                },
            )
        }
    }

    val border = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = px(10).toFloat()
        setStroke(px(1).coerceAtLeast(1), line)
        setColor(Color.TRANSPARENT)
    }
    return android.widget.HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = true
        background = border
        clipToOutline = false
        addView(table)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = px(8); lp.bottomMargin = px(4)
        layoutParams = lp
    }
}
