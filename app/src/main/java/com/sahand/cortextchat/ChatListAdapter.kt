package com.sahand.cortextchat

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One row per chat in the chat list: the title (with a 📌 marker when
 * pinned) and the last-activity date. Colours and fonts come from the
 * current theme, so a theme toggle repaints them with no code change.
 */
class ChatListAdapter(private val context: Context) : BaseAdapter() {

    private var items: List<ChatClient.ChatSession> = emptyList()

    fun update(newItems: List<ChatClient.ChatSession>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_chat, parent, false)
        val item = items[position]
        val tvTitle = view.findViewById<TextView>(R.id.tvChatTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvChatSubtitle)
        tvTitle.text = if (item.pinned) "📌 ${item.title}" else item.title
        tvSub.text = formatDate(item.updatedAt)
        return view
    }

    /**
     * The hub stores updated_at as UTC "YYYY-MM-DD HH:MM:SS" (SQLite
     * CURRENT_TIMESTAMP). Show it in the phone's local time as
     * "Today, 14:30" / "Yesterday, 14:30" / "16 Nov 2026, 14:30".
     * If the value does not parse, show it as-is.
     */
    private fun formatDate(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed: Date = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(raw)
        } catch (e: Exception) {
            return raw
        } ?: return raw
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val cal = Calendar.getInstance().apply { time = parsed }
        val today = Calendar.getInstance()
        val isToday = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        today.add(Calendar.DAY_OF_YEAR, -1)
        val isYesterday = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return when {
            isToday -> "Today, ${timeFmt.format(parsed)}"
            isYesterday -> "Yesterday, ${timeFmt.format(parsed)}"
            else -> SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(parsed)
        }
    }
}
