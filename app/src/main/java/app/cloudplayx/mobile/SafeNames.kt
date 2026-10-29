package app.cloudplayx.mobile

import java.io.File

/** Turns untrusted cloud/archive names into names that are safe to create on local storage. */
object SafeNames {
    private val BAD = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")

    /** One path segment: no separators, no control characters, never hidden, never "." or "..", bounded length. */
    fun sanitize(name: String, maxBytes: Int = 120): String {
        var s = BAD.replace(name, "_").trim().trimStart('.').trimEnd('.', ' ')
        if (s.isEmpty()) s = "file"
        if (s.toByteArray(Charsets.UTF_8).size <= maxBytes) return s
        val dot = s.lastIndexOf('.')
        val ext = if (dot > 0 && s.length - dot <= 10) s.substring(dot) else ""
        var base = s.substring(0, s.length - ext.length)
        while (base.isNotEmpty() && (base + ext).toByteArray(Charsets.UTF_8).size > maxBytes) base = base.dropLast(1)
        return (base.ifEmpty { "file" }) + ext
    }

    /** "name.ext" -> "name (1).ext" style candidates until one does not exist in [dir]. */
    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    /** True if [child] is [root] or lies inside it after resolving "..", symlinks and relative segments. */
    fun isInside(root: File, child: File): Boolean {
        val r = root.canonicalFile.path
        val c = child.canonicalFile.path
        return c == r || c.startsWith(r + File.separator)
    }
}
