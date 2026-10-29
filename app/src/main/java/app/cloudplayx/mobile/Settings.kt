package app.cloudplayx.mobile

import android.content.Context
import java.io.File

/** Small settings store. The WebDAV password is encrypted with an Android Keystore key ([SecretStore]). */
class Settings(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("cloudplayx", Context.MODE_PRIVATE)

    /** "", "gdrive" or "webdav". */
    var backend: String
        get() = p.getString("backend", "") ?: ""
        set(v) { p.edit().putString("backend", v).apply() }

    var webDavUrl: String
        get() = p.getString("webDavUrl", "") ?: ""
        set(v) { p.edit().putString("webDavUrl", v).apply() }

    var webDavUser: String
        get() = p.getString("webDavUser", "") ?: ""
        set(v) { p.edit().putString("webDavUser", v).apply() }

    var webDavPass: String
        get() {
            val stored = p.getString("webDavPassEnc", "") ?: ""
            if (stored.isNotEmpty()) return SecretStore.decrypt(stored)
            // Migrate a plain-text password saved by an older version, then remove it.
            val legacy = p.getString("webDavPass", "") ?: ""
            if (legacy.isNotEmpty()) { webDavPass = legacy }
            return legacy
        }
        set(v) {
            val enc = try { SecretStore.encrypt(v) } catch (e: Exception) { null }
            p.edit().apply {
                remove("webDavPass") // never keep the plain text
                if (enc != null) putString("webDavPassEnc", enc)
                else remove("webDavPassEnc") // Keystore unusable: do not fall back to plain text
            }.apply()
        }

    /** Cache budget in MiB (default 10 GiB). */
    var cacheBudgetMb: Long
        get() = p.getLong("cacheBudgetMb", 10L * 1024)
        set(v) { p.edit().putLong("cacheBudgetMb", v).apply() }

    /** Cached blocks not read for this many days are deleted. */
    var maxAgeDays: Int
        get() = p.getInt("maxAgeDays", 30)
        set(v) { p.edit().putInt("maxAgeDays", v).apply() }

    /** Changes whenever the cloud account changes, so the provider drops its in-memory state. */
    var configVersion: Int
        get() = p.getInt("configVersion", 0)
        set(v) { p.edit().putInt("configVersion", v).apply() }

    val budgetBytes: Long get() = cacheBudgetMb * 1024L * 1024L

    /** Forget the selected cloud and its credentials on this phone. Downloaded library files are kept. */
    fun disconnect() {
        GoogleAuth.forget()
        p.edit().putString("backend", "").remove("webDavPass").remove("webDavPassEnc").apply()
        configVersion += 1
    }
}

object RemoteFactory {
    fun create(ctx: Context, settings: Settings): Remote? = when (settings.backend) {
        "gdrive" -> GoogleDriveRemote({ GoogleAuth.token(ctx) }, { GoogleAuth.forget() })
        "webdav" -> WebDavRemote(settings.webDavUrl, settings.webDavUser, settings.webDavPass)
        else -> null
    }
}

/** One cache per process, shared by the file provider and the settings screen. */
object CacheHolder {
    @Volatile
    private var cache: BlockCache? = null

    fun get(ctx: Context): BlockCache {
        val s = Settings(ctx)
        val c = cache ?: synchronized(this) {
            cache ?: BlockCache(File(ctx.applicationContext.cacheDir, "blocks"), s.budgetBytes).also {
                it.trimByAge(s.maxAgeDays * 86_400_000L)
                cache = it
            }
        }
        c.budgetBytes = s.budgetBytes
        return c
    }
}

/** The on-device library of downloaded files (app-specific external storage, so it never needs a storage permission). */
object LibraryHolder {
    fun root(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir, "Library")

    @Volatile private var store: LibraryStore? = null

    fun get(ctx: Context): LibraryStore {
        val r = root(ctx)
        val s = store
        if (s != null && s.root == r) return s
        return synchronized(this) { LibraryStore(r).also { store = it } }
    }
}
