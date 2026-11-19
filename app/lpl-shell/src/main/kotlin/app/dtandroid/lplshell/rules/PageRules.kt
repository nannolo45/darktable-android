// SPDX-License-Identifier: GPL-3.0-or-later
package app.dtandroid.lplshell.rules

/** File choosing and downloads started by the page. */
object PageRules {

    /**
     * MIME types for the system file picker from an `<input accept=...>` list.
     * Empty result: any file. File extensions (`.raf`) are looked up with
     * [mimeOfExtension]; if one cannot be resolved the picker offers any file,
     * so a file the page accepts is never hidden.
     */
    fun chooserMimeTypes(accept: List<String>, mimeOfExtension: (String) -> String?): List<String> {
        val types = LinkedHashSet<String>()
        for (raw in accept.flatMap { it.split(',') }) {
            val a = raw.trim().lowercase()
            if (a.isEmpty()) continue
            val mime = when {
                a.startsWith(".") -> mimeOfExtension(a.substring(1)) ?: return emptyList()
                a.matches(Regex("[a-z0-9.+-]+/([a-z0-9.+-]+|\\*)")) -> a
                else -> return emptyList()
            }
            if (mime == "*/*") return emptyList()
            types += mime
        }
        return types.toList()
    }

    sealed interface DownloadDecision {
        /** same origin: the system download manager fetches it with the page's cookies */
        data object Manager : DownloadDecision
        /** another http(s) origin: the browser handles it */
        data object Outside : DownloadDecision
        /** blob:, data: and the rest: the download manager cannot fetch these */
        data object Unsupported : DownloadDecision
    }

    fun download(saved: Origin, url: String): DownloadDecision {
        val o = Origin.of(url)
        return when {
            o == saved -> DownloadDecision.Manager
            o != null -> DownloadDecision.Outside
            else -> DownloadDecision.Unsupported
        }
    }

    /** A file name safe to create in Downloads: no folders, no control characters, not hidden. */
    fun safeFileName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filterNot { it.isISOControl() || it in "<>:\"|?*" }
            .trim().trimStart('.')
        return base.ifEmpty { "download" }.take(120)
    }
}
