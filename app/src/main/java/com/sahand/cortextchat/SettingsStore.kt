package com.sahand.cortextchat

import android.content.Context
import android.content.SharedPreferences

/**
 * App settings, stored in plain SharedPreferences (MODE_PRIVATE — the
 * file is only readable by this app).
 *
 * v2 is token-based (PROTOCOL-v2.md): the ONE credential is the mobile
 * token copied from the hub's Mobile page. It is BOTH the encryption
 * key (SHA-256(token)) and the auth credential (the hub verifies it by
 * decrypting the request body — the token is never sent in plaintext).
 * There is no username, no password, and no separate tunnel key.
 *
 * NOTE: the token lives here in plaintext. That is a deliberate
 * trade-off, not an oversight: EncryptedSharedPreferences needs the
 * AndroidX Security library, and this app has zero third-party
 * dependencies on purpose (mirroring SMS Cleaner). The file is
 * app-private, so the token is only exposed if the phone is rooted or
 * the app data is extracted. If that ever becomes unacceptable, add
 * androidx.security:security-crypto and switch the token to it.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cortext_chat", Context.MODE_PRIVATE)

    var mode: String // "direct" | "relay"
        get() = prefs.getString("mode", "direct") ?: "direct"
        set(v) = prefs.edit().putString("mode", v).apply()

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v).apply()

    var port: Int
        get() = prefs.getInt("port", 8787)
        set(v) = prefs.edit().putInt("port", v).apply()

    /** The mobile token from the hub's Mobile page. Encryption key + auth. */
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    /** "auto" | "on" | "off". Default Auto. */
    var ssl: String
        get() = prefs.getString("ssl", "auto") ?: "auto"
        set(v) = prefs.edit().putString("ssl", v).apply()

    /** "light" | "dark". Default Light. */
    var theme: String
        get() = prefs.getString("theme", "light") ?: "light"
        set(v) = prefs.edit().putString("theme", v).apply()

    /** The current CorText chat session id (reused across restarts). */
    var sessionId: String
        get() = prefs.getString("session_id", "") ?: ""
        set(v) = prefs.edit().putString("session_id", v).apply()

    /**
     * The model chosen in Settings (provider id + model id). Empty =
     * "Hub default": the app sends no provider/model and the hub uses
     * its active defaults. Changing the model does NOT start a new
     * session — it is deliberately NOT part of the config fingerprint
     * in MainActivity (only a server/token change clears the session).
     */
    var modelProvider: String
        get() = prefs.getString("model_provider", "") ?: ""
        set(v) = prefs.edit().putString("model_provider", v).apply()

    var modelId: String
        get() = prefs.getString("model_id", "") ?: ""
        set(v) = prefs.edit().putString("model_id", v).apply()

    /** True when the user picked a specific model (not Hub default). */
    val hasModelChoice: Boolean
        get() = modelProvider.isNotBlank() && modelId.isNotBlank()

    /**
     * The persona of the current session (prefs.sessionId). Empty =
     * main chat (no persona). The persona lives on the session on the
     * hub; this is the app's copy of it, so Settings can show the
     * current persona in the picker. It is updated whenever the app
     * opens or creates a session. Changing it in Settings (a different
     * persona) creates a NEW session with that persona — the old chat
     * stays in the list, its history is never discarded.
     */
    var personaId: String
        get() = prefs.getString("persona_id", "") ?: ""
        set(v) = prefs.edit().putString("persona_id", v).apply()

    /** The scheme that last worked ("https" | "http" | ""), for SSL Auto. */
    var lastScheme: String
        get() = prefs.getString("last_scheme", "") ?: ""
        set(v) = prefs.edit().putString("last_scheme", v).apply()

    /** When HTTPS was last probed in Auto mode (for auto-upgrade). */
    var lastHttpsProbeAt: Long
        get() = prefs.getLong("last_https_probe_at", 0L)
        set(v) = prefs.edit().putLong("last_https_probe_at", v).apply()

    /** True once the user has saved a server and a token. */
    val isConfigured: Boolean
        get() = host.isNotBlank() && token.isNotBlank()

    fun clearSession() {
        prefs.edit().remove("session_id").apply()
    }

    /** Log out: forget the token (and the session). */
    fun clearToken() {
        prefs.edit()
            .remove("token").remove("session_id")
            .apply()
    }
}
