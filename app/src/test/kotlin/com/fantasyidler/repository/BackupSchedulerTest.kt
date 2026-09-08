package com.fantasyidler.repository

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fantasyidler.data.db.AppDatabase
import com.fantasyidler.data.model.PlayerExport
import com.fantasyidler.data.model.PlayerFlags
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [34])
class BackupSchedulerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var sessionRepo: SessionRepository
    private lateinit var playerRepo: PlayerRepository
    private lateinit var scheduler: BackupScheduler
    private lateinit var docs: FakeDocsProvider

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val gameData = GameDataRepository(context, json)
        playerRepo = PlayerRepository(
            db.playerDao(),
            db.questProgressDao(),
            db.farmingPatchDao(),
            json,
            DailyQuestRepository(gameData),
            WeeklyQuestRepository(gameData),
            BuffNotificationScheduler(context),
            gameData,
            BoostRepository(gameData),
            db,
        )
        sessionRepo = SessionRepository(db.skillSessionDao(), context, json, gameData, db.playerDao(), playerRepo)
        scheduler = BackupScheduler(context, sessionRepo, GlobalStateRepository(db.globalStateDao()))

        docs = FakeDocsProvider(TEST_AUTHORITY)
        docs.seed(OLD_DOC_ID, OLD_BACKUP_NAME)
        docs.seed(LEGACY_DOC_ID, LEGACY_BACKUP_NAME)
        val info = ProviderInfo().apply { authority = TEST_AUTHORITY }
        docs.attachInfo(context, info)
        ShadowContentResolver.registerProviderInternal(TEST_AUTHORITY, docs)

        playerRepo.updateFlagsAtomically { it.copy(backupFolderUri = treeUriString()) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `happy path writes verifies then swaps old document`() = runBlocking {
        val ok = scheduler.performBackup(playerRepo)

        assertTrue(ok)
        assertEquals(listOf("create", "write", "readback", "delete", "rename"), docs.events)
        assertEquals(listOf(OLD_DOC_ID), docs.deletedDocIds)
        assertEquals(1, docs.renamedFrom.size)
        assertFalse(docs.children.containsKey(OLD_DOC_ID))
        assertEquals(LEGACY_BACKUP_NAME, docs.children[LEGACY_DOC_ID])
        assertEquals(listOf(LEGACY_BACKUP_NAME, FINAL_BACKUP_NAME), docs.children.values.sorted())
        assertEquals(
            normalized(playerRepo.exportSave()),
            normalized(String(docs.bufferFor(docs.createdUris.single()))),
        )
        val flags = playerRepo.getFlags()
        assertTrue(flags.lastBackupOk)
        assertNotEquals(0L, flags.lastBackupAt)
        assertEquals("", flags.lastBackupError)
    }

    private fun normalized(raw: String): String {
        val export = json.decodeFromString<PlayerExport>(raw)
        val flags = json.decodeFromString<PlayerFlags>(export.flags).copy(
            lastBackupAt = 0L,
            lastBackupOk = true,
            lastBackupError = "",
        )
        return json.encodeToString(PlayerExport.serializer(), export.copy(exportedAt = 0L, sig = "", flags = json.encodeToString(PlayerFlags.serializer(), flags)))
    }

    @Test
    fun `createDocument returning null fails without deleting old backup`() = runBlocking {
        docs.createReturnsNull = true

        val ok = scheduler.performBackup(playerRepo)

        assertFalse(ok)
        assertTrue(docs.createdUris.isEmpty())
        assertTrue(docs.deletedDocIds.isEmpty())
        assertEquals(OLD_BACKUP_NAME, docs.children[OLD_DOC_ID])
        val flags = playerRepo.getFlags()
        assertFalse(flags.lastBackupOk)
        assertTrue(flags.lastBackupError.isNotEmpty())
    }

    @Test
    fun `write failure cleans temp and keeps old backup`() = runBlocking {
        docs.writeThrows = true

        val ok = scheduler.performBackup(playerRepo)

        assertFalse(ok)
        assertTrue(docs.deletedDocIds.contains(DocumentsContract.getDocumentId(docs.createdUris.single())))
        assertEquals(OLD_BACKUP_NAME, docs.children[OLD_DOC_ID])
        assertTrue(docs.renamedFrom.isEmpty())
        val flags = playerRepo.getFlags()
        assertFalse(flags.lastBackupOk)
        assertTrue(flags.lastBackupError.isNotEmpty())
    }

    @Test
    fun `readback mismatch fails and keeps old backup`() = runBlocking {
        docs.readbackOverride = "{\"corrupted\":true}".toByteArray()

        val ok = scheduler.performBackup(playerRepo)

        assertFalse(ok)
        assertTrue(docs.deletedDocIds.contains(DocumentsContract.getDocumentId(docs.createdUris.single())))
        assertEquals(OLD_BACKUP_NAME, docs.children[OLD_DOC_ID])
        assertTrue(docs.renamedFrom.isEmpty())
        val flags = playerRepo.getFlags()
        assertFalse(flags.lastBackupOk)
        assertTrue(flags.lastBackupError.isNotEmpty())
    }

    @Test
    fun `failure after delete pass preserves verified temp file`() = runBlocking {
        docs.renameFails = true

        val ok = scheduler.performBackup(playerRepo)

        assertFalse(ok)
        val tempId = DocumentsContract.getDocumentId(docs.createdUris.single())
        assertFalse(docs.deletedDocIds.contains(tempId))
        assertTrue(docs.deletedDocIds.contains(OLD_DOC_ID))
        assertEquals("fantasyidler_auto_1.tmp", docs.children[tempId])
        val flags = playerRepo.getFlags()
        assertFalse(flags.lastBackupOk)
        assertTrue(flags.lastBackupError.isNotEmpty())
    }

    @Test
    fun `stale temp documents are swept during the swap`() = runBlocking {
        docs.seed("doc_stale_tmp", "fantasyidler_auto_1.tmp")

        val ok = scheduler.performBackup(playerRepo)

        assertTrue(ok)
        assertTrue(docs.deletedDocIds.contains("doc_stale_tmp"))
        assertTrue(docs.deletedDocIds.contains(OLD_DOC_ID))
        val tempId = DocumentsContract.getDocumentId(docs.createdUris.single())
        assertFalse(docs.deletedDocIds.contains(tempId))
        assertEquals("fantasyidler_auto_1", docs.children[tempId])
        assertFalse(docs.children.containsKey("doc_stale_tmp"))
    }

    @Test
    fun `unverifiable post-rename swap fails backup and preserves renamed document`() = runBlocking {
        docs.queryThrowsAfterDelete = true

        val ok = scheduler.performBackup(playerRepo)

        assertFalse(ok)
        val flags = playerRepo.getFlags()
        assertFalse(flags.lastBackupOk)
        assertTrue(flags.lastBackupError.isNotEmpty())
        assertNotEquals(0L, flags.lastBackupAt)
        val tempId = DocumentsContract.getDocumentId(docs.createdUris.single())
        assertFalse(docs.deletedDocIds.contains(tempId))
        assertEquals("fantasyidler_auto_1", docs.children[tempId])
        assertTrue(docs.deletedDocIds.contains(OLD_DOC_ID))
    }

    @Test
    fun `rename returning null fails backup and preserves verified temp`() = runBlocking {
        docs.renameReturnsNull = true

        val ok = scheduler.performBackup(playerRepo)

        assertFalse(ok)
        val flags = playerRepo.getFlags()
        assertFalse(flags.lastBackupOk)
        assertTrue(flags.lastBackupError.isNotEmpty())
        val tempId = DocumentsContract.getDocumentId(docs.createdUris.single())
        assertFalse(docs.deletedDocIds.contains(tempId))
        assertEquals("fantasyidler_auto_1.tmp", docs.children[tempId])
        assertTrue(docs.deletedDocIds.contains(OLD_DOC_ID))
    }

    @Test
    fun `empty backup folder returns false without touching provider`() = runBlocking {
        playerRepo.updateFlagsAtomically { it.copy(backupFolderUri = "") }

        val ok = scheduler.performBackup(playerRepo)

        assertFalse(ok)
        assertTrue(docs.events.isEmpty())
        val flags = playerRepo.getFlags()
        assertTrue(flags.lastBackupOk)
        assertEquals(0L, flags.lastBackupAt)
    }

    private fun treeUriString(): String =
        DocumentsContract.buildTreeDocumentUri(TEST_AUTHORITY, TREE_DOC_ID).toString()

    companion object {
        private const val TEST_AUTHORITY = "com.fantasyidler.test.documents"
        private const val TREE_DOC_ID = "root:primary"
        private const val OLD_DOC_ID = "doc_old"
        private const val OLD_BACKUP_NAME = "fantasyidler_auto_1_Old"
        private const val LEGACY_DOC_ID = "doc_legacy"
        private const val LEGACY_BACKUP_NAME = "fantasyidler_auto"
        private const val FINAL_BACKUP_NAME = "fantasyidler_auto_1"
    }
}

private class FakeDocsProvider(private val authority: String) : ContentProvider() {

    val children = LinkedHashMap<String, String>()
    val createdUris = mutableListOf<Uri>()
    val deletedDocIds = mutableListOf<String>()
    val renamedFrom = mutableListOf<Uri>()
    val events = mutableListOf<String>()

    var createReturnsNull = false
    var writeThrows = false
    var readbackOverride: ByteArray? = null
    var renameFails = false
    var renameReturnsNull = false
    var queryThrowsAfterDelete = false

    private val buffers = HashMap<Uri, ByteArray>()
    private var nextDocNum = 0

    fun seed(docId: String, displayName: String) {
        children[docId] = displayName
    }

    fun bufferFor(uri: Uri): ByteArray =
        buffers[uri] ?: error("no bytes captured for $uri")

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return when (method) {
            METHOD_CREATE -> {
                events += "create"
                if (createReturnsNull) {
                    null
                } else {
                    val docId = "doc_${nextDocNum++}"
                    children[docId] = extras?.getString(KEY_DISPLAY_NAME) ?: ""
                    val uri = DocumentsContract.buildDocumentUri(authority, docId)
                    createdUris += uri
                    val shadow = Shadows.shadowOf(context!!.contentResolver)
                    shadow.registerInputStream(uri, LazyRead(this, uri))
                    shadow.registerOutputStream(uri, CapturingStream(this, uri))
                    Bundle().apply { putParcelable(KEY_URI, uri) }
                }
            }
            METHOD_DELETE -> {
                events += "delete"
                val target = extras!!.getParcelable<Uri>(KEY_URI)!!
                val docId = DocumentsContract.getDocumentId(target)
                children.remove(docId)
                deletedDocIds += docId
                null
            }
            METHOD_RENAME -> {
                if (renameFails) {
                    events += "rename-failed"
                    throw IllegalStateException("simulated provider rename failure")
                }
                if (renameReturnsNull) {
                    events += "rename-failed"
                    return null
                }
                events += "rename"
                val src = extras!!.getParcelable<Uri>(KEY_URI)!!
                renamedFrom += src
                children[DocumentsContract.getDocumentId(src)] = arg ?: extras.getString(KEY_DISPLAY_NAME) ?: ""
                Bundle().apply { putParcelable(KEY_URI, src) }
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (queryThrowsAfterDelete && deletedDocIds.isNotEmpty()) {
            throw RuntimeException("simulated provider query failure after deletion")
        }
        val cols = projection?.toList()
            ?: listOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val cursor = MatrixCursor(cols.toTypedArray())
        synchronized(children) {
            for ((docId, name) in children.toList()) {
                cursor.addRow(cols.map { col ->
                    when (col) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> docId
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> name
                        else -> null
                    }
                })
            }
        }
        return cursor
    }

    private fun store(uri: Uri, bytes: ByteArray) {
        synchronized(buffers) { buffers[uri] = bytes }
    }

    private class CapturingStream(
        private val provider: FakeDocsProvider,
        private val uri: Uri,
    ) : OutputStream() {
        private val buf = ByteArrayOutputStream()
        private var threw = false
        override fun write(b: Int) {
            failIfConfigured()
            buf.write(b)
            provider.events += "write"
            provider.store(uri, buf.toByteArray())
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            failIfConfigured()
            buf.write(b, off, len)
            provider.events += "write"
            provider.store(uri, buf.toByteArray())
        }
        private fun failIfConfigured() {
            if (provider.writeThrows && !threw) {
                threw = true
                provider.events += "write-failed"
                throw IOException("simulated provider write failure")
            }
        }
    }

    private class LazyRead(
        private val provider: FakeDocsProvider,
        private val uri: Uri,
    ) : InputStream() {
        private var inner: ByteArrayInputStream? = null
        private fun stream(): ByteArrayInputStream {
            inner?.let { return it }
            provider.events += "readback"
            val bytes = provider.readbackOverride ?: provider.bufferFor(uri)
            return ByteArrayInputStream(bytes).also { inner = it }
        }
        override fun read(): Int = stream().read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = stream().read(b, off, len)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_CREATE = "android:createDocument"
        const val METHOD_DELETE = "android:deleteDocument"
        const val METHOD_RENAME = "android:renameDocument"
        const val KEY_URI = "uri"
        const val KEY_DISPLAY_NAME = "_display_name"
    }
}
