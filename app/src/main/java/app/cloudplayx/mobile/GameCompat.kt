package app.cloudplayx.mobile

/**
 * What the app can honestly do with a file when it is meant for gaming.
 *
 * SUPPORTED   = CloudPlayX performs every step itself with standard Android APIs (download, verify, extract, hand to the installer).
 * PARTIAL     = some steps work here; other steps depend on another app, the game, or Android restrictions.
 * UNSUPPORTED = Android, the file format or the platform makes it impossible to run from here.
 * "Supported" describes the workflow design, not a guarantee that a particular game works.
 */
enum class Support { SUPPORTED, PARTIAL, UNSUPPORTED }

enum class Kind { ANDROID_APK, ANDROID_BUNDLE, OBB, ZIP, OTHER_ARCHIVE, WINDOWS_PROGRAM, DISC_IMAGE, EMULATOR_ROM, GAME_ASSET, MEDIA, DOCUMENT, UNKNOWN }

data class Verdict(
    val kind: Kind,
    val support: Support,
    val title: String,
    val detail: String,
    val canExtract: Boolean = false,
    val canInstall: Boolean = false,
)

enum class StorageLevel { OK, LOW, INSUFFICIENT }

data class StorageVerdict(val level: StorageLevel, val message: String)

object GameCompat {
    private val ROM_EXT = setOf(
        "nes", "sfc", "smc", "gb", "gbc", "gba", "nds", "n64", "z64", "v64", "3ds", "cia", "nsp", "xci",
        "gen", "md", "sms", "gg", "pce", "a26", "chd", "gcz", "rvz", "wbfs", "psp",
    )
    private val DISC_EXT = setOf("iso", "bin", "cue", "img", "mdf", "nrg")
    private val ASSET_EXT = setOf("pak", "unity3d", "assetbundle", "bundle", "wad", "pck", "vpk", "dat", "pkg", "bsa", "ba2", "upk")
    private val OTHER_ARCHIVE_EXT = setOf("7z", "rar", "tar", "gz", "tgz", "bz2", "xz", "zst", "lz4")
    private val WINDOWS_EXT = setOf("exe", "msi", "dll", "bat", "com", "scr", "appx", "msix")
    private val MEDIA_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "mp3", "ogg", "flac", "wav", "m4a", "png", "jpg", "jpeg", "gif", "webp")
    private val DOC_EXT = setOf("txt", "md", "pdf", "doc", "docx", "json", "xml", "ini", "cfg", "log")

    fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun assess(name: String, mime: String? = null): Verdict {
        val ext = extensionOf(name)
        val lower = name.lowercase()
        return when {
            ext == "apk" -> Verdict(
                Kind.ANDROID_APK, Support.SUPPORTED, "Android app (APK)",
                "Download, then install through Android's package installer. Android asks you to allow installs from CloudPlayX and shows its own confirmation. Games that check their signature or need extra data may still refuse to run.",
                canInstall = true,
            )
            ext in setOf("xapk", "apks", "apkm") -> Verdict(
                Kind.ANDROID_BUNDLE, Support.PARTIAL, "Android app bundle (${ext.uppercase()})",
                "This is a zip of split APKs plus data. CloudPlayX can download and extract it, but installing split APKs needs a dedicated installer app (for example SAI) or ADB. Extract it and pick the files you need.",
                canExtract = true,
            )
            ext == "obb" -> Verdict(
                Kind.OBB, Support.PARTIAL, "Game data file (OBB)",
                "CloudPlayX can download and verify it and export it to a folder you choose. Android 11+ blocks every app, including this one, from writing into Android/obb, so move it there with the system Files app or ADB, and only if the game supports that.",
            )
            ext == "zip" -> Verdict(
                Kind.ZIP, Support.SUPPORTED, "ZIP archive",
                "Download, then extract safely into the CloudPlayX library. Password-protected ZIPs are not supported. What you can do with the extracted files depends on the game or app that will use them.",
                canExtract = true,
            )
            ext in OTHER_ARCHIVE_EXT -> Verdict(
                Kind.OTHER_ARCHIVE, Support.PARTIAL, "Archive (${ext.uppercase()})",
                "CloudPlayX can download and verify it, but only extracts ZIP files. Extract it with another app, or re-upload it as ZIP.",
            )
            ext in WINDOWS_EXT -> Verdict(
                Kind.WINDOWS_PROGRAM, Support.UNSUPPORTED, "Windows program (.${ext})",
                "Android cannot run Windows programs. A PC game does not become runnable on Android by moving its files here. CloudPlayX can still download the file so you can copy it elsewhere.",
            )
            ext in DISC_EXT -> Verdict(
                Kind.DISC_IMAGE, Support.PARTIAL, "Disc image (.${ext})",
                "CloudPlayX can download and verify it. Running it needs a matching emulator app that can read the file from a folder or the Android file picker. Only use images you have the right to use.",
            )
            ext in ROM_EXT -> Verdict(
                Kind.EMULATOR_ROM, Support.PARTIAL, "Emulator game file (.${ext})",
                "CloudPlayX can download and verify it and export it to a folder. An emulator app of your choice is needed to play it. Only use files you have the right to use.",
            )
            ext in ASSET_EXT -> Verdict(
                Kind.GAME_ASSET, Support.PARTIAL, "Game asset file (.${ext})",
                "Such files only work inside the game that created them. CloudPlayX cannot place files in another app's private storage; use the game's own import option if it has one.",
            )
            ext in MEDIA_EXT || mime?.startsWith("image/") == true || mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true -> Verdict(
                Kind.MEDIA, Support.SUPPORTED, "Media file",
                "Download it or open it directly (streamed) with any app that can play it.",
            )
            ext in DOC_EXT || lower.endsWith(".readme") -> Verdict(
                Kind.DOCUMENT, Support.SUPPORTED, "Document",
                "Download it or open it directly with any compatible app.",
            )
            else -> Verdict(
                Kind.UNKNOWN, Support.PARTIAL, "Unknown file type",
                "CloudPlayX can download and verify any file. Whether anything on this phone can use it depends on the file.",
            )
        }
    }

    /** Checks that [requiredBytes] fits in [availableBytes] with a safety margin so the phone does not run out of space. */
    fun storage(requiredBytes: Long, availableBytes: Long, margin: Long = defaultMargin(requiredBytes)): StorageVerdict {
        val need = requiredBytes + margin
        return when {
            availableBytes < requiredBytes ->
                StorageVerdict(StorageLevel.INSUFFICIENT, "Not enough free space: needs ${human(requiredBytes)}, only ${human(availableBytes)} free.")
            availableBytes < need ->
                StorageVerdict(StorageLevel.LOW, "Low storage: needs ${human(requiredBytes)} and only ${human(availableBytes - requiredBytes)} would remain.")
            else -> StorageVerdict(StorageLevel.OK, "Needs ${human(requiredBytes)}; ${human(availableBytes)} free.")
        }
    }

    fun defaultMargin(requiredBytes: Long): Long = maxOf(200L * 1024 * 1024, requiredBytes / 20)

    fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = -1
        do { v /= 1024.0; i++ } while (v >= 1024.0 && i < units.lastIndex)
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }
}
