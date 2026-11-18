package com.sahand.cortextchat

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * The chat list — the app's home screen once it is configured. Shows
 * the hub's chats (GET /api/mobile/sessions): pinned first, then most
 * recent — the same list the hub's web panel shows, so a chat started
 * in either place appears in the other. Tap a chat to open it.
 * Long-press a chat for Pin/Unpin and Delete (delete asks first).
 * "+" starts a new main-chat session. The gear opens Settings; the
 * sun/moon toggles the theme.
 *
 * A chat started in the hub's web panel shows up here, and a chat
 * started here shows up in the hub — both read the same sessions
 * table on the hub.
 */
class ChatListActivity : Activity() {

    private lateinit var prefs: SettingsStore
    private lateinit var client: ChatClient
    private lateinit var adapter: ChatListAdapter
    private lateinit var listView: ListView
    private lateinit var tvStatus: TextView

    private var chats = ArrayList<ChatClient.ChatSession>()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SettingsStore(this)
        ThemeHelper.applyTheme(this, prefs)

        if (!Crypto.selfCheck()) {
            Toast.makeText(
                this,
                "Crypto self-check failed — the app cannot talk to the hub safely.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (!prefs.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_chat_list)
        client = ChatClient(prefs)

        listView = findViewById(R.id.listChats)
        tvStatus = findViewById(R.id.tvChatListStatus)
        adapter = ChatListAdapter(this)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            openChat(chats[position])
        }
        // Long-press: Pin/Unpin and Delete.
        listView.setOnItemLongClickListener { _, _, position, _ ->
            showChatActions(chats[position])
            true
        }

        findViewById<Button>(R.id.btnNewChatList).setOnClickListener { newChat() }
        val btnTheme = findViewById<Button>(R.id.btnThemeList)
        btnTheme.text = if (ThemeHelper.isDark(prefs)) "☀" else "🌙"
        btnTheme.setOnClickListener { ThemeHelper.toggle(this, prefs) }
        findViewById<Button>(R.id.btnSettingsList).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        loadChats()
    }

    override fun onResume() {
        super.onResume()
        // Back from a chat or Settings: refresh the list (titles, pins).
        if (::prefs.isInitialized && prefs.isConfigured) loadChats()
    }

    private fun openChat(chat: ChatClient.ChatSession) {
        prefs.sessionId = chat.id
        prefs.personaId = chat.personaId
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("session_id", chat.id)
            putExtra("persona_id", chat.personaId)
        }
        startActivity(intent)
    }

    private fun loadChats() {
        if (loading) return
        loading = true
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.loading_chats)
        Thread {
            try {
                val list = client.sessions()
                runOnUiThread {
                    loading = false
                    chats = ArrayList(list)
                    adapter.update(chats)
                    if (chats.isEmpty()) {
                        tvStatus.text = getString(R.string.no_chats)
                    } else {
                        tvStatus.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    tvStatus.text = getString(R.string.could_not_load_chats)
                    Toast.makeText(this, "Could not load chats: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Start a new main-chat session and open it. */
    private fun newChat() {
        if (loading) return
        loading = true
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = getString(R.string.starting_chat)
        Thread {
            try {
                val id = client.createSession("")
                runOnUiThread {
                    loading = false
                    tvStatus.visibility = View.GONE
                    prefs.sessionId = id
                    prefs.personaId = ""
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("session_id", id)
                        putExtra("persona_id", "")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    loading = false
                    tvStatus.visibility = View.GONE
                    Toast.makeText(this, "Could not start a new chat: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showChatActions(chat: ChatClient.ChatSession) {
        val items = arrayOf(
            if (chat.pinned) getString(R.string.unpin) else getString(R.string.pin),
            getString(R.string.delete_chat),
            getString(R.string.cancel)
        )
        AlertDialog.Builder(this)
            .setTitle(chat.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> togglePin(chat)
                    1 -> confirmDelete(chat)
                }
            }
            .show()
    }

    private fun togglePin(chat: ChatClient.ChatSession) {
        Thread {
            try {
                client.pinSession(chat.id, !chat.pinned)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (chat.pinned) "Unpinned" else "Pinned",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadChats()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not pin: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmDelete(chat: ChatClient.ChatSession) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_chat))
            .setMessage(getString(R.string.delete_chat_confirm))
            .setPositiveButton(getString(R.string.delete_chat)) { _, _ -> deleteChat(chat) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun deleteChat(chat: ChatClient.ChatSession) {
        Thread {
            try {
                client.deleteSession(chat.id)
                // If the deleted chat was the current one, forget it so
                // the next open starts fresh instead of a dead session.
                if (prefs.sessionId == chat.id) {
                    prefs.clearSession()
                    prefs.personaId = ""
                }
                runOnUiThread {
                    Toast.makeText(this, "Chat deleted", Toast.LENGTH_SHORT).show()
                    loadChats()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Could not delete: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
