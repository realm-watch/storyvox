package `in`.jphe.storyvox.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.epub.config.EpubConfig
import `in`.jphe.storyvox.source.epub.config.EpubFileEntry
import `in`.jphe.storyvox.source.epub.config.fictionIdForEpubUri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.epubDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_epub")

private object EpubKeys {
    /** Persisted SAF tree URI (the user-picked folder). The
     *  ContentResolver.takePersistableUriPermission grant survives
     *  reboots — we don't have to re-prompt the user. Empty / missing
     *  = no folder configured (Browse → Local Books shows empty
     *  state with a CTA back to Settings). */
    val FOLDER_URI = stringPreferencesKey("pref_epub_folder_uri")
}

/**
 * Issue #235 — abstraction over SAF folder enumeration + EPUB byte
 * reads. Production impl lives in this file; test fakes can pass a
 * no-op implementation and avoid pulling Robolectric into the
 * settings-test classpath.
 */
internal interface EpubFileReader {
    fun enumerate(treeUriString: String): List<EpubFileEntry>
    suspend fun readBytes(uriString: String): ByteArray?
}

private class SafEpubFileReader(
    private val context: Context,
) : EpubFileReader {

    override fun enumerate(treeUriString: String): List<EpubFileEntry> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return emptyList()
        if (!tree.isDirectory) return emptyList()
        return tree.listFiles()
            .asSequence()
            .filter { it.isFile && (it.name?.endsWith(".epub", ignoreCase = true) ?: false) }
            .mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val uriStr = f.uri.toString()
                EpubFileEntry(
                    fictionId = fictionIdForEpubUri(uriStr),
                    uriString = uriStr,
                    displayName = name,
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }

    override suspend fun readBytes(uriString: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            }.getOrNull()
        }
}

/**
 * Production [EpubConfig] (issue #235) backed by a tiny dedicated
 * DataStore + Android's Storage Access Framework. Same pattern as
 * [PalaceConfigImpl] / [RssConfigImpl] — the source module stays
 * Android-types-free; this impl owns the SAF plumbing.
 *
 * Folder traversal is delegated to [EpubFileReader] so the settings
 * tests can inject a no-op reader without bringing Robolectric +
 * Context into their classpath.
 */
@Singleton
class EpubConfigImpl internal constructor(
    private val store: DataStore<Preferences>,
    private val reader: EpubFileReader,
) : EpubConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(
        store = context.epubDataStore,
        reader = SafEpubFileReader(context),
    )

    override val folderUriString: Flow<String?> = store.data
        .map { prefs -> prefs[EpubKeys.FOLDER_URI]?.takeIf { it.isNotBlank() } }
        .distinctUntilChanged()

    override suspend fun snapshot(): String? =
        store.data.first()[EpubKeys.FOLDER_URI]?.takeIf { it.isNotBlank() }

    override val books: Flow<List<EpubFileEntry>> = folderUriString.map { uri ->
        if (uri == null) emptyList() else withContext(Dispatchers.IO) { reader.enumerate(uri) }
    }.distinctUntilChanged()

    override suspend fun books(): List<EpubFileEntry> {
        val uri = snapshot() ?: return emptyList()
        return withContext(Dispatchers.IO) { reader.enumerate(uri) }
    }

    override suspend fun readBookBytes(uriString: String): ByteArray? =
        reader.readBytes(uriString)

    /**
     * Mutator hooks for Settings UI. Kept on the impl rather than the
     * EpubConfig interface — same reasoning as [PalaceConfigImpl].
     * The caller is expected to take persistable URI permission via
     * [android.content.ContentResolver.takePersistableUriPermission]
     * before calling [setFolder]; otherwise the URI grant evaporates
     * on next app launch.
     */
    suspend fun setFolder(uriString: String) {
        store.edit { prefs ->
            if (uriString.isBlank()) prefs.remove(EpubKeys.FOLDER_URI)
            else prefs[EpubKeys.FOLDER_URI] = uriString.trim()
        }
    }

    suspend fun clearFolder() {
        store.edit { prefs -> prefs.remove(EpubKeys.FOLDER_URI) }
    }

    companion object {
        /** Test factory — no-op [EpubFileReader] for unit tests that
         *  just need the dependency to satisfy the constructor. */
        internal fun forTesting(store: DataStore<Preferences>): EpubConfigImpl =
            EpubConfigImpl(
                store = store,
                reader = object : EpubFileReader {
                    override fun enumerate(treeUriString: String): List<EpubFileEntry> = emptyList()
                    override suspend fun readBytes(uriString: String): ByteArray? = null
                },
            )
    }
}
