package com.sahand.cortextchat

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast

/**
 * The one settings screen (v2, PROTOCOL-v2.md): mode (Direct/Relay),
 * host, port, TOKEN, SSL (Auto/On/Off), theme, and the MODEL picker.
 * Shown on first launch (no settings saved) and from the gear on the
 * chat screen.
 *
 * The token is the ONLY credential: it is copied from the hub's Mobile
 * page, and it is both the encryption key and the auth (the hub
 * verifies it by decrypting the request body). There is no username,
 * no password, and no separate tunnel key.
 *
 * The model picker lists the hub's models (POST /api/mobile/models);
 * "Hub default" sends no provider/model and the hub uses its active
 * defaults. Picking a model does not start a new session.
 *
 * Back: the visible ← button and the system back button both return
 * to the chat WITHOUT saving (MainActivity is underneath, not
 * finished). Saving is never required just to get back to the chat.
 *
 * Activation: saving runs two checks in the background — a plain
 * GET /api/mobile/health (reachability + is mobile activated on the
 * hub?) and then an encrypted POST /api/mobile/sessions (does the hub
 * accept this token?). Only when both pass is the app activated and
 * the chat opened. "Test connection" runs the same two checks without
 * leaving the screen.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: SettingsStore
    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etToken: EditText
    private lateinit var spinnerMode: Spinner
    private lateinit var spinnerSsl: Spinner
    private lateinit var spinnerTheme: Spinner
    private lateinit var spinnerModel: Spinner
    private lateinit var spinnerPersona: Spinner

    /** The mode the port field was last filled for (for the port default). */
    private var lastMode: String = "direct"

    /** The hub's models (index 0 in the spinner is always "Hub default"). */
    private var modelOptions: List<ChatClient.ModelOption> = emptyList()
    private var modelLoading = false

    /** The model the user picked but has not saved yet (null = no pick
     *  yet, so the saved choice is shown). Survives a theme recreate. */
    private var pendingModelProvider: String? = null
    private var pendingModelId: String? = null

    /** The hub's personas (index 0 in the spinner is always "Main chat"). */
    private var personaOptions: List<ChatClient.PersonaOption> = emptyList()
    private var personaLoading = false

    /** The persona the user picked but has not saved yet (null = no pick
     *  yet, so the current session's persona is shown). Survives a
     *  theme recreate. */
    private var pendingPersonaId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SettingsStore(this)
        ThemeHelper.applyTheme(this, prefs)
        setContentView(R.layout.activity_settings)

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etToken = findViewById(R.id.etToken)
        spinnerMode = findViewById(R.id.spinnerMode)
        spinnerSsl = findViewById(R.id.spinnerSsl)
        spinnerTheme = findViewById(R.id.spinnerTheme)
        spinnerModel = findViewById(R.id.spinnerModel)
        spinnerPersona = findViewById(R.id.spinnerPersona)

        pendingModelProvider = savedInstanceState?.getString("pending_model_provider")
        pendingModelId = savedInstanceState?.getString("pending_model_id")
        pendingPersonaId = savedInstanceState?.getString("pending_persona_id")

        // Back button: return to the chat WITHOUT saving (discard any
        // unsaved edits). Shown only when the app is already configured
        // — on first launch there is no chat to go back to. The system
        // back button does the same (MainActivity is underneath, not
        // finished), so this is just the visible version of it.
        val btnBack = findViewById<Button>(R.id.btnBack)
        if (prefs.isConfigured) {
            btnBack.visibility = android.view.View.VISIBLE
            btnBack.setOnClickListener { finish() }
        } else {
            btnBack.visibility = android.view.View.GONE
        }

        // Model picker: starts with just "Hub default"; the hub's list
        // fills it in from the background when we are connected.
        spinnerModel.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(getString(R.string.model_hub_default))
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                // Ignore the selection event from the initial
                // "Hub default"-only adapter (the list is not loaded
                // yet) — otherwise it would wipe a saved model pick.
                if (modelOptions.isEmpty()) return
                if (position == 0) {
                    pendingModelProvider = ""
                    pendingModelId = ""
                } else {
                    val o = modelOptions[position - 1]
                    pendingModelProvider = o.provider
                    pendingModelId = o.id
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        loadModels()

        // Persona picker: starts with just "Main chat"; the hub's list
        // fills it in from the background when we are connected.
        // Picking a persona applies to the current chat: if it differs
        // from the current session's persona, saving starts a NEW
        // session with that persona — the old chat keeps its history
        // in the chat list, untouched.
        spinnerPersona.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(getString(R.string.persona_main_chat))
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerPersona.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                // Ignore the selection event from the initial
                // "Main chat"-only adapter (the list is not loaded
                // yet) — otherwise it would wipe a saved persona pick.
                if (personaOptions.isEmpty()) return
                pendingPersonaId = if (position == 0) {
                    ""
                } else {
                    personaOptions[position - 1].id
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        loadPersonas()

        lastMode = prefs.mode

        spinnerMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(getString(R.string.mode_direct), getString(R.string.mode_relay))
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerMode.setSelection(if (prefs.mode == "relay") 1 else 0)
        spinnerMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                val newMode = if (position == 1) "relay" else "direct"
                // Keep the port in step with the mode, unless the user
                // typed a custom port: Direct defaults to the hub
                // (8788), Relay to the relay (8080).
                val portText = etPort.text.toString().trim()
                val oldDefault = defaultPort(lastMode).toString()
                if (portText.isEmpty() || portText == oldDefault) {
                    etPort.setText(defaultPort(newMode).toString())
                }
                etPort.hint = defaultPort(newMode).toString()
                lastMode = newMode
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerSsl.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(
                getString(R.string.ssl_auto),
                getString(R.string.ssl_on),
                getString(R.string.ssl_off)
            )
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSsl.setSelection(
            when (prefs.ssl) {
                "on" -> 1
                "off" -> 2
                else -> 0
            }
        )

        spinnerTheme.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item,
            listOf(getString(R.string.theme_light), getString(R.string.theme_dark))
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerTheme.setSelection(if (ThemeHelper.isDark(prefs)) 1 else 0)
        // Attach the listener AFTER setting the selection, so the initial
        // selection does not fire it.
        spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) {
                val wanted = if (position == 1) "dark" else "light"
                if (wanted != prefs.theme) {
                    prefs.theme = wanted
                    recreate()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Fill the fields from the saved settings.
        etHost.setText(prefs.host)
        etPort.setText(if (prefs.port != 0) prefs.port.toString() else "")
        etPort.hint = defaultPort(prefs.mode).toString()
        etToken.setText(prefs.token)

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (!readAndSave()) return@setOnClickListener
            // Activation: prove the token works before opening the chat.
            verifyAndActivate(openChat = true)
        }
        findViewById<Button>(R.id.btnTest).setOnClickListener {
            if (!readAndSave()) return@setOnClickListener
            verifyAndActivate(openChat = false)
        }
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            prefs.clearToken()
            etToken.setText("")
            Toast.makeText(
                this, "Logged out — paste the token from the hub to connect again.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun defaultPort(mode: String): Int = if (mode == "relay") 8080 else 8788

    /**
     * Validate the fields and save them. Returns false (with a Toast)
     * when something is missing, so the caller can stop.
     */
    private fun readAndSave(): Boolean {
        val host = etHost.text.toString().trim()
        val portText = etPort.text.toString().trim()
        val port = portText.toIntOrNull()
        val token = etToken.text.toString().trim()
        if (host.isEmpty()) {
            Toast.makeText(this, "Enter the host (IP or domain).", Toast.LENGTH_SHORT).show()
            return false
        }
        if (port == null || port !in 1..65535) {
            Toast.makeText(this, "Enter a port between 1 and 65535.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (token.isEmpty()) {
            Toast.makeText(
                this, "Paste the token from the hub's Mobile page.",
                Toast.LENGTH_SHORT
            ).show()
            return false
        }
        val mode = if (spinnerMode.selectedItemPosition == 1) "relay" else "direct"
        val ssl = when (spinnerSsl.selectedItemPosition) {
            1 -> "on"
            2 -> "off"
            else -> "auto"
        }
        // The model pick. Only overwrite the saved model when the hub's
        // list is actually loaded — if the fetch is still in flight, the
        // spinner only holds "Hub default" and saving it would wipe a
        // saved model choice. A model change never clears the session.
        if (modelOptions.isNotEmpty()) {
            val selPos = spinnerModel.selectedItemPosition
            if (selPos > 0 && selPos - 1 < modelOptions.size) {
                val o = modelOptions[selPos - 1]
                prefs.modelProvider = o.provider
                prefs.modelId = o.id
            } else {
                prefs.modelProvider = ""
                prefs.modelId = ""
            }
        }
        // The persona pick. Only apply it when the hub's list is
        // actually loaded — if the fetch is still in flight, the spinner
        // only holds "Main chat" and saving it would wipe the current
        // persona. A persona change starts a NEW session with that
        // persona: the persona lives on the session, so the current chat
        // keeps its own persona (and its history) in the chat list,
        // untouched — it is never silently discarded.
        if (personaOptions.isNotEmpty()) {
            val selPos = spinnerPersona.selectedItemPosition
            val picked = if (selPos > 0 && selPos - 1 < personaOptions.size) {
                personaOptions[selPos - 1].id
            } else {
                ""
            }
            if (picked != prefs.personaId) {
                prefs.personaId = picked
                prefs.clearSession()
            }
        }
        // A different server or token means the saved chat session is
        // meaningless.
        val serverChanged = mode != prefs.mode || host != prefs.host ||
            port != prefs.port || token != prefs.token
        prefs.mode = mode
        prefs.host = host
        prefs.port = port
        prefs.token = token
        prefs.ssl = ssl
        if (serverChanged) prefs.clearSession()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingModelProvider?.let { outState.putString("pending_model_provider", it) }
        pendingModelId?.let { outState.putString("pending_model_id", it) }
        pendingPersonaId?.let { outState.putString("pending_persona_id", it) }
    }

    /**
     * Fetch the hub's model list in the background (only when the app
     * is already configured — on first launch there is no token yet).
     * On failure (hub without the models endpoint, or unreachable) the
     * picker stays on "Hub default" only.
     */
    private fun loadModels() {
        if (!prefs.isConfigured || modelLoading) return
        modelLoading = true
        Thread {
            try {
                val result = ChatClient(prefs).models()
                runOnUiThread {
                    modelLoading = false
                    setModelOptions(result.options)
                }
            } catch (e: Exception) {
                runOnUiThread { modelLoading = false }
            }
        }.start()
    }

    private fun setModelOptions(options: List<ChatClient.ModelOption>) {
        modelOptions = options
        val labels = ArrayList<String>()
        labels.add(getString(R.string.model_hub_default))
        for (o in options) labels.add("${o.displayName} (${o.provider})")
        spinnerModel.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerModel.setSelection(selectedModelPosition(), false)
    }

    /** Spinner position for the pending pick, else the saved choice. */
    private fun selectedModelPosition(): Int {
        val prov = pendingModelProvider ?: prefs.modelProvider
        val id = pendingModelId ?: prefs.modelId
        if (prov.isBlank() || id.isBlank()) return 0
        val idx = modelOptions.indexOfFirst { it.provider == prov && it.id == id }
        return if (idx >= 0) idx + 1 else 0
    }

    /**
     * Fetch the hub's persona list in the background (only when the app
     * is already configured — on first launch there is no token yet).
     * On failure (hub without the personas endpoint, or unreachable) the
     * picker stays on "Main chat" only.
     */
    private fun loadPersonas() {
        if (!prefs.isConfigured || personaLoading) return
        personaLoading = true
        Thread {
            try {
                val result = ChatClient(prefs).personas()
                runOnUiThread {
                    personaLoading = false
                    setPersonaOptions(result)
                }
            } catch (e: Exception) {
                runOnUiThread { personaLoading = false }
            }
        }.start()
    }

    private fun setPersonaOptions(options: List<ChatClient.PersonaOption>) {
        personaOptions = options
        val labels = ArrayList<String>()
        labels.add(getString(R.string.persona_main_chat))
        for (o in options) labels.add(o.name)
        spinnerPersona.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerPersona.setSelection(selectedPersonaPosition(), false)
    }

    /** Spinner position for the pending pick, else the current session's persona. */
    private fun selectedPersonaPosition(): Int {
        val id = pendingPersonaId ?: prefs.personaId
        if (id.isBlank()) return 0
        val idx = personaOptions.indexOfFirst { it.id == id }
        return if (idx >= 0) idx + 1 else 0
    }

    /**
     * The two activation checks, in the background:
     * 1) plain GET /api/mobile/health — can we reach the server, and is
     *    the mobile app activated on the hub?
     * 2) encrypted POST /api/mobile/sessions — does the hub accept this
     *    token? (The hub can only answer if it decrypted the request
     *    with the stored token.) A fresh session id is kept, so the
     *    chat opens straight into it.
     * On success, and only on success, the app is activated: with
     * openChat=true it opens the chat; otherwise it just says so.
     */
    private fun verifyAndActivate(openChat: Boolean) {
        Toast.makeText(this, "Connecting…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val client = ChatClient(prefs)
                client.health()
                // Reuse the saved session when the server/token did not
                // change — creating a fresh session on every Save threw
                // the chat away (a model-only change reset the chat).
                // Only create a new session when there is none (first
                // setup, or the server/token changed and the saved
                // session was cleared, or the persona changed and
                // readAndSave cleared it). The new session is created
                // with the current persona, so a persona pick opens a
                // chat with that persona. history() is an encrypted call,
                // so it still proves the token is accepted.
                if (prefs.sessionId.isBlank()) {
                    prefs.sessionId = client.createSession(prefs.personaId)
                } else {
                    try {
                        client.history(prefs.sessionId)
                    } catch (e: Exception) {
                        // The saved session expired on the hub: start fresh.
                        prefs.sessionId = client.createSession(prefs.personaId)
                    }
                }
                runOnUiThread {
                    Toast.makeText(
                        this, "Connected — token accepted.",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Now that we are connected, the hub's model and
                    // persona lists can load — so the pickers fill in
                    // even if this was the first connection.
                    loadModels()
                    loadPersonas()
                    if (openChat) {
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this, "Could not connect: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }
}
