package com.mhmmtbg.x5reframe.project

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Bir .insv klibinin hafif önizleme dosyasını (LRV) bulur.
 * İsim kuralı: VID_20260926_141617_00_142.insv → LRV_20260926_141617_01_142.lrv
 */
object LrvFinder {
    fun lrvName(insvName: String): String =
        insvName.replaceFirst("VID_", "LRV_").replace("_00_", "_01_").replaceAfterLast('.', "lrv")

    /** Kaydedilmiş medya klasöründe (en fazla 3 seviye derinlikte) ada göre ara. */
    fun findInTree(context: Context, tree: Uri?, insvName: String): Uri? {
        if (tree == null || !insvName.endsWith(".insv", true)) return null
        val target = lrvName(insvName)
        val root = runCatching { DocumentFile.fromTreeUri(context, tree) }.getOrNull() ?: return null
        fun search(d: DocumentFile, depth: Int): DocumentFile? {
            val files = runCatching { d.listFiles() }.getOrDefault(emptyArray())
            files.firstOrNull { it.isFile && it.name.equals(target, ignoreCase = true) }?.let { return it }
            if (depth <= 0) return null
            for (sub in files) if (sub.isDirectory) search(sub, depth - 1)?.let { return it }
            return null
        }
        return search(root, 3)?.uri
    }

    /** Çoklu seçimde gelen dosyalar arasından .insv ve ona ait LRV'yi eşleştir. */
    fun pair(uris: List<Uri>, nameOf: (Uri) -> String): Pair<Uri, Uri?>? {
        val named = uris.map { it to nameOf(it) }
        val insv = named.firstOrNull { it.second.endsWith(".insv", true) } ?: named.firstOrNull { !it.second.endsWith(".lrv", true) } ?: return null
        val want = lrvName(insv.second)
        val lrv = named.firstOrNull { it.second.equals(want, true) } ?: named.firstOrNull { it.second.endsWith(".lrv", true) }
        return insv.first to lrv?.first
    }

    const val PREFS = "prefs"
    const val KEY_FOLDER = "folder"

    fun savedFolder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)?.let(Uri::parse)
}
