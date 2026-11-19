package com.sahand.cortextchat

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the hub (Direct mode) or the relay (Relay mode) over plain
 * HTTP(S). v2 (PROTOCOL-v2.md): every endpoint is under /api/mobile/,
 * and the ONLY credential is the mobile token — it is the encryption
 * key (SHA-256(token)) and the auth (the hub verifies it by decrypting
 * the request body, so the token is never sent in plaintext). There is
 * no login call: the first encrypted request IS the authentication.
 *
 * SSL Auto: try HTTPS first; on a connection-level failure fall back to
 * HTTP and remember which scheme worked, so the next call goes straight
 * there. If the remembered scheme is HTTP, HTTPS is re-probed once an
 * hour — so when a certificate is added later, the app upgrades to it
 * automatically. A non-200 HTTP response is an app error (wrong token,
 * hub error), not a scheme failure, so it never triggers a fallback.
 *
 * All calls are blocking — call them from a background thread.
 * Connect timeout 10s; read timeout 600s (the model can take minutes).
 */
class ChatClient(private val prefs: SettingsStore) {

    class ApiException(message: String) : Exception(message)

    data class ChatMessage(val role: String, val content: String)

    /** One model the hub offers: provider id + model id + human label. */
    data class ModelOption(
        val provider: String,
        val id: String,
        val displayName: String
    )

    /** The hub's model list (POST /api/mobile/models, encrypted). */
    data class ModelsResult(
        val options: List<ModelOption>,
        val activeProvider: String,
        val activeModel: String
    )

    /** One chat in the hub's list (GET /api/mobile/sessions). The hub
     *  orders them pinned first, then most recent — the app shows them
     *  in that order. */
    data class ChatSession(
        val id: String,
        val title: String,
        val personaId: String,
        val pinned: Boolean,
        val updatedAt: String
    )

    /** One persona the hub offers (GET /api/mobile/personas). */
    data class PersonaOption(val id: String, val name: String)

    private fun schemeOrder(): List<String> {
        return when (prefs.ssl) {
            "on" -> listOf("https")
            "off" -> listOf("http")
            else -> { // auto
                val remembered = prefs.lastScheme
                val probeDue =
                    System.currentTimeMillis() - prefs.lastHttpsProbeAt > 3_600_000L
                when {
                    remembered == "https" -> listOf("https", "http")
                    remembered == "http" && probeDue -> {
                        prefs.lastHttpsProbeAt = System.currentTimeMillis()
                        listOf("https", "http")
                    }
                    remembered == "http" -> listOf("http", "https")
                    else -> listOf("https", "http")
                }
            }
        }
    }

    private fun baseUrl(scheme: String): String {
        return "$scheme://${prefs.host.trim()}:${prefs.port}"
    }

    /** POST an encrypted JSON object to path, return the decrypted JSON. */
    private fun postEncrypted(path: String, json: JSONObject): JSONObject =
        requestEncrypted("POST", path, json)

    /** DELETE with an encrypted JSON body (the token check), return the
     *  decrypted JSON. */
    private fun deleteEncrypted(path: String, json: JSONObject): JSONObject =
        requestEncrypted("DELETE", path, json)

    /** Send an encrypted JSON object with the given method, return the
     *  decrypted JSON. Tries the schemes in order (SSL Auto fallback);
     *  a server answer (wrong token, hub error) is an app error, not a
     *  scheme failure, so it never triggers a fallback. */
    private fun requestEncrypted(method: String, path: String, json: JSONObject): JSONObject {
        val wire = Crypto.encrypt(
            prefs.token, json.toString().toByteArray(Charsets.UTF_8)
        )
        var lastError: Exception? = null
        for (scheme in schemeOrder()) {
            try {
                val result = requestWire(scheme, method, path, wire)
                prefs.lastScheme = scheme
                return result
            } catch (e: ApiException) {
                // The server answered (wrong token, hub error, ...):
                // the scheme is fine, so do not fall back to the other one.
                throw e
            } catch (e: Exception) {
                // Connection-level failure — try the next scheme.
                lastError = e
            }
        }
        throw ApiException(lastError?.message ?: "Could not reach the server")
    }

    private fun requestWire(scheme: String, method: String, path: String, wire: String): JSONObject {
        val conn = (URL(baseUrl(scheme) + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 600_000
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
        }
        try {
            conn.outputStream.use { it.write(wire.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            if (code !in 200..299) {
                // Plain JSON error body: {"error": "..."}
                val msg = try {
                    JSONObject(text).optString("error", "").ifBlank { "HTTP $code" }
                } catch (e: Exception) {
                    "HTTP $code"
                }
                throw ApiException(msg)
            }
            // 200: the body is the encrypted wire.
            val plain = try {
                Crypto.decrypt(prefs.token, text).toString(Charsets.UTF_8)
            } catch (e: SecurityException) {
                throw ApiException("Wrong token — the reply could not be decrypted")
            } catch (e: Exception) {
                throw ApiException("Bad reply from server: ${e.message}")
            }
            return JSONObject(plain)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Plain (unencrypted) GET /api/mobile/health — reachability check.
     * Also tells us whether the mobile feature is activated on the hub.
     */
    fun health() {
        var lastError: Exception? = null
        for (scheme in schemeOrder()) {
            try {
                val conn = (URL(baseUrl(scheme) + "/api/mobile/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                }
                try {
                    val code = conn.responseCode
                    if (code in 200..299) {
                        prefs.lastScheme = scheme
                        val text = BufferedReader(
                            InputStreamReader(conn.inputStream, Charsets.UTF_8)
                        ).use { it.readText() }
                        val enabled = try {
                            JSONObject(text).optBoolean("mobile_enabled", true)
                        } catch (e: Exception) {
                            true
                        }
                        if (!enabled) {
                            throw ApiException(
                                "The mobile app is not activated on the hub yet"
                            )
                        }
                        return
                    }
                    // The server answered, but not with the mobile
                    // health page — most likely a hub without the
                    // mobile update. That is an app error, not a
                    // scheme failure, so do not fall back.
                    throw ApiException(
                        "Server answered HTTP $code — is this hub running the mobile update?"
                    )
                } finally {
                    conn.disconnect()
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw ApiException(lastError?.message ?: "Could not reach the server")
    }

    /**
     * Is host:port a cortext-relay rather than the hub itself? The relay
     * forwards every mobile request unchanged, so the hub can't tell the two
     * apart; only the relay answers GET / with {"service": "cortext-relay"}.
     * Uses the scheme health() just found. Any failure counts as "hub".
     */
    fun isRelay(): Boolean {
        val scheme = prefs.lastScheme.ifBlank { "https" }
        return try {
            val conn = (URL(baseUrl(scheme) + "/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            try {
                if (conn.responseCode !in 200..299) return false
                val text = BufferedReader(
                    InputStreamReader(conn.inputStream, Charsets.UTF_8)
                ).use { it.readText() }
                JSONObject(text).optString("service") == "cortext-relay"
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Create a CorText session. personaId "" = main chat; a persona id
     *  starts a chat with that persona (the hub validates it and falls
     *  back to main chat when unknown). Also the encrypted check that
     *  proves the token is accepted: the hub can only answer this if it
     *  decrypted the request with the stored token. */
    fun createSession(personaId: String = ""): String {
        val req = JSONObject().apply { put("persona_id", personaId) }
        val resp = postEncrypted("/api/mobile/sessions", req)
        return resp.optString("id", "").also {
            if (it.isBlank()) throw ApiException("Server did not return a session")
        }
    }

    /** The hub's chat list (POST /api/mobile/sessions/list, encrypted) — the
     *  same chats the hub's web panel shows, so a chat started in either
     *  place appears in the other. It is a POST, not a GET: a GET request
     *  body is not reliably carried by HttpURLConnection, so the list
     *  came back empty. Throws ApiException when the hub has no such
     *  endpoint yet (older hub) or cannot be reached. */
    fun sessions(): List<ChatSession> {
        val resp = postEncrypted("/api/mobile/sessions/list", JSONObject())
        val arr = resp.optJSONArray("sessions") ?: return emptyList()
        val out = ArrayList<ChatSession>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            out.add(
                ChatSession(
                    id = id,
                    title = o.optString("title", "New conversation").ifBlank { "New conversation" },
                    personaId = o.optString("persona_id", ""),
                    pinned = o.optBoolean("pinned", false),
                    updatedAt = o.optString("updated_at", "")
                )
            )
        }
        return out
    }

    /** Pin or unpin a chat (POST /api/mobile/sessions/{id}/pin).
     *  Returns the updated session. */
    fun pinSession(id: String, pinned: Boolean): ChatSession {
        val req = JSONObject().apply { put("pinned", pinned) }
        val resp = postEncrypted("/api/mobile/sessions/$id/pin", req)
        return ChatSession(
            id = resp.optString("id", id),
            title = resp.optString("title", "New conversation").ifBlank { "New conversation" },
            personaId = resp.optString("persona_id", ""),
            pinned = resp.optBoolean("pinned", pinned),
            updatedAt = resp.optString("updated_at", "")
        )
    }

    /** Delete a chat and its messages (DELETE /api/mobile/sessions/{id}). */
    fun deleteSession(id: String) {
        deleteEncrypted("/api/mobile/sessions/$id", JSONObject())
    }

    /** The hub's enabled personas (POST /api/mobile/personas, encrypted).
     *  It is a POST, not a GET: a GET request body is not reliably
     *  carried by HttpURLConnection, so the list came back empty.
     *  Throws ApiException when the hub has no such endpoint yet (older
     *  hub) or cannot be reached — the caller falls back to "Main chat". */
    fun personas(): List<PersonaOption> {
        val resp = postEncrypted("/api/mobile/personas", JSONObject())
        val arr = resp.optJSONArray("personas") ?: return emptyList()
        val out = ArrayList<PersonaOption>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            if (id.isEmpty()) continue
            out.add(PersonaOption(id, o.optString("name", id).ifBlank { id }))
        }
        return out
    }

    fun send(sessionId: String, message: String, provider: String = "", model: String = ""): String {
        val req = JSONObject().apply {
            put("session_id", sessionId)
            put("message", message)
            // Only send provider/model when the user picked a model.
            // "Hub default" omits both keys and the hub uses its
            // active defaults, exactly as before.
            if (provider.isNotBlank() && model.isNotBlank()) {
                put("provider", provider)
                put("model", model)
            }
        }
        val resp = postEncrypted("/api/mobile/chat", req)
        return resp.optString("reply", "")
    }

    /** Stream a reply (POST /api/mobile/chat/stream, encrypted SSE).
     *  onChunk is called on the CALLER's thread with each piece of the
     *  reply text, in order, as it arrives. Returns when the hub sends
     *  {"done": true}. Throws ApiException on an {"error": ...} event,
     *  on a non-200 HTTP status (e.g. 404 on a hub without the stream
     *  endpoint — the caller falls back to send()), or on a connection
     *  failure. The hub writes the full reply to its DB only when the
     *  stream completes, exactly like the buffered chat. All calls are
     *  blocking — call from a background thread.
     *
     *  Returns true when the hub sent {"done": true}, false when the
     *  connection closed before that (a proxy or the network cut the
     *  stream). On false the reply may still be complete on the hub —
     *  the caller re-syncs the history instead of trusting the bubble. */
    fun stream(
        sessionId: String,
        message: String,
        provider: String = "",
        model: String = "",
        onChunk: (String) -> Unit
    ): Boolean {
        val req = JSONObject().apply {
            put("session_id", sessionId)
            put("message", message)
            if (provider.isNotBlank() && model.isNotBlank()) {
                put("provider", provider)
                put("model", model)
            }
        }
        val wire = Crypto.encrypt(
            prefs.token, req.toString().toByteArray(Charsets.UTF_8)
        )
        var lastError: Exception? = null
        for (scheme in schemeOrder()) {
            try {
                val done = streamWire(scheme, wire, onChunk)
                prefs.lastScheme = scheme
                return done
            } catch (e: ApiException) {
                // The server answered (wrong token, hub error, no stream
                // endpoint) — the scheme is fine, do not fall back.
                throw e
            } catch (e: Exception) {
                // Connection-level failure — try the next scheme.
                lastError = e
            }
        }
        throw ApiException(lastError?.message ?: "Could not reach the server")
    }

    private fun streamWire(scheme: String, wire: String, onChunk: (String) -> Unit): Boolean {
        val conn = (URL(baseUrl(scheme) + "/api/mobile/chat/stream").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 600_000
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Accept", "text/event-stream")
        }
        try {
            conn.outputStream.use { it.write(wire.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                } ?: ""
                val msg = try {
                    JSONObject(text).optString("error", "").ifBlank { "HTTP $code" }
                } catch (e: Exception) {
                    "HTTP $code"
                }
                throw ApiException(msg)
            }
            // 200: an SSE stream. Each "data: <wire>" line is one
            // encrypted event {"chunk": "..."} / {"done": true} /
            // {"error": "..."}. readLine() blocks until the next line
            // arrives, so chunks are delivered as they are sent.
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (true) {
                // A network error AFTER the hub accepted the message is a
                // cut stream, not a reason to try the next scheme: that
                // would send the same message to the hub a second time.
                val next: String? = try {
                    reader.readLine()
                } catch (e: java.io.IOException) {
                    return false
                }
                if (next == null) break
                val l = next.trim()
                if (!l.startsWith("data:")) continue
                val eventWire = l.removePrefix("data:").trim()
                if (eventWire.isEmpty()) continue
                val plain = try {
                    Crypto.decrypt(prefs.token, eventWire).toString(Charsets.UTF_8)
                } catch (e: SecurityException) {
                    throw ApiException("Wrong token — the reply could not be decrypted")
                } catch (e: Exception) {
                    throw ApiException("Bad reply from server: ${e.message}")
                }
                val obj = try {
                    JSONObject(plain)
                } catch (e: Exception) {
                    continue
                }
                val err = obj.optString("error", "")
                if (err.isNotBlank()) throw ApiException(err)
                if (obj.optBoolean("done", false)) return true
                val chunk = obj.optString("chunk", "")
                if (chunk.isNotEmpty()) onChunk(chunk)
            }
            // The stream ended without a {"done": true}: something cut
            // the connection. Report it so the caller re-syncs.
            return false
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The hub's available models (encrypted POST /api/mobile/models).
     * Only enabled models are returned. Throws ApiException when the
     * hub has no such endpoint yet (older hub) or cannot be reached —
     * the caller falls back to "Hub default".
     */
    fun models(): ModelsResult {
        val resp = postEncrypted("/api/mobile/models", JSONObject())
        val options = ArrayList<ModelOption>()
        val arr = resp.optJSONArray("models")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (!o.optBoolean("enabled", true)) continue
                val provider = o.optString("provider", "").trim()
                val id = o.optString("id", "").trim()
                if (provider.isEmpty() || id.isEmpty()) continue
                val display = o.optString("display_name", "").trim().ifBlank { id }
                options.add(ModelOption(provider, id, display))
            }
        }
        return ModelsResult(
            options,
            resp.optString("active_provider", ""),
            resp.optString("active_model", "")
        )
    }

    fun history(sessionId: String): List<ChatMessage> {
        val req = JSONObject().apply { put("session_id", sessionId) }
        val resp = postEncrypted("/api/mobile/history", req)
        val arr = resp.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<ChatMessage>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(ChatMessage(o.optString("role", ""), o.optString("content", "")))
        }
        return out
    }
}
