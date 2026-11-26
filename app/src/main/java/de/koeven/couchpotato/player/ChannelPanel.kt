package de.koeven.couchpotato.player

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.koeven.couchpotato.data.LiveCategory
import de.koeven.couchpotato.data.LiveChannel
import de.koeven.couchpotato.data.PlayItem
import de.koeven.couchpotato.player.PlayerStyle.ACCENT
import de.koeven.couchpotato.player.PlayerStyle.ON_ACCENT
import de.koeven.couchpotato.player.PlayerStyle.TEXT

/**
 * Channel list in the live player (OK): categories on the left, their channels on the right.
 * OK on a category shows its channels, OK on a channel switches to it – without leaving the
 * player. Up/down then zap through that category.
 */
internal class ChannelPanel(private val a: PlayerActivity, private val root: FrameLayout) {
    private var panel: View? = null
    private var categories: List<LiveCategory> = emptyList()
    private var shownCategory: String? = null
    private var channels: List<LiveChannel> = emptyList()
    private lateinit var catList: RecyclerView
    private lateinit var chList: RecyclerView
    private lateinit var title: TextView

    val visible: Boolean get() = panel != null

    fun show() {
        if (visible) return
        categories = LiveLineup.categories(a)
        if (categories.isEmpty()) { a.toast("Keine Sender im Katalog"); return }
        val current = a.items.getOrNull(a.live.index)
        val currentCat = current?.let { LiveLineup.categoryOf(a, it.streamId) }
            ?.takeIf { id -> categories.any { it.id == id } } ?: categories.first().id

        title = TextView(a).apply {
            setTextColor(PlayerStyle.MUTED); textSize = 15f; typeface = context.interMedium()
            setPadding(a.dp(14), 0, 0, a.dp(8))
        }
        catList = list()
        chList = list()
        val columns = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(catList, LinearLayout.LayoutParams(a.dp(230), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(chList, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = a.dp(10) })
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(16), a.dp(18), a.dp(16), a.dp(18))
            background = a.panelBackground()
            addView(title)
            addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(box, FrameLayout.LayoutParams(a.dp(640), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START).apply {
            setMargins(a.dp(28), a.dp(28), 0, a.dp(28))
        })
        panel = box
        a.playerView.hideController()

        catList.adapter = Rows({ categories.size }, { i -> categories[i].name }, { i -> categories[i].id == shownCategory }) { i ->
            showChannels(categories[i].id, focusIndex = 0)
        }
        showChannels(currentCat, focusIndex = channels(currentCat).indexOfFirst { it.id == current?.streamId }.coerceAtLeast(0))
        catList.scrollToPosition(categories.indexOfFirst { it.id == currentCat }.coerceAtLeast(0))
    }

    private fun channels(category: String) = LiveLineup.channels(a, category)

    private fun showChannels(category: String, focusIndex: Int) {
        shownCategory = category
        channels = channels(category)
        title.text = categories.firstOrNull { it.id == category }?.name ?: "Sender"
        catList.adapter?.notifyDataSetChanged()
        val playing = a.items.getOrNull(a.live.index)?.streamId
        chList.adapter = Rows({ channels.size }, { i -> channels[i].name }, { i -> channels[i].id == playing }) { i ->
            a.live.play(channels.map { PlayItem.live(it) }, i)
            hide()
        }
        if (channels.isEmpty()) { a.toast("In dieser Kategorie sind keine Sender"); return }
        focus(chList, focusIndex)
    }

    /** Scrolls to [index] and gives that row the focus once it is laid out. */
    private fun focus(list: RecyclerView, index: Int) {
        list.scrollToPosition(index)
        list.post { list.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() ?: list.getChildAt(0)?.requestFocus() }
    }

    fun hide() {
        panel?.let { root.removeView(it) }
        panel = null
        a.playerView.requestFocus()
    }

    private fun list() = RecyclerView(a).apply {
        layoutManager = LinearLayoutManager(a)
        itemAnimator = null
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }

    /** One text row per entry; [marked] = the current category / the channel that is playing. */
    private inner class Rows(
        private val count: () -> Int,
        private val text: (Int) -> String,
        private val marked: (Int) -> Boolean,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = count()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            fun bg(color: Int) = GradientDrawable().apply { cornerRadius = a.dp(12).toFloat(); setColor(color) }
            val v = TextView(a).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(a.dp(14), a.dp(11), a.dp(14), a.dp(11))
                textSize = 17f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), bg(ACCENT))
                    addState(intArrayOf(), bg(0x00000000))
                }
                setOnFocusChangeListener { view, has -> (view as TextView).setTextColor(if (has) ON_ACCENT else TEXT) }
                setTextColor(TEXT)
            }
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView as TextView
            val mark = marked(position)
            v.text = (if (mark) "▶  " else "") + text(position)
            v.setTypeface(null, if (mark) Typeface.BOLD else Typeface.NORMAL)
            v.setOnClickListener { holder.bindingAdapterPosition.takeIf { it >= 0 }?.let(onClick) }
        }
    }
}
