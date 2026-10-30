package app.cloudplayx.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URLConnection

/**
 * Built-in cloud browser. Tap a folder to open it; tap a file to see what CloudPlayX can do with it
 * (compatibility, free space) and to download it into the library or open it directly.
 */
class BrowserActivity : Activity() {
    private lateinit var u: Ui
    private lateinit var settings: Settings
    private lateinit var pathText: TextView
    private lateinit var status: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var downloadsBtn: android.widget.Button
    private var currentId = "root"
    private val parents = mutableListOf<Pair<String, String>>()

    /** Incremented per request so a slow answer for an old folder can never overwrite a newer screen. */
    @Volatile private var generation = 0
    private val listingCache = HashMap<String, Pair<Long, List<RemoteEntry>>>()
    private val jobListener: () -> Unit = { ui { refreshDownloadsButton() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        u = Ui(this)
        settings = Settings(this)
        currentId = if (settings.backend == "webdav") "/" else "root"
        u.styleWindow(this)
        val p = u.p

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u.dp(16), u.dp(16), u.dp(16), u.dp(16))
            setBackgroundColor(p.bg)
        }
        root.addView(u.label("Cloud files", 25f, p.text, true))
        pathText = u.label("/", 13f, p.muted)
        root.addView(pathText)

        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolbar.addView(u.button("← Back") { goBack() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(u.button("↻ Refresh", BtnKind.PRIMARY) { load(force = true) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = u.dp(8) })
        downloadsBtn = u.button("Downloads", BtnKind.MINT) { startActivity(Intent(this, LibraryActivity::class.java)) }
        toolbar.addView(downloadsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = u.dp(8) })
        root.addView(toolbar)

        status = u.label("Loading cloud files…", 13f, p.muted).apply { setPadding(0, u.dp(9), 0, u.dp(9)) }
        root.addView(status)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(listBox)
            isFillViewport = true
            clipToPadding = false
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(u.label("Read-only: nothing in your cloud is changed. Tap a file for download options.", 12f, p.muted))
        setContentView(root)
        load()
    }

    override fun onStart() {
        super.onStart()
        Jobs.center.addListener(jobListener)
        refreshDownloadsButton()
    }

    override fun onStop() {
        Jobs.center.removeListener(jobListener)
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (parents.isNotEmpty()) goBack() else super.onBackPressed()
    }

    private fun refreshDownloadsButton() {
        val n = Jobs.center.activeCount
        downloadsBtn.text = if (n > 0) "Downloads ($n)" else "Downloads"
    }

    private fun backendName() = if (settings.backend == "gdrive") "Google Drive" else "WebDAV"

    private fun load(force: Boolean = false) {
        pathText.text = "/" + parents.joinToString("/") { it.second }
        val id = currentId
        val cached = listingCache[id]
        if (!force && cached != null && System.currentTimeMillis() - cached.first < 60_000L) {
            render(cached.second)
            return
        }
        status.text = "Loading…"
        listBox.removeAllViews()
        listBox.addView(u.label("Fetching file list…", 14f, u.p.muted))
        val g = ++generation
        runBg {
            var error: String? = null
            var list: List<RemoteEntry>? = null
            try {
                val remote = RemoteFactory.create(applicationContext, settings)
                    ?: throw RemoteException("No cloud is selected. Go back and connect a provider.")
                list = remote.list(id).sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name.lowercase() })
            } catch (e: Exception) {
                error = ErrorText.of(e)
            }
            ui {
                if (g != generation) return@ui // the user already moved on
                val result = list
                if (result == null) {
                    status.text = "Could not load ${backendName()} files"
                    listBox.removeAllViews()
                    listBox.addView(u.label(error ?: "Unknown connection error", 14f, u.p.danger).apply { setTextIsSelectable(true) })
                    listBox.addView(u.button("Try again", BtnKind.PRIMARY) { load(force = true) })
                } else {
                    listingCache[id] = System.currentTimeMillis() to result
                    render(result)
                }
            }
        }
    }

    private fun render(result: List<RemoteEntry>) {
        listBox.removeAllViews()
        status.text = "${result.size} item(s) · ${backendName()}"
        if (result.isEmpty()) listBox.addView(u.label("This folder is empty.", 14f, u.p.muted))
        result.forEach { addEntryRow(it) }
    }

    private fun addEntryRow(entry: RemoteEntry) {
        val p = u.p
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u.dp(12), u.dp(11), u.dp(12), u.dp(11))
            minimumHeight = u.dp(56)
            background = u.rounded(p.panel, 14)
            isClickable = true
            isFocusable = true
            contentDescription = (if (entry.isDir) "Folder " else "File ") + entry.name
        }
        row.addView(u.label(if (entry.isDir) "📁" else iconFor(entry), 23f, p.text).apply { importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(u.dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(u.label(entry.name, 14f, p.text, true))
        if (entry.isDir) {
            textColumn.addView(u.label("Folder", 12f, p.muted))
        } else {
            val v = GameCompat.assess(entry.name, entry.mime)
            val badge = when (v.support) { Support.SUPPORTED -> "✔ supported"; Support.PARTIAL -> "◐ partial"; Support.UNSUPPORTED -> "✖ not runnable on Android" }
            textColumn.addView(u.label("${Formatter.formatFileSize(this, entry.size)} · $badge", 12f, when (v.support) {
                Support.SUPPORTED -> p.ok; Support.PARTIAL -> p.warn; Support.UNSUPPORTED -> p.danger
            }))
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(u.label("›", 25f, p.muted))
        row.setOnClickListener {
            if (entry.isDir) {
                parents.add(currentId to entry.name)
                currentId = entry.id
                load()
            } else showFileDialog(entry)
        }
        listBox.addView(row, u.margins(bottom = 8))
    }

    private fun showFileDialog(entry: RemoteEntry) {
        val v = GameCompat.assess(entry.name, entry.mime)
        val store = LibraryHolder.get(this)
        val plan = store.plan(settings.backend, entry)
        val storage = GameCompat.storage(entry.size, store.root.usableSpace)
        val sb = StringBuilder()
        sb.append(v.title).append(" · ").append(
            when (v.support) { Support.SUPPORTED -> "Supported"; Support.PARTIAL -> "Partially supported"; Support.UNSUPPORTED -> "Not supported on Android" },
        ).append("\n\n").append(v.detail).append("\n\n")
        sb.append("Size: ").append(GameCompat.human(entry.size)).append('\n')
        sb.append(storage.message).append('\n')
        if (entry.md5 != null) sb.append("The download will be verified against Google's checksum.\n")
        if (plan.upToDate) sb.append("\nAlready downloaded and unchanged: ").append(plan.target.name)
        else if (plan.target.exists() && store.metaOf(plan.target) != null) sb.append("\nThe cloud file changed since your last download; downloading replaces the old copy.")

        val downloadLabel = when {
            plan.upToDate -> "Open library"
            v.support == Support.UNSUPPORTED -> "Download anyway"
            else -> "Download"
        }
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(sb.toString())
            .setPositiveButton(downloadLabel) { _, _ ->
                if (plan.upToDate) startActivity(Intent(this, LibraryActivity::class.java)) else startDownload(entry)
            }
            .setNeutralButton("More…") { _, _ -> showMoreOptions(entry, v) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showMoreOptions(entry: RemoteEntry, v: Verdict) {
        if (!v.canBrowseInside) { openStreamed(entry); return }
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf("Choose files inside to download (saves space)", "Open directly (stream, no download)")) { _, which ->
                if (which == 0) chooseInside(entry) else openStreamed(entry)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Reads only the archive's index from the cloud, then lets the user tick the files to download. */
    private fun chooseInside(entry: RemoteEntry) {
        u.toast("Reading the archive index (a small part of the file)…")
        runBg {
            var items: List<ZipItem>? = null
            var error: String? = null
            try {
                val remote = RemoteFactory.create(applicationContext, settings) ?: throw RemoteException("No cloud is selected.")
                items = RemoteZip.list(remote, entry).filter { !it.isDir }
            } catch (e: Exception) {
                error = ErrorText.of(e)
            }
            ui {
                val list = items
                if (list == null) {
                    AlertDialog.Builder(this).setTitle("Cannot read inside this file")
                        .setMessage((error ?: "Unknown error") + "\n\nYou can still download the whole file.")
                        .setPositiveButton("OK", null).show()
                } else if (list.isEmpty()) {
                    u.toast("The archive contains no files.")
                } else showSelection(entry, list)
            }
        }
    }

    private fun showSelection(entry: RemoteEntry, list: List<ZipItem>) {
        val store = LibraryHolder.get(this)
        val free = store.root.usableSpace
        val checked = BooleanArray(list.size)
        val labels = list.map { "${it.name}  ·  ${GameCompat.human(it.size)}" }.toTypedArray()
        fun title(): String {
            val sel = list.filterIndexed { i, _ -> checked[i] }
            val need = sel.sumOf { it.size }
            val verdict = GameCompat.storage(need, free)
            return "${sel.size} of ${list.size} selected · ${GameCompat.human(need)} · ${GameCompat.human(free)} free" +
                if (verdict.level == StorageLevel.OK) "" else "\n${verdict.message}"
        }
        lateinit var dialog: AlertDialog
        dialog = AlertDialog.Builder(this)
            .setTitle(title())
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                checked[i] = on
                dialog.setTitle(title())
            }
            .setPositiveButton("Download selected") { _, _ ->
                val sel = list.filterIndexed { i, _ -> checked[i] }
                if (sel.isEmpty()) u.toast("Nothing selected.") else {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
                    }
                    Transfers.extractSelected(this, entry, sel)
                    u.toast("Started. Only the selected files are downloaded.")
                    refreshDownloadsButton()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun startDownload(entry: RemoteEntry) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77) // progress notification; downloads work without it
        }
        Transfers.download(this, entry)
        u.toast("Download started: ${entry.name}. Progress is in Downloads.")
        refreshDownloadsButton()
    }

    /** Streams the file straight from the cloud through the DocumentsProvider (nothing is saved in the library). */
    private fun openStreamed(entry: RemoteEntry) {
        val uri = DocumentsContract.buildDocumentUri(CloudProvider.AUTHORITY, entry.id)
        val mime = entry.mime ?: URLConnection.guessContentTypeFromName(entry.name) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (_: Exception) {
            u.toast("No installed app can open this file type directly. Download it instead.", long = true)
        }
    }

    private fun goBack() {
        if (parents.isEmpty()) {
            finish()
            return
        }
        currentId = parents.removeAt(parents.lastIndex).first
        load()
    }

    private fun iconFor(entry: RemoteEntry): String = when {
        entry.mime?.startsWith("image/") == true -> "🖼️"
        entry.mime?.startsWith("audio/") == true -> "🎵"
        entry.mime?.startsWith("video/") == true -> "🎬"
        GameCompat.extensionOf(entry.name) in setOf("zip", "7z", "rar", "xapk", "apks") -> "📦"
        GameCompat.extensionOf(entry.name) == "apk" -> "🎮"
        GameCompat.extensionOf(entry.name) in setOf("txt", "md", "pdf", "doc", "docx") -> "📄"
        else -> "📃"
    }
}
