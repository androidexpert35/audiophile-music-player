package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.net.toUri
import com.androidexpert35.audiophilemusicplayer.data.scanner.MusicFolderScope
import com.androidexpert35.audiophilemusicplayer.data.scanner.MusicFolderScopeResolver
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the folders the library scan is allowed to read.
 *
 * Owns both halves of a grant that must never drift apart: the persisted document-tree
 * URI in [SharedPreferences] and the matching long-lived read permission held by the
 * system. A URI whose permission was revoked (card unmounted, user cleared access) is
 * treated as absent rather than reported as a folder the scan can use.
 *
 * Lives in the Data layer and speaks [MusicFolderScope] so both scan passes can consume
 * it directly; [MusicFolderRepositoryImpl] adapts the same state to the Domain contract.
 *
 * @property prefs Dedicated settings storage shared with the rest of the app.
 * @property contentResolver Resolver used to take, release, and verify read grants.
 * @property scopeResolver Translates tree URIs into MediaStore-addressable scopes.
 * @property ioDispatcher Dispatcher for the blocking preference commits.
 */
@Singleton
class MusicFolderRegistry @Inject constructor(
    private val prefs: SharedPreferences,
    private val contentResolver: ContentResolver,
    private val scopeResolver: MusicFolderScopeResolver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Live stream of the granted folders, emitting the current collection immediately
     * and again on every addition or removal.
     *
     * Wraps [SharedPreferences.OnSharedPreferenceChangeListener] in a `callbackFlow` so
     * the listener is detached automatically when the collector's scope cancels.
     */
    fun observeScopes(): Flow<List<MusicFolderScope>> = callbackFlow {
        // Emit the stored value synchronously so the first collector has a real
        // reading before any change event arrives.
        trySend(readScopes())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == SettingsPreferences.KEY_MUSIC_FOLDER_URIS) {
                trySend(readScopes())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(ioDispatcher)

    /**
     * Reads the folders the scan may currently walk.
     *
     * @return Granted scopes ordered by storage volume then path, or an empty list when
     *   the user has not authorised any folder yet.
     */
    suspend fun getScopes(): List<MusicFolderScope> = withContext(ioDispatcher) { readScopes() }

    /**
     * Reports whether the user has any folder on record, regardless of whether its grant
     * currently resolves.
     *
     * This is what separates *"the user removed their last folder"* from *"the card holding
     * the folder is unmounted right now"*. Both leave [getScopes] empty, but only the first
     * one means the library should legitimately become empty.
     *
     * @return `true` when at least one folder URI is stored.
     */
    suspend fun hasStoredFolders(): Boolean = withContext(ioDispatcher) {
        storedUriStrings().isNotEmpty()
    }

    /**
     * Builds a stable fingerprint of the current scan scope.
     *
     * Persisted alongside the index so a catalogue built from a different set of folders can
     * be recognised as stale — otherwise a folder removed while the library screen was not
     * running would keep its tracks visible until something else forced a rescan.
     *
     * @return Deterministic signature, empty when no folder is granted.
     */
    suspend fun folderSignature(): String = withContext(ioDispatcher) {
        readScopes().joinToString(separator = "|") { scope ->
            "${scope.volumeName}:${scope.relativePath}"
        }
    }

    /**
     * Takes a durable read grant on [treeUri] and adds it to the scan scope.
     *
     * Overlapping grants are collapsed: a folder already covered by a stored parent is
     * dropped, and stored sub-folders of the new grant are replaced by it, so no tree is
     * ever walked twice.
     *
     * @param treeUri Document-tree URI returned by the system folder chooser.
     * @return Success when saved, or a stable failure distinguishing location, grant,
     *   and settings-write problems for user support.
     */
    suspend fun add(treeUri: Uri): Resource<Unit> = withContext(ioDispatcher) {
        val takeSucceeded = runCatching {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure {
            LibraryDiagnostics.record(LibraryResourceError.FOLDER_PERMISSION_DENIED, it)
        }.isSuccess
        if (!takeSucceeded) return@withContext Resource.Error(LibraryResourceError.FOLDER_PERMISSION_DENIED)

        // Providers outside external storage (cloud shortcuts, some Downloads roots) do not
        // expose a volume-addressable document ID, so MediaStore could never be filtered to
        // them. Give the grant straight back rather than storing a folder that cannot scan.
        val addedScope = scopeResolver.resolve(treeUri) ?: run {
            releaseGrant(treeUri)
            return@withContext Resource.Error(LibraryResourceError.UNSUPPORTED_FOLDER)
        }
        val storedUris = storedUriStrings()
        val storedScopes = storedUris.mapNotNull { uri -> resolveStoredScope(uri) }

        // Already covered by an existing grant — nothing to store. The permission taken
        // above is released again unless this exact URI is the covering entry itself.
        if (storedScopes.any { stored -> stored.covers(addedScope) }) {
            if (treeUri.toString() !in storedUris) releaseGrant(treeUri)
            // A failed commit still updates SharedPreferences in memory. Recommit on
            // retry rather than mistaking an in-memory selection for durable success.
            return@withContext if (writeUriStrings(storedUris)) Resource.Success(Unit)
                else Resource.Error(LibraryResourceError.FOLDER_SAVE_FAILED)
        }

        // Replace any stored sub-folder of the new grant, releasing its now-redundant
        // permission so the app does not retain access it no longer uses.
        val supersededScopes = storedScopes.filter { stored -> addedScope.covers(stored) }

        val supersededUris = supersededScopes.map { it.treeUri.toString() }.toSet()
        val updatedUris = storedUris - supersededUris + treeUri.toString()
        if (writeUriStrings(updatedUris)) {
            supersededScopes.forEach { superseded -> releaseGrant(superseded.treeUri) }
            Resource.Success(Unit)
        } else {
            Resource.Error(LibraryResourceError.FOLDER_SAVE_FAILED)
        }
    }.also { result ->
        (result as? Resource.Error)?.data?.let { error ->
            if (error is LibraryResourceError) LibraryDiagnostics.record(error)
        }
    }

    /**
     * Removes a folder from the scan scope and releases its read grant.
     *
     * @param folderId Stored document-tree URI string.
     * @return `true` when the folder is no longer part of the scan scope.
     */
    suspend fun remove(folderId: String): Boolean = withContext(ioDispatcher) {
        val storedUris = storedUriStrings()
        if (folderId !in storedUris) return@withContext true

        releaseGrant(folderId.toUri())
        writeUriStrings(storedUris - folderId)
    }

    /**
     * Resolves the stored URIs whose read grant is still held by the system.
     *
     * Entries that lost their permission are filtered out instead of being surfaced as
     * usable folders — a scan would fail on them, and reporting an empty scope is what
     * lets onboarding ask the user to grant the folder again.
     */
    private fun readScopes(): List<MusicFolderScope> = storedUriStrings()
        .mapNotNull { uri -> resolveStoredScope(uri) }
        .sortedWith(compareBy({ it.storageLabel }, { it.displayPath }))

    /**
     * Resolves a stored URI string, returning `null` when its read grant is gone.
     */
    private fun resolveStoredScope(uriString: String): MusicFolderScope? {
        val uri = runCatching { uriString.toUri() }.getOrNull() ?: return null
        val hasGrant = contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == uri
        }
        return if (hasGrant) scopeResolver.resolve(uri) else null
    }

    private fun storedUriStrings(): Set<String> =
        if (ensureCurrentFolderSelectionVersion()) {
            prefs.getStringSet(SettingsPreferences.KEY_MUSIC_FOLDER_URIS, emptySet()).orEmpty()
        } else {
            emptySet()
        }

    /**
     * Retires folder grants saved before the mandatory folder-scoped onboarding contract.
     *
     * This one-time upgrade gate runs before every registry read, including onboarding's
     * initial [hasStoredFolders] check. Consequently an upgraded installation cannot reuse
     * an old selection and skip the folder picker. Fresh installations take the same path
     * harmlessly with an empty legacy set.
     *
     * @return `true` when the current selection version is persisted successfully.
     */
    private fun ensureCurrentFolderSelectionVersion(): Boolean {
        val currentVersion = prefs.getInt(
            SettingsPreferences.KEY_MUSIC_FOLDER_SELECTION_VERSION,
            LEGACY_FOLDER_SELECTION_VERSION,
        )
        if (currentVersion >= SettingsPreferences.CURRENT_MUSIC_FOLDER_SELECTION_VERSION) {
            return true
        }

        val legacyUris = prefs
            .getStringSet(SettingsPreferences.KEY_MUSIC_FOLDER_URIS, emptySet())
            .orEmpty()
            .toSet()
        legacyUris.forEach { uriString ->
            runCatching { uriString.toUri() }.getOrNull()?.let(::releaseGrant)
        }

        return prefs.edit()
            .remove(SettingsPreferences.KEY_MUSIC_FOLDER_URIS)
            .putInt(
                SettingsPreferences.KEY_MUSIC_FOLDER_SELECTION_VERSION,
                SettingsPreferences.CURRENT_MUSIC_FOLDER_SELECTION_VERSION,
            )
            .commit()
    }

    private fun writeUriStrings(uris: Set<String>): Boolean = prefs.edit()
        // Defensive copy: the set handed to SharedPreferences must not be one we mutate.
        .putStringSet(SettingsPreferences.KEY_MUSIC_FOLDER_URIS, LinkedHashSet(uris))
        .commit()

    private fun releaseGrant(treeUri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /**
     * Reports whether this scope's tree contains [other], i.e. scanning this folder
     * would already index everything inside [other].
     */
    private fun MusicFolderScope.covers(other: MusicFolderScope): Boolean =
        volumeName == other.volumeName && other.relativePath.startsWith(relativePath)

    private companion object {
        const val LEGACY_FOLDER_SELECTION_VERSION: Int = 0
    }
}
