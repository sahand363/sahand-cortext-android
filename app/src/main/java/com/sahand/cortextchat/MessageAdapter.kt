package com.sahand.cortextchat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import android.widget.Toast

data class ChatUiMessage(val role: String, val content: String)

/**
 * Chat bubbles in a ListView (framework only, no RecyclerView):
 * user on the right, assistant on the left, generous whitespace.
 * Bubble colours come from the current theme (the panel's exact
 * colours), so a theme toggle repaints them with no code change.
 * Bubbles are 16dp rounded with the corner on the speaker's side
 * kept sharp, like the panel's chat.
 */
class MessageAdapter(private val context: Context) : BaseAdapter() {

    private var items: List<ChatUiMessage> = emptyList()
    private val maxBubbleWidth: Int
    private val cornerRadius: Float
    private val tailRadius: Float

    init {
        val dm = context.resources.displayMetrics
        maxBubbleWidth = (dm.widthPixels * 0.78).toInt()
        cornerRadius = 16f * dm.density
        tailRadius = 4f * dm.density
    }

    fun update(newItems: List<ChatUiMessage>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_message, parent, false)
        val item = items[position]
        val bubble = view.findViewById<TextView>(R.id.tvBubble)
        val spacerLeft = view.findViewById<View>(R.id.spacerLeft)
        val spacerRight = view.findViewById<View>(R.id.spacerRight)

        val isUser = item.role == "user"
        bubble.text = item.content
        bubble.maxWidth = maxBubbleWidth
        val bgColor = ThemeHelper.attrColor(
            context, if (isUser) R.attr.chatBubbleUser else R.attr.chatBubbleAssistant
        )
        val textColor = ThemeHelper.attrColor(
            context, if (isUser) R.attr.chatBubbleUserText else R.attr.chatBubbleAssistantText
        )
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            // Rounded everywhere except the bottom corner on the
            // speaker's side — the chat "tail".
            cornerRadii = if (isUser) {
                floatArrayOf(
                    cornerRadius, cornerRadius, // top-left
                    cornerRadius, cornerRadius, // top-right
                    tailRadius, tailRadius,     // bottom-right (tail)
                    cornerRadius, cornerRadius  // bottom-left
                )
            } else {
                floatArrayOf(
                    cornerRadius, cornerRadius, // top-left
                    cornerRadius, cornerRadius, // top-right
                    cornerRadius, cornerRadius, // bottom-right
                    tailRadius, tailRadius      // bottom-left (tail)
                )
            }
            setColor(bgColor)
        }
        bubble.setTextColor(textColor)
        bubble.gravity = if (isUser) Gravity.END else Gravity.START
        // The spacer on the opposite side pushes the bubble to its side.
        spacerLeft.visibility = if (isUser) View.VISIBLE else View.GONE
        spacerRight.visibility = if (isUser) View.GONE else View.VISIBLE
        // "⧉ Copy" under assistant replies: copies the whole message.
        // (Partial text is copied via long-press selection, which the
        // bubble already supports with textIsSelectable.) User messages
        // get no copy row — the row would sit under a right-aligned
        // bubble and add clutter for a message you rarely copy.
        val copyRow = view.findViewById<View>(R.id.copyRow)
        val btnCopy = view.findViewById<TextView>(R.id.btnCopy)
        if (isUser) {
            copyRow.visibility = View.GONE
        } else {
            copyRow.visibility = View.VISIBLE
            btnCopy.setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("CorText", item.content))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        return view
    }
}
