package app.cloudplayx.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.auth.api.identity.Identity

/** Home screen: cloud connection, downloads/library entry point, WebDAV setup and cache controls. */
class MainActivity : Activity() {
    private companion object {
        const val REQ_AUTH = 1001
        const val REQ_PICK = 1003
    }

    private lateinit var u: Ui
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var transfersInfo: TextView
    private lateinit var libraryInfo: TextView
    private lateinit var cacheInfo: TextView
    private lateinit var urlEdit: EditText
    private lateinit var userEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var budgetEdit: EditText
    private lateinit var ageEdit: EditText
    private lateinit var libLimitEdit: EditText
    private lateinit var connectBtn: Button
    private lateinit var testBtn: Button
    private lateinit var browseBtn: Button
    private lateinit var disconnectBtn: Button
    private var connectionMessage: String? = null
    private var connectionState = 0 // 0 neutral, 1 ok, -1 problem
    private val jobListener: () -> Unit = { ui { refreshTransfers() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        u = Ui(this)
        settings = Settings(this)
        u.styleWindow(this)
        val p = u.p

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u.dp(18), u.dp(18), u.dp(18), u.dp(28))
            setBackgroundColor(p.bg)
        }
        content.addView(u.label("CLOUDPLAYX", 13f, p.mint, bold = true))
        content.addView(u.label("Your cloud, one place.", 28f, p.text, bold = true).apply { setPadding(0, u.dp(6), 0, u.dp(3)) })
        content.addView(u.label("Browse your cloud, download and verify game files, extract them and open them.", 14f, p.muted))

        // ---- cloud connection ----
        val connectionCard = u.card()
        connectionCard.addView(u.label("CLOUD CONNECTION", 12f, p.mint, bold = true))
        status = u.label("", 14f, p.text).apply { setPadding(0, u.dp(9), 0, u.dp(10)); setTextIsSelectable(true) }
        connectionCard.addView(status)
        connectBtn = u.button("Connect Google Drive", BtnKind.PRIMARY) { signInGoogle() }
        testBtn = u.button("Test connection") { testConnection() }
        browseBtn = u.button("Browse cloud files", BtnKind.MINT) { openBrowser() }
        disconnectBtn = u.button("Disconnect this phone") { confirmDisconnect() }
        connectionCard.addView(connectBtn)
        connectionCard.addView(testBtn)
        connectionCard.addView(browseBtn)
        connectionCard.addView(disconnectBtn)
        content.addView(connectionCard, u.margins(top = 14))

        // ---- games & downloads ----
        val libCard = u.card()
        libCard.addView(u.label("GAMES & DOWNLOADS", 12f, p.mint, bold = true))
        libraryInfo = u.label("", 13f, p.muted).apply { setPadding(0, u.dp(6), 0, u.dp(2)) }
        transfersInfo = u.label("", 13f, p.muted)
        libCard.addView(libraryInfo)
        libCard.addView(transfersInfo)
        libCard.addView(u.button("Open downloads & library", BtnKind.PRIMARY) { startActivity(Intent(this, LibraryActivity::class.java)) })
        libCard.addView(u.button("Test the Android file picker") { openSystemPicker() })
        content.addView(libCard, u.margins(top = 14))

        // ---- google sign-in troubleshooting ----
        val sha = SigningInfo.sha1(this)
        val helpCard = u.card()
        helpCard.addView(u.label("GOOGLE SIGN-IN SETUP", 12f, p.mint, bold = true))
        helpCard.addView(u.label(
            "Google identifies this app by its package name and the SHA-1 of the key that signed this APK. " +
                "In Google Cloud Console create an OAuth client of type Android with exactly these two values, enable the Google Drive API, " +
                "and add your Google account as a test user on the OAuth consent screen.",
            13f, p.muted,
        ).apply { setPadding(0, u.dp(6), 0, u.dp(6)) })
        helpCard.addView(u.label("Package name\n$packageName", 13f, p.text).apply { setTextIsSelectable(true) })
        helpCard.addView(u.label("Signing SHA-1\n${sha ?: "unavailable"}", 13f, p.text).apply { setTextIsSelectable(true); setPadding(0, u.dp(8), 0, u.dp(3)) })
        helpCard.addView(u.button("Copy SHA-1", enabled = sha != null) { copy("SHA-1", sha ?: "") })
        helpCard.addView(u.button("Manage Google permissions for this app") { openUrl("https://myaccount.google.com/permissions") })
        content.addView(helpCard, u.margins(top = 14))

        // ---- webdav ----
        val webCard = u.card()
        webCard.addView(u.label("WEBDAV SERVER", 12f, p.mint, bold = true))
        webCard.addView(u.label("Optional · Nextcloud, rclone WebDAV and compatible servers. Use https://, or http:// only on your own network.", 13f, p.muted))
        urlEdit = u.field("Server URL · https://dav.example.com", settings.webDavUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        userEdit = u.field("Username (optional)", settings.webDavUser, InputType.TYPE_CLASS_TEXT)
        val hasPass = settings.webDavPass.isNotEmpty()
        passEdit = u.field(if (hasPass) "Password (saved, encrypted · leave blank to keep)" else "Password (optional)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        webCard.addView(urlEdit)
        webCard.addView(userEdit)
        webCard.addView(passEdit)
        webCard.addView(u.button("Use this WebDAV server") { saveWebDav() })
        content.addView(webCard, u.margins(top = 14))

        // ---- streaming cache ----
        val cacheCard = u.card()
        cacheCard.addView(u.label("STREAMING CACHE", 12f, p.mint, bold = true))
        cacheCard.addView(u.label("Used when other apps read cloud files directly through the file picker. Downloaded library files are separate and are never removed automatically.", 13f, p.muted))
        budgetEdit = u.field("Cache budget in GB", (settings.cacheBudgetMb / 1024.0).toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        ageEdit = u.field("Remove unused blocks after (days)", settings.maxAgeDays.toString(), InputType.TYPE_CLASS_NUMBER)
        libLimitEdit = u.field("Downloads library limit in GB (0 = no limit)", (settings.libraryLimitMb / 1024.0).toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        cacheCard.addView(budgetEdit)
        cacheCard.addView(ageEdit)
        cacheCard.addView(libLimitEdit)
        cacheCard.addView(u.label("The library limit only stops new downloads that would exceed it. CloudPlayX never deletes your downloaded files by itself; only leftover temporary data (unfinished downloads older than 7 days, crashed extractions) is cleaned automatically.", 12f, p.muted).apply { setPadding(0, u.dp(4), 0, u.dp(2)) })
        cacheCard.addView(u.button("Save cache settings") { saveCacheSettings() })
        cacheInfo = u.label("", 13f, p.muted).apply { setPadding(0, u.dp(10), 0, u.dp(4)) }
        cacheCard.addView(cacheInfo)
        cacheCard.addView(u.button("Clear streaming cache", BtnKind.DANGER) { clearCache() })
        content.addView(cacheCard, u.margins(top = 14))

        content.addView(u.label("v${BuildConfig.VERSION_NAME} · build ${BuildConfig.GIT_SHA} · No root required · Android 8+", 12f, p.muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, u.dp(10), 0, u.dp(4))
        })

        setContentView(ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setBackgroundColor(p.bg)
        })
    }

    override fun onStart() {
        super.onStart()
        Jobs.center.addListener(jobListener)
    }

    override fun onStop() {
        Jobs.center.removeListener(jobListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Housekeeping that is always safe: removes only stale temporary files (see LibraryStore.cleanTemp).
        runBg { LibraryHolder.get(applicationContext).cleanTemp(7 * 86_400_000L) }
        refresh()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_AUTH -> handleAuthResult(resultCode, data)
            REQ_PICK -> if (resultCode == RESULT_OK && data?.data != null) probePickedFile(data.data!!)
        }
    }

    private fun handleAuthResult(resultCode: Int, data: Intent?) {
        if (resultCode != RESULT_OK || data == null) {
            fail("Google authorization was cancelled or did not complete (result code $resultCode). Tap Connect Google Drive to try again.")
            return
        }
        try {
            val authResult = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data)
            val accessToken = authResult.accessToken
            if (accessToken.isNullOrBlank()) {
                fail("Google completed the permission screen but returned no access token. Check the OAuth client (package name + SHA-1 shown below) and try again.")
                return
            }
            GoogleAuth.remember(accessToken)
            useBackend("gdrive")
        } catch (e: Exception) {
            fail(GoogleAuth.describe(this, e))
        }
    }

    private fun signInGoogle() {
        connectionState = 0
        connectionMessage = "Opening Google authorization…"
        refreshStatus()
        Identity.getAuthorizationClient(this).authorize(GoogleAuth.request())
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    try {
                        @Suppress("DEPRECATION")
                        startIntentSenderForResult(pending.intentSender, REQ_AUTH, null, 0, 0, 0)
                    } catch (e: Exception) {
                        fail("Could not open the Google permission screen: ${e.message ?: e.javaClass.simpleName}")
                    }
                } else {
                    val accessToken = result.accessToken
                    if (accessToken.isNullOrBlank()) {
                        fail("Google returned no access token. Check the OAuth client (package name + SHA-1 shown below).")
                    } else {
                        GoogleAuth.remember(accessToken)
                        useBackend("gdrive")
                    }
                }
            }
            .addOnFailureListener { e -> fail(GoogleAuth.describe(this, e)) }
    }

    private fun fail(message: String) {
        connectionState = -1
        connectionMessage = message
        refreshStatus()
        u.toast(message, long = true)
    }

    private fun saveWebDav() {
        val url = urlEdit.text.toString().trim()
        val problem = UrlPolicy.checkWebDav(url)
        if (problem != null) {
            u.toast(problem, long = true)
            return
        }
        settings.webDavUrl = url
        settings.webDavUser = userEdit.text.toString().trim()
        val pass = passEdit.text.toString()
        if (pass.isNotEmpty()) settings.webDavPass = pass
        passEdit.setText("")
        useBackend("webdav")
    }

    private fun useBackend(name: String) {
        if (name != "gdrive") GoogleAuth.forget()
        settings.backend = name
        settings.configVersion = settings.configVersion + 1
        connectionState = 0
        connectionMessage = "${if (name == "gdrive") "Google Drive" else "WebDAV"} selected · checking connection…"
        notifyRoots()
        refreshStatus()
        refreshCacheInfo()
        testConnection()
    }

    private fun testConnection() {
        val backend = settings.backend
        if (backend.isBlank()) {
            connectionState = 0
            connectionMessage = "Choose Google Drive or configure a WebDAV server first."
            refreshStatus()
            return
        }
        connectionState = 0
        connectionMessage = "Testing ${if (backend == "gdrive") "Google Drive" else "WebDAV"}…"
        refreshStatus()
        runBg {
            var ok = false
            val result = try {
                val remote = RemoteFactory.create(applicationContext, settings) ?: throw RemoteException("Choose a cloud first.")
                val count = remote.list(remote.rootId).size
                ok = true
                if (remote is GoogleDriveRemote) "Connected · Google Drive · $count item(s) in root folder\n${remote.accountSummary()}"
                else "Connected · WebDAV · $count item(s) in root folder"
            } catch (e: Exception) {
                "Connection failed · ${ErrorText.of(e)}"
            }
            ui {
                connectionState = if (ok) 1 else -1
                connectionMessage = result
                refreshStatus()
                if (!ok) u.toast(result, long = true)
            }
        }
    }

    private fun openBrowser() {
        if (settings.backend.isBlank()) {
            u.toast("Connect Google Drive or configure a WebDAV server first.")
            return
        }
        startActivity(Intent(this, BrowserActivity::class.java))
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect this phone?")
            .setMessage("CloudPlayX forgets the selected cloud and any saved WebDAV password on this phone and clears the streaming cache. Files you already downloaded stay in your library. Your cloud files are not touched.\n\nTo also revoke Google's permission, use \"Manage Google permissions\".")
            .setPositiveButton("Disconnect") { _, _ ->
                settings.disconnect()
                runBg { CacheHolder.get(applicationContext).clear(); ui { refreshCacheInfo() } }
                notifyRoots()
                connectionState = 0
                connectionMessage = "Disconnected. Choose a cloud to connect."
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openSystemPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
            u.toast("In the picker, open the menu and choose CloudPlayX or CloudPlayX Library, then pick a file to test reading it.", long = true)
        } catch (e: Exception) {
            u.toast("Android file picker could not be opened: ${e.message}", long = true)
        }
    }

    /** Reads the start of a file chosen in the system picker: a real end-to-end test of the DocumentsProvider path. */
    private fun probePickedFile(uri: Uri) {
        runBg {
            val msg = try {
                var name = "file"
                var size = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: name
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                val buf = ByteArray(64 * 1024)
                val n = contentResolver.openInputStream(uri)?.use { it.read(buf) } ?: throw java.io.IOException("The provider returned no data.")
                "Read OK: $name${if (size >= 0) " (${GameCompat.human(size)})" else ""} · first $n byte(s) received through Android's file picker."
            } catch (e: Exception) {
                "Reading the picked file failed: ${ErrorText.of(e)}"
            }
            ui { u.toast(msg, long = true) }
        }
    }

    private fun saveCacheSettings() {
        val gb = budgetEdit.text.toString().toDoubleOrNull()
        val days = ageEdit.text.toString().toIntOrNull()
        val limitGb = libLimitEdit.text.toString().ifBlank { "0" }.toDoubleOrNull()
        if (gb == null || gb < 0.1 || gb > 2048 || days == null || days < 1 || days > 3650 || limitGb == null || limitGb < 0 || limitGb > 100000) {
            u.toast("Enter a cache budget from 0.1 to 2048 GB, unused age from 1 to 3650 days, and a library limit of 0 or more GB.")
            return
        }
        settings.libraryLimitMb = (limitGb * 1024).toLong()
        settings.cacheBudgetMb = (gb * 1024).toLong()
        settings.maxAgeDays = days
        runBg {
            val cache = CacheHolder.get(applicationContext)
            cache.trimByAge(days * 86_400_000L)
            cache.trimToBudget()
            ui { u.toast("Cache settings saved."); refreshCacheInfo() }
        }
    }

    private fun clearCache() {
        runBg {
            CacheHolder.get(applicationContext).clear()
            ui { u.toast("Streaming cache cleared. Cloud files and your library are untouched."); refreshCacheInfo() }
        }
    }

    private fun copy(label: String, text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        u.toast("Copied.")
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            u.toast("No browser found to open $url", long = true)
        }
    }

    private fun notifyRoots() {
        try { contentResolver.notifyChange(DocumentsContract.buildRootsUri(CloudProvider.AUTHORITY), null) } catch (_: Exception) { }
    }

    // ---- state -> UI ----

    private fun refresh() {
        refreshStatus()
        refreshCacheInfo()
        refreshLibraryInfo()
        refreshTransfers()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val backend = settings.backend
        status.text = connectionMessage ?: when (backend) {
            "gdrive" -> "Google Drive selected · tap Test connection"
            "webdav" -> "WebDAV selected · tap Test connection"
            else -> "No cloud connected yet. Tap Connect Google Drive, or set up WebDAV below."
        }
        status.setTextColor(when (connectionState) { 1 -> u.p.ok; -1 -> u.p.danger; else -> u.p.text })
        val connected = backend.isNotBlank()
        connectBtn.text = if (backend == "gdrive") "Reconnect Google Drive" else "Connect Google Drive"
        u.setEnabled(testBtn, connected)
        u.setEnabled(browseBtn, connected)
        u.setEnabled(disconnectBtn, connected)
    }

    private fun refreshCacheInfo() {
        if (!::cacheInfo.isInitialized) return
        runBg {
            val used = CacheHolder.get(applicationContext).usedBytes
            val budget = settings.budgetBytes
            ui { cacheInfo.text = "Using ${Formatter.formatFileSize(this, used)} of ${Formatter.formatFileSize(this, budget)}" }
        }
    }

    private fun refreshLibraryInfo() {
        if (!::libraryInfo.isInitialized) return
        runBg {
            val store = LibraryHolder.get(applicationContext)
            val items = store.list().size
            val used = store.usedBytes()
            val free = store.root.usableSpace
            ui { libraryInfo.text = "$items item(s) in your library · ${GameCompat.human(used)} used · ${GameCompat.human(free)} free on this phone" }
        }
    }

    private fun refreshTransfers() {
        if (!::transfersInfo.isInitialized) return
        val active = Jobs.center.snapshot().filter { it.isActive }
        transfersInfo.text = if (active.isEmpty()) "No transfers running." else "${active.size} transfer(s) running or waiting."
        if (active.isEmpty()) refreshLibraryInfo()
    }
}
