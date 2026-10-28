package com.sos.nearby

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var alerts: TextView
    private lateinit var send: Button
    private var profileDialog: AlertDialog? = null
    private var soundDialog: AlertDialog? = null
    private var locationDialog: AlertDialog? = null
    private var speechStatusView: TextView? = null
    private var service: SosService? = null
    private var bound = false
    private var visible = false
    private var voiceSettingsOpened = false
    private var permissionRequestInFlight = false
    private val profile get() = getSharedPreferences("profile", MODE_PRIVATE)
    private val changed: (RelayState) -> Unit = { render(it) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!visible) return
            service = (binder as SosService.LocalBinder).service
            service?.addListener(changed)
            if (voiceSettingsOpened) { service?.refreshSpeech(); voiceSettingsOpened = false }
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null; if (visible) status.setText(R.string.background_stopped) }
    }
    override fun attachBaseContext(base: Context) {
        val locale = Locale.forLanguageTag("fa-IR")
        val config = Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        super.attachBaseContext(base.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val content = findViewById<LinearLayout>(R.id.content)
        val padding = (24 * resources.displayMetrics.density).toInt()
        findViewById<ScrollView>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            content.setPadding(padding + insets.systemWindowInsetLeft, padding + insets.systemWindowInsetTop,
                padding + insets.systemWindowInsetRight, padding + insets.systemWindowInsetBottom)
            insets
        }
        status = findViewById(R.id.status); alerts = findViewById(R.id.alerts); send = findViewById(R.id.send)
        findViewById<Button>(R.id.connect).setOnClickListener { ensureListening(true) }
        findViewById<Button>(R.id.sound_settings).setOnClickListener {
            val options = mutableListOf(getString(R.string.edit_profile), getString(R.string.sound_settings))
            if (service?.snapshot()?.foreground == true) options.add(getString(R.string.stop_background))
            AlertDialog.Builder(this).setTitle(R.string.settings).setItems(options.toTypedArray()) { _, item ->
                when (item) { 0 -> showProfileSetup(); 1 -> showSoundSettings(); 2 -> service?.stopListening() }
            }.setNegativeButton(R.string.close, null).show()
        }
        send.setOnClickListener {
            val current = service
            if (current?.snapshot()?.own != null) { current.stopOwn(); return@setOnClickListener }
            val person = profile.getString("name", "").orEmpty()
            val home = profile.getString("unit", "").orEmpty()
            if (!SosMessage.validProfile(person, home)) { showProfileSetup(); return@setOnClickListener }
            if (current?.snapshot()?.radioRunning != true || current.snapshot().foreground != true) {
                ensureListening(true); return@setOnClickListener
            }
            AlertDialog.Builder(this).setTitle(getString(R.string.confirm_title, person, home))
                .setMessage(R.string.confirm_body).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_send) { _, _ -> service?.sendOwn(person, home) }.show()
        }
        if (!SosMessage.validProfile(profile.getString("name", "").orEmpty(), profile.getString("unit", "").orEmpty())) {
            findViewById<ScrollView>(R.id.root).post { if (!isFinishing && !isDestroyed) showProfileSetup() }
        }
    }
    private fun ensureListening(ask: Boolean) {
        if (permissionRequestInFlight) return
        if (ask) profile.edit().putBoolean("relay_enabled", true).apply()
        if (!SosService.foregroundPermissionsGranted(this)) {
            if (ask) { permissionRequestInFlight = true; requestPermissions(SosService.bluetoothPermissions(), 1) }
            return
        }
        if (!SosService.backgroundPermissionGranted(this)) {
            status.setText(R.string.background_location_help)
            if (ask && Build.VERSION.SDK_INT == 29) {
                permissionRequestInFlight = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            } else if (ask && locationDialog?.isShowing != true) {
                locationDialog = AlertDialog.Builder(this).setTitle(R.string.background_location_title)
                    .setMessage(R.string.background_location_help).setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }.show()
            }
            return
        }
        if (ask && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !profile.getBoolean("notifications_asked", false)) {
            profile.edit().putBoolean("notifications_asked", true).apply()
            permissionRequestInFlight = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        profile.edit().putBoolean("relay_enabled", true).apply()
        try {
            if (service?.snapshot()?.foreground != true) status.setText(R.string.background_starting)
            startForegroundService(Intent(this, SosService::class.java).setAction(SosService.ACTION_START))
        } catch (_: RuntimeException) { status.setText(R.string.background_start_failed) }
    }
    private fun render(state: RelayState) {
        val manager = getSystemService(NotificationManager::class.java)
        val blocked = !manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(SosService.ALERT_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        val connection = when {
            !SosService.foregroundPermissionsGranted(this) -> getString(R.string.status_initial)
            !SosService.backgroundPermissionGranted(this) -> getString(R.string.background_location_help)
            else -> state.status
        } + if (state.foreground && blocked) "\n" + getString(R.string.notifications_off) else ""
        if (status.text.toString() != connection) status.text = connection
        send.setText(if (state.own != null) R.string.stop_sos else R.string.send_sos)
        findViewById<Button>(R.id.connect).setText(if (state.foreground) R.string.check_connection else R.string.connect)
        val text = if (state.messages.isEmpty()) getString(R.string.no_alerts) else state.messages.joinToString("\n\n") {
            getString(R.string.alert_entry, it.name, it.unit, getString(if (it.hops == 0) R.string.direct else R.string.relayed))
        }
        if (alerts.text.toString() != text) alerts.text = text
        speechStatusView?.text = state.speechStatus
    }
    private fun showProfileSetup() {
        if (profileDialog?.isShowing == true) return
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, padding) }
        layout.addView(TextView(this).apply { setText(R.string.profile_once); textSize = 16f; gravity = android.view.Gravity.START; textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START; setPadding(0, padding / 2, 0, padding / 2) })
        val person = EditText(this).apply {
            setHint(R.string.name_hint); setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setText(profile.getString("name", "")); layout.addView(this)
        }
        val home = EditText(this).apply { setHint(R.string.unit_hint); setSingleLine(); setText(profile.getString("unit", "")); layout.addView(this) }
        val error = TextView(this).apply { gravity = android.view.Gravity.START; textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START; setTextColor(android.graphics.Color.rgb(181,44,54)); layout.addView(this) }
        profileDialog = AlertDialog.Builder(this).setTitle(R.string.profile_title)
            .setView(ScrollView(this).apply { addView(layout) })
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save_profile, null).create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val savedName = person.text.toString().trim()
                        val savedUnit = home.text.toString().trim()
                        if (!SosMessage.validProfile(savedName, savedUnit)) {
                            error.setText(R.string.invalid_profile)
                        } else {
                            profile.edit().putString("name", savedName).putString("unit", savedUnit).apply()
                            dismiss()
                        }
                    }
                }
                show()
            }
    }
    private fun showSoundSettings() {
        if (soundDialog?.isShowing == true) return
        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, padding) }
        fun label(text: CharSequence) = TextView(this).apply {
            this.text = text; textSize = 15f; gravity = android.view.Gravity.START; textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            setPadding(0, padding / 2, 0, padding / 2); layout.addView(this)
        }
        speechStatusView = label(service?.snapshot()?.speechStatus ?: getString(R.string.voice_checking))
        layout.addView(Switch(this).apply {
            setText(R.string.speech_enabled); minHeight = padding * 2
            isChecked = profile.getBoolean("speechEnabled", true)
            setOnCheckedChangeListener { _, enabled -> profile.edit().putBoolean("speechEnabled", enabled).apply() }
        })
        layout.addView(Button(this).apply { setText(R.string.test_voice); setOnClickListener { service?.testSpeech() } })
        layout.addView(Button(this).apply {
            setText(R.string.voice_settings)
            setOnClickListener {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")); voiceSettingsOpened = true }
                catch (_: ActivityNotFoundException) { speechStatusView?.setText(R.string.voice_settings_missing) }
            }
        })
        label(getString(R.string.voice_help))
        soundDialog = AlertDialog.Builder(this).setTitle(R.string.sound_title)
            .setView(ScrollView(this).apply { addView(layout) }).setPositiveButton(R.string.close, null).create().apply {
                setOnDismissListener { speechStatusView = null }; show()
            }
    }
    override fun onStart() {
        super.onStart(); visible = true
        bound = bindService(Intent(this, SosService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onResume() {
        super.onResume()
        if (voiceSettingsOpened && service != null) { service?.refreshSpeech(); voiceSettingsOpened = false }
        if (profile.getBoolean("relay_enabled", true)) ensureListening(false)
    }
    override fun onStop() {
        visible = false
        service?.removeListener(changed)
        if (bound) { unbindService(connection); bound = false }
        service = null
        // The started foreground service owns radio and speech; leaving the UI must not stop them.
        super.onStop()
    }
    override fun onDestroy() {
        profileDialog?.dismiss(); soundDialog?.dismiss(); locationDialog?.dismiss()
        super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionRequestInFlight = false
        if (!SosService.foregroundPermissionsGranted(this)) { status.setText(R.string.permission_denied); return }
        if (requestCode == 2 && !SosService.backgroundPermissionGranted(this)) { status.setText(R.string.background_location_help); return }
        ensureListening(requestCode != 3)
    }
}
