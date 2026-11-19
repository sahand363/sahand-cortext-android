package com.sahand.cortextchat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.AbsListView
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * The one chat screen. Minimal: a top bar (CorText, new chat, theme
 * toggle, settings), the message list, a "Thinking…" line, and the
 * input row. Nothing else.
 *
 * v2 (PROTOCOL-v2.md): there is no login call — the first encrypted
 * request IS the authentication (the token, copied from the hub's
 * Mobile page, is the encryption key and the credential).
 *
 * Flow on open: reuse the saved session (or create one), load the
 * history. Sending shows the user's message at once and a "Thinking…"
 * line until the reply arrives (the model can take minutes — the HTTP
 * read timeout is 600s).
 */
class MainActivity : Activity() {

    private lateinit var prefs: SettingsStore
    private lateinit var client: ChatClient
    private lateinit var adapter: MessageAdapter
    private lateinit var listView: ListView
    private lateinit var etMessage: EditText
    private lateinit var tvThinking: TextView

    private var messages = ArrayList<ChatUiMessage>()
    private var sessionId: String = ""
    private var busy = false
    private var configFingerprint: String = ""
    // Scroll policy while a reply streams: true while the user is at
    // (or near) the bottom. A user scroll-up flips it off, so the
    // stream never yanks the scroll while they read; scrolling back
    // to the bottom flips it on again.
    private var autoScroll = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SettingsStore(this)
        ThemeHelper.applyTheme(this, prefs)

        // The cipher must match PROTOCOL-v2.md, or nothing we send can be
        // read by the hub. Fail loudly, not silently.
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
        configFingerprint = configFingerprint()
        setContentView(R.layout.activity_main)
        client = ChatClient(prefs)
        // Opened from the chat list with a specific session (extras), or
        // from Settings / first launch with the saved session. The
        // extras also carry the session's persona, so Settings shows
        // the right persona in the picker.
        val extraSession = intent.getStringExtra("session_id").orEmpty()
        val extraPersona = intent.getStringExtra("persona_id")
        if (extraSession.isNotBlank()) {
            sessionId = extraSession
            prefs.sessionId = extraSession
            if (extraPersona != null) prefs.personaId = extraPersona
        } else {
            sessionId = prefs.sessionId
        }

        listView = findViewById(R.id.listMessages)
        adapter = MessageAdapter(this)
        listView.adapter = adapter
        // Track whether the user is at the bottom: a scroll-up turns
        // auto-scroll off until they scroll back down. Programmatic
        // scrolls (setSelection) also fire this, and they report the
        // bottom correctly, so the flag stays in sync either way.
        listView.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(
                view: AbsListView?, scrollState: Int
            ) {
            }

            override fun onScroll(
                view: AbsListView?, firstVisibleItem: Int,
                visibleItemCount: Int, totalItemCount: Int
            ) {
                autoScroll = totalItemCount == 0 ||
                    firstVisibleItem + visibleItemCount >= totalItemCount - 1
            }
        })
        etMessage = findViewById(R.id.etMessage)
        tvThinking = findViewById(R.id.tvThinking)

        val btnSend = findViewById<Button>(R.id.btnSend)
        btnSend.setOnClickListener { sendMessage() }

        // Theme toggle: show the sun when dark (tap for light),
        // the moon when light (tap for dark). The toggle recreates
        // this Activity, so the new theme applies immediately.
        val btnTheme = findViewById<Button>(R.id.btnTheme)
        btnTheme.text = if (ThemeHelper.isDark(prefs)) "☀" else "🌙"
        btnTheme.setOnClickListener { ThemeHelper.toggle(this, prefs) }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnNewChat).setOnClickListener { newChat() }
        // Back to the chat list (ChatListActivity). The system back
        // button already did this, but there was no visible way to
        // get there — now there is an arrow in the top bar.
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val restoredRoles = savedInstanceState?.getStringArrayList("roles")
        val restoredContents = savedInstanceState?.getStringArrayList("contents")
        if (restoredRoles != null && restoredContents != null &&
            restoredRoles.size == restoredContents.size
        ) {
            // Recreated (e.g. theme toggle): keep the messages on screen
            // instead of reloading them from the server.
            messages = ArrayList(
                restoredRoles.indices.map { i ->
                    ChatUiMessage(restoredRoles[i], restoredContents[i])
                }
            )
            refreshList()
        } else {
            loadHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings with a different server/token: the saved
        // session belongs to the old server, so start fresh.
        if (::prefs.isInitialized && prefs.isConfigured &&
            configFingerprint() != configFingerprint
        ) {
            configFingerprint = configFingerprint()
            prefs.clearSession()
            sessionId = ""
            messages.clear()
            refreshList()
            loadHistory()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("roles", ArrayList(messages.map { it.role }))
        outState.putStringArrayList("contents", ArrayList(messages.map { it.content }))
    }

    private fun configFingerprint(): String {
        // NOTE: the chosen model is deliberately NOT part of the
        // fingerprint. Changing the model must not start a new session
        // — only a server/token change clears it (see onResume).
        return "${prefs.host}|${prefs.port}|${prefs.token}"
    }

    private fun refreshList() {
        adapter.update(messages)
        if (messages.isNotEmpty()) listView.setSelection(messages.size - 1)
    }

    /** Refresh the bubbles without moving the scroll (used while a
     *  reply streams, so the list is not yanked on every chunk). */
    private fun refreshListKeepPosition() {
        adapter.update(messages)
    }

    /** Scroll anchoring when a response starts streaming: put the AI
     *  reply a few lines below the top, so the last lines of the
     *  user's question stay visible above it and the reply beginning
     *  shows below — instead of jumping to the very bottom, which
     *  would hide the question. Posted, so the ListView has laid the
     *  new message out first. */
    private fun anchorToQuestion() {
        val aiIndex = messages.size - 1
        if (aiIndex < 1) return
        val offsetPx = (72 * resources.displayMetrics.density).toInt()
        listView.post { listView.setSelectionFromTop(aiIndex, offsetPx) }
    }

    /** Append one streamed chunk to the AI message at index, then
     *  follow the stream only while the user is at the bottom. */
    private fun appendChunk(index: Int, chunk: String) {
        if (index !in messages.indices) return
        val cur = messages[index]
        messages[index] = ChatUiMessage("assistant", cur.content + chunk)
        refreshListKeepPosition()
        maybeAutoScroll()
    }

    /** While streaming, scroll to the bottom only if the user is
     *  already at (or near) it; if they scrolled up to read, leave
     *  the scroll alone. */
    private fun maybeAutoScroll() {
        if (autoScroll && messages.isNotEmpty()) {
            listView.setSelection(messages.size - 1)
        }
    }

    private fun showError(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** Reuse (or create) the session, load its history. The first
     *  encrypted request also proves the token is accepted by the hub.
     *  A new session is created with the current persona context, so a
     *  persona chat that expired on the hub comes back as the same
     *  persona instead of main chat. */
    private fun loadHistory() {
        Thread {
            try {
                if (sessionId.isBlank()) {
                    sessionId = client.createSession(prefs.personaId)
                    prefs.sessionId = sessionId
                }
                val loaded = try {
                    client.history(sessionId)
                } catch (e: Exception) {
                    // The session may have expired on the hub: start a
                    // fresh one and try the (empty) history once.
                    sessionId = client.createSession(prefs.personaId)
                    prefs.sessionId = sessionId
                    client.history(sessionId)
                }
                runOnUiThread {
                    messages = ArrayList(loaded.map { ChatUiMessage(it.role, it.content) })
                    refreshList()
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Could not reach the hub: ${e.message}") }
            }
        }.start()
    }

    /** Send a message with real streaming: the AI reply appears
     *  word-by-word as it arrives (client.stream), instead of waiting
     *  for the whole reply. The empty AI bubble is added up front so
     *  chunks append to it; the scroll is anchored to the question
     *  (see anchorToQuestion) and only follows the stream while the
     *  user is at the bottom.
     *
     *  Fallback: if the stream endpoint is missing (an older hub
     *  answers HTTP 404 for /api/mobile/chat/stream), the buffered
     *  send() is used instead, so the app still works. Any other
     *  stream failure does NOT fall back — the user message is already
     *  in the hub's history, so a second send would duplicate it;
     *  a partial reply stays on screen and the error is shown. */
    private fun sendMessage() {
        if (busy) return
        val text = etMessage.text.toString().trim()
        if (text.isEmpty()) return
        etMessage.setText("")
        messages.add(ChatUiMessage("user", text))
        // The AI bubble for this reply, filled in chunk by chunk.
        val replyIndex = messages.size
        messages.add(ChatUiMessage("assistant", ""))
        // Stay anchored at the question + the start of the reply
        // while it streams (see anchorToQuestion): the view must
        // NOT chase the bottom, or the user ends up at the end of
        // the answer and has to scroll back up to read from the
        // start. autoScroll stays off until the user scrolls to the
        // bottom themselves — the scroll listener flips it on then.
        autoScroll = false
        anchorToQuestion()
        tvThinking.visibility = android.view.View.VISIBLE
        busy = true
        Thread {
            var streamedAny = false
            try {
                // The chosen model is read here, at send time, from
                // SettingsStore — so changing it in Settings takes
                // effect on the next message, in the same session.
                val done = client.stream(sessionId, text, prefs.modelProvider, prefs.modelId) { chunk ->
                    streamedAny = true
                    runOnUiThread { appendChunk(replyIndex, chunk) }
                }
                if (!done || !streamedAny) {
                    // The stream ended without the reply on screen: it was
                    // cut before {"done": true}, or the hub produced the
                    // answer without streaming any of it (e.g. a model that
                    // answers in its reasoning field). The hub has saved
                    // the reply, so fetch the history instead of dropping
                    // the bubble — no more closing and reopening the chat.
                    syncFromHub()
                    // Cut before done: the model may still be writing on
                    // the hub. Look again a few times until it is saved.
                    if (!done) scheduleResync()
                    return@Thread
                }
                runOnUiThread {
                    busy = false
                    tvThinking.visibility = android.view.View.GONE
                    if (replyIndex in messages.indices &&
                        messages[replyIndex].content.isBlank()
                    ) {
                        messages.removeAt(replyIndex)
                    }
                    refreshListKeepPosition()
                }
            } catch (e: Exception) {
                val missingEndpoint = e is ChatClient.ApiException &&
                    (e.message?.contains("404") == true)
                if (missingEndpoint) {
                    // Older hub without the stream endpoint: fall back
                    // to the buffered send (the empty bubble is
                    // dropped first, so the reply is not duplicated).
                    try {
                        val reply = client.send(
                            sessionId, text, prefs.modelProvider, prefs.modelId
                        )
                        runOnUiThread {
                            busy = false
                            tvThinking.visibility = android.view.View.GONE
                            if (replyIndex in messages.indices) {
                                messages.removeAt(replyIndex)
                            }
                            if (reply.isNotBlank()) {
                                messages.add(ChatUiMessage("assistant", reply))
                            }
                            refreshList()
                        }
                    } catch (e2: Exception) {
                        runOnUiThread {
                            busy = false
                            tvThinking.visibility = android.view.View.GONE
                            if (replyIndex in messages.indices) {
                                messages.removeAt(replyIndex)
                            }
                            refreshListKeepPosition()
                            showError("Could not send: ${e2.message}")
                        }
                    }
                } else {
                    // A real error (or a stream that broke mid-way):
                    // keep any partial reply, drop the empty bubble
                    // when nothing arrived, and show the error.
                    runOnUiThread {
                        busy = false
                        tvThinking.visibility = android.view.View.GONE
                        if (!streamedAny && replyIndex in messages.indices) {
                            messages.removeAt(replyIndex)
                        }
                        refreshListKeepPosition()
                        showError("Could not send: ${e.message}")
                    }
                }
            }
        }.start()
    }

    /** Replace the on-screen messages with the hub's history for this
     *  session (the hub is the source of truth once a reply is saved).
     *  Called from a background thread after a stream that ended
     *  without the reply on screen. Keeps the scroll anchored at the
     *  question, like a streamed reply. */
    private fun syncFromHub() {
        val loaded = try {
            client.history(sessionId)
        } catch (e: Exception) {
            null
        }
        runOnUiThread {
            busy = false
            tvThinking.visibility = android.view.View.GONE
            if (loaded != null) {
                messages = ArrayList(loaded.map { ChatUiMessage(it.role, it.content) })
                if (messages.lastOrNull()?.role == "assistant") {
                    refreshListKeepPosition()
                    anchorToQuestion()
                } else {
                    refreshList()
                }
            } else {
                if (messages.lastOrNull()?.let { it.role == "assistant" && it.content.isBlank() } == true) {
                    messages.removeAt(messages.size - 1)
                }
                refreshListKeepPosition()
                showError("The reply was cut off. Pull it in by reopening the chat.")
            }
        }
    }

    /** After a dropped connection the hub keeps working and saves the
     *  reply when the model finishes. Check again a few times so the
     *  answer appears without reopening the chat. */
    private fun scheduleResync() {
        val sid = sessionId
        Thread {
            for (delayMs in longArrayOf(3_000, 10_000, 30_000, 60_000)) {
                try { Thread.sleep(delayMs) } catch (e: InterruptedException) { return@Thread }
                if (isFinishing || isDestroyed || sid != sessionId || busy) return@Thread
                val loaded = try { client.history(sid) } catch (e: Exception) { continue }
                val last = loaded.lastOrNull() ?: continue
                if (last.role != "assistant") continue
                runOnUiThread {
                    if (sid == sessionId && !busy) {
                        messages = ArrayList(loaded.map { ChatUiMessage(it.role, it.content) })
                        refreshListKeepPosition()
                        maybeAutoScroll()
                    }
                }
                return@Thread
            }
        }.start()
    }

    /** Start a fresh main-chat conversation (new session on the hub).
     *  The old chat stays in the chat list. */
    private fun newChat() {
        if (busy) return
        busy = true
        tvThinking.visibility = android.view.View.VISIBLE
        Thread {
            try {
                val id = client.createSession("")
                runOnUiThread {
                    busy = false
                    tvThinking.visibility = android.view.View.GONE
                    sessionId = id
                    prefs.sessionId = id
                    prefs.personaId = ""
                    messages.clear()
                    refreshList()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    tvThinking.visibility = android.view.View.GONE
                    showError("Could not start a new chat: ${e.message}")
                }
            }
        }.start()
    }
}
