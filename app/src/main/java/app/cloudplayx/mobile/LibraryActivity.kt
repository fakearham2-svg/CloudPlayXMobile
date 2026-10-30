package app.cloudplayx.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Downloads and library: live transfers (progress, speed, ETA, cancel, retry), downloaded files with what can be
 * done with each (install APK, extract ZIP, open, copy to a folder, delete), and a launcher for installed apps.
 */
class LibraryActivity : Activity() {
    private companion object { const val REQ_TREE = 2001 }

    private lateinit var u: Ui
    private lateinit var store: LibraryStore
    private lateinit var storageInfo: TextView
    private lateinit var transferBox: LinearLayout
    private lateinit var fileBox: LinearLayout
    private lateinit var partialBtn: android.widget.Button
    private var pendingExport: File? = null
    private var pendingMove = false
    private val main = Handler(Looper.getMainLooper())
    private var refreshPosted = false
    private var lastActive = -1
    private val jobListener: () -> Unit = { ui { scheduleRefresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        u = Ui(this)
        store = LibraryHolder.get(this)
        u.styleWindow(this)
        val p = u.p
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u.dp(16), u.dp(16), u.dp(16), u.dp(24))
            setBackgroundColor(p.bg)
        }
        content.addView(u.label("Downloads & library", 25f, p.text, true))
        storageInfo = u.label("", 13f, p.muted)
        content.addView(storageInfo)
        content.addView(u.button("Launch an installed app or game", BtnKind.PRIMARY) { showLauncher() })
        content.addView(u.button("Show library in Android Files app") { openInFilesApp() })
        partialBtn = u.button("Clear unfinished downloads", BtnKind.DANGER) { confirmClearPartials() }
        content.addView(partialBtn)

        content.addView(u.label("TRANSFERS", 12f, p.mint, true).apply { setPadding(0, u.dp(16), 0, u.dp(4)) })
        transferBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(transferBox)
        content.addView(u.label("LIBRARY", 12f, p.mint, true).apply { setPadding(0, u.dp(16), 0, u.dp(4)) })
        fileBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(fileBox)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onStart() {
        super.onStart()
        Jobs.center.addListener(jobListener)
        refreshAll()
    }

    override fun onStop() {
        Jobs.center.removeListener(jobListener)
        main.removeCallbacksAndMessages(null)
        refreshPosted = false
        super.onStop()
    }

    /** Progress events arrive many times a second; redraw at most ~3 times a second. */
    private fun scheduleRefresh() {
        if (refreshPosted) return
        refreshPosted = true
        main.postDelayed({
            refreshPosted = false
            refreshTransfers()
            val active = Jobs.center.activeCount
            if (active != lastActive) { // something finished or started: the file list may have changed
                lastActive = active
                refreshFiles()
            }
        }, 300)
    }

    private fun refreshAll() {
        refreshTransfers()
        refreshFiles()
    }

    // ---- transfers ----

    private fun refreshTransfers() {
        val p = u.p
        transferBox.removeAllViews()
        val jobs = Jobs.center.snapshot().reversed()
        if (jobs.isEmpty()) {
            transferBox.addView(u.label("No transfers yet. Open a file in the cloud browser and choose Download.", 13f, p.muted))
            return
        }
        for (j in jobs) {
            val card = u.card()
            val verb = when (j.kind) { JobKind.DOWNLOAD -> "Download"; JobKind.EXTRACT -> "Extract"; JobKind.EXPORT -> "Copy" }
            card.addView(u.label(j.title, 14f, p.text, true))
            val stateText = when (j.state) {
                JobState.QUEUED -> "$verb waiting in queue…"
                JobState.RUNNING -> listOf(
                    if (j.total > 0) "${GameCompat.human(j.done)} of ${GameCompat.human(j.total)} · ${Fmt.percent(j.done, j.total)}%" else "$verb starting…",
                    Fmt.speed(j.bytesPerSec), Fmt.eta(j.etaSeconds),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                JobState.DONE -> "✔ ${j.message}"
                JobState.FAILED -> "✖ ${j.message}"
                JobState.CANCELLED -> "Cancelled. ${if (j.kind == JobKind.DOWNLOAD) "Tap Retry to resume where it stopped." else ""}"
            }
            card.addView(u.label(stateText, 12f, when (j.state) {
                JobState.DONE -> p.ok; JobState.FAILED -> p.danger; else -> p.muted
            }).apply { setTextIsSelectable(true) })
            if (j.state == JobState.RUNNING || j.state == JobState.QUEUED) {
                card.addView(u.progress().apply {
                    if (j.total > 0 && j.state == JobState.RUNNING) { isIndeterminate = false; progress = (Fmt.percent(j.done, j.total) * 10) }
                    else isIndeterminate = true
                })
                card.addView(u.button("Cancel") { Jobs.center.cancel(j.id) })
            } else {
                if (j.state != JobState.DONE) card.addView(u.button("Retry", BtnKind.PRIMARY) {
                    if (!Jobs.center.retry(j.id)) u.toast("This transfer is already running.")
                    DownloadService.ensureRunning(applicationContext)
                })
                card.addView(u.button("Dismiss") { dismiss(j) })
            }
            transferBox.addView(card, u.margins(bottom = 8))
        }
        if (jobs.any { !it.isActive }) transferBox.addView(u.button("Clear finished") { Jobs.center.clearFinished() })
    }

    private fun dismiss(j: Job) {
        // JobCenter only exposes clearFinished(); dismissing one means clearing all finished entries.
        Jobs.center.clearFinished()
    }

    // ---- files ----

    private fun refreshFiles() {
        runBg {
            val items = store.list()
            val used = store.usedBytes()
            val free = store.root.usableSpace
            val partial = store.partialBytes()
            ui {
                storageInfo.text = "${items.size} item(s) · ${GameCompat.human(used)} used · ${GameCompat.human(free)} free on this phone"
                partialBtn.text = "Clear unfinished downloads (${GameCompat.human(partial)})"
                u.setEnabled(partialBtn, partial > 0)
                renderFiles(items)
            }
        }
    }

    private fun renderFiles(items: List<LibraryStore.Item>) {
        val p = u.p
        fileBox.removeAllViews()
        if (items.isEmpty()) {
            fileBox.addView(u.label("Nothing downloaded yet. Files you download appear here and in the \"CloudPlayX Library\" location of Android's file picker.", 13f, p.muted))
            return
        }
        for (it in items) {
            val v = if (it.isDir) null else GameCompat.assess(it.file.name)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(u.dp(14), u.dp(12), u.dp(14), u.dp(12))
                minimumHeight = u.dp(56)
                background = u.rounded(p.panel, 14)
                isClickable = true
                isFocusable = true
            }
            row.addView(u.label((if (it.isDir) "📁 " else "") + it.file.name, 14f, p.text, true))
            val src = it.meta?.let { m -> " · from ${if (m.backend == "gdrive") "Google Drive" else "WebDAV"}" } ?: ""
            row.addView(u.label("${GameCompat.human(it.size)} · ${DateFormat.getDateInstance().format(Date(it.modified))}$src", 12f, p.muted))
            if (v != null) row.addView(u.label(v.title + " · " + when (v.support) {
                Support.SUPPORTED -> "supported"; Support.PARTIAL -> "partial"; Support.UNSUPPORTED -> "not runnable on Android"
            }, 12f, when (v.support) { Support.SUPPORTED -> p.ok; Support.PARTIAL -> p.warn; Support.UNSUPPORTED -> p.danger }))
            row.setOnClickListener { _ -> showActions(it) }
            fileBox.addView(row, u.margins(bottom = 8))
        }
    }

    private fun showActions(item: LibraryStore.Item) {
        val f = item.file
        val v = if (item.isDir) null else GameCompat.assess(f.name)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, a: () -> Unit) { labels.add(label); actions.add(a) }
        if (v?.canInstall == true) add("Install app (Android installer)") { installApk(f) }
        if (v?.canExtract == true) add("Extract here") { extract(f) }
        if (!item.isDir) add("Open with…") { openWith(f) }
        add("Copy to a folder…") { pickFolderFor(f, move = false) }
        add("Move to a folder (frees phone storage)…") { pickFolderFor(f, move = true) }
        add("Delete") { confirmDelete(f) }
        AlertDialog.Builder(this)
            .setTitle(f.name)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    /** Shows the storage needed before extracting, and offers to delete the downloaded archive afterwards. */
    private fun extract(f: File) {
        val free = store.root.usableSpace
        runBg {
            val need = ZipExtractor.declaredSize(f)
            ui {
                if (need < 0) { u.toast("This file cannot be read as a ZIP archive.", long = true); return@ui }
                val verdict = GameCompat.storage(need, free)
                val msg = "Extracting needs ${GameCompat.human(need)}. ${GameCompat.human(free)} is free on this phone.\n" +
                    "While extracting, the archive (${GameCompat.human(f.length())}) and the extracted files both take space. " +
                    "Deleting the archive afterwards frees ${GameCompat.human(f.length())}; your cloud copy is not affected and you can download it again.\n\n" +
                    (if (verdict.level == StorageLevel.OK) "" else verdict.message)
                val b = AlertDialog.Builder(this).setTitle("Extract ${f.name}?").setMessage(msg)
                    .setNegativeButton("Cancel", null)
                if (verdict.level != StorageLevel.INSUFFICIENT) {
                    b.setPositiveButton("Extract, keep archive") { _, _ -> startExtract(f, false) }
                    b.setNeutralButton("Extract, then delete archive") { _, _ -> startExtract(f, true) }
                }
                b.show()
            }
        }
    }

    private fun startExtract(f: File, deleteAfter: Boolean) {
        Transfers.extract(this, f, deleteAfter)
        u.toast("Extraction started.")
        scheduleRefresh()
    }

    private fun docUri(f: File): Uri? =
        store.relativePath(f)?.let { DocumentsContract.buildDocumentUri(CloudProvider.AUTHORITY, CloudProvider.LIB_ROOT_DOC + it) }

    private fun openWith(f: File) {
        val uri = docUri(f) ?: return
        val mime = java.net.URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"
        try {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open ${f.name}"))
        } catch (e: Exception) {
            u.toast("No installed app can open this file.", long = true)
        }
    }

    /** Hands the APK to Android's package installer. Android shows its own confirmation; nothing is installed silently. */
    private fun installApk(f: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            u.toast("Android needs your permission first: allow installs from CloudPlayX, then tap Install again.", long = true)
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
            return
        }
        val uri = docUri(f) ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            u.toast("Could not start the Android installer: ${e.message}", long = true)
        }
    }

    private fun pickFolderFor(f: File, move: Boolean) {
        pendingExport = f
        pendingMove = move
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), REQ_TREE)
            u.toast("Choose the destination folder. Android 11+ does not allow choosing Android/data or Android/obb.", long = true)
        } catch (e: Exception) {
            u.toast("No folder picker available: ${e.message}", long = true)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val file = pendingExport
        val tree = data?.data
        pendingExport = null
        if (resultCode != RESULT_OK || tree == null || file == null) return
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) { }
        Transfers.export(this, file, tree, deleteSourceAfter = pendingMove)
        u.toast(if (pendingMove) "Move started. The local copy is removed only after everything was copied." else "Copy started.")
        pendingMove = false
        scheduleRefresh()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${f.name}?")
            .setMessage("This removes it from this phone only. The original stays in your cloud, and you can download it again.")
            .setPositiveButton("Delete") { _, _ ->
                runBg {
                    val ok = store.delete(f)
                    Transfers.libraryChanged(applicationContext)
                    ui { u.toast(if (ok) "Deleted." else "Could not delete it."); refreshFiles() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearPartials() {
        AlertDialog.Builder(this)
            .setTitle("Clear unfinished downloads?")
            .setMessage("Deletes only partly downloaded files (they cannot be resumed afterwards). Completed downloads are not touched.")
            .setPositiveButton("Clear") { _, _ ->
                runBg { val freed = store.clearPartials(); ui { u.toast("Freed ${GameCompat.human(freed)}."); refreshFiles() } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInFilesApp() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(
                DocumentsContract.buildRootUri(CloudProvider.AUTHORITY, "library"), DocumentsContract.Root.MIME_TYPE_ITEM))
        } catch (e: Exception) {
            u.toast("Your Files app cannot open this directly. Open it and choose \"CloudPlayX Library\" in its menu.", long = true)
        }
    }

    // ---- launching installed apps through the standard launcher intent ----

    private fun showLauncher() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) { u.toast("No launchable apps were found."); return }
        var shown = apps
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.first }.toMutableList())
        val search = EditText(this).apply { hint = "Search apps"; setSingleLine(true) }
        val list = ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(u.dp(16), u.dp(8), u.dp(16), 0); addView(search); addView(list) }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty().trim().lowercase()
                shown = apps.filter { q.isEmpty() || it.first.lowercase().contains(q) }
                adapter.clear(); adapter.addAll(shown.map { it.first })
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val dialog = AlertDialog.Builder(this).setTitle("Launch an installed app").setView(box).setNegativeButton("Close", null).create()
        list.setOnItemClickListener { _, _, pos, _ ->
            val launch = pm.getLaunchIntentForPackage(shown[pos].second)
            if (launch == null) u.toast("This app cannot be launched.") else {
                try { startActivity(launch); dialog.dismiss() } catch (e: Exception) { u.toast("Could not launch: ${e.message}", long = true) }
            }
        }
        dialog.show()
    }
}
