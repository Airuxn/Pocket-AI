package com.localllm.chat.data.db

import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseMigrationsTest {
    private companion object {
        const val DB_NAME = "migration-test.db"
    }

    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var db: SupportSQLiteDatabase

    /** The version-1 `models` table as shipped before systemPrompt/catalogId existed. */
    private val createModelsV1 = """
        CREATE TABLE models (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,
            filePath TEXT NOT NULL,
            fileSizeBytes INTEGER NOT NULL,
            promptFormat TEXT NOT NULL,
            isActive INTEGER NOT NULL DEFAULT 0,
            addedAt INTEGER NOT NULL DEFAULT 0
        )
    """.trimIndent()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DB_NAME)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(createModelsV1)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                },
            )
            .build()
        helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        db = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        context.deleteDatabase(DB_NAME)
    }

    private fun columns(table: String): Set<String> {
        val found = mutableSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) found.add(cursor.getString(nameIndex))
        }
        return found
    }

    private fun tableExists(table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use { it.moveToFirst() }

    private fun insertModel(values: ContentValues.() -> Unit = {}): Long {
        val row = ContentValues().apply {
            put("name", "Model")
            put("filePath", "/models/model.gguf")
            put("fileSizeBytes", 0L)
            put("promptFormat", "LLAMA_3")
            put("isActive", 0)
            put("addedAt", 0L)
            values()
        }
        return db.insert("models", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, row)
    }

    private fun migrateAll() {
        AppDatabaseMigrations.ALL.forEach { it.migrate(db) }
    }

    @Test
    fun allMigrationsCoverEveryVersionStep() {
        val steps = AppDatabaseMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5), steps)
    }

    @Test
    fun migrationOneToTwoAddsSystemPrompt() {
        assertFalse("systemPrompt" in columns("models"))
        AppDatabaseMigrations.MIGRATION_1_2.migrate(db)
        assertTrue("systemPrompt" in columns("models"))
    }

    @Test
    fun migrationTwoToThreeAddsAndResetsCustomPrompt() {
        AppDatabaseMigrations.MIGRATION_1_2.migrate(db)
        val id = insertModel { put("systemPrompt", "legacy prompt") }

        AppDatabaseMigrations.MIGRATION_2_3.migrate(db)

        assertTrue("hasCustomPrompt" in columns("models"))
        db.query("SELECT systemPrompt, hasCustomPrompt FROM models WHERE id = $id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(0, cursor.getInt(1))
        }
    }

    @Test
    fun migrationThreeToFourCreatesMemoriesTable() {
        assertFalse(tableExists("memories"))
        AppDatabaseMigrations.MIGRATION_3_4.migrate(db)
        assertTrue(tableExists("memories"))
        assertEquals(
            setOf("id", "content", "sourceConversationId", "createdAt", "updatedAt"),
            columns("memories"),
        )
    }

    @Test
    fun migrationFourToFiveAddsCatalogIdAndClearsLegacyLanguagePrompt() {
        AppDatabaseMigrations.MIGRATION_1_2.migrate(db)
        AppDatabaseMigrations.MIGRATION_2_3.migrate(db)
        val legacyId = insertModel()
        val customId = insertModel()
        db.execSQL(
            "UPDATE models SET systemPrompt = ?, hasCustomPrompt = 1 WHERE id = ?",
            arrayOf<Any>("You are a helpful assistant. Always respond in Nederlands.", legacyId),
        )
        db.execSQL(
            "UPDATE models SET systemPrompt = ?, hasCustomPrompt = 1 WHERE id = ?",
            arrayOf<Any>("You only speak in haiku.", customId),
        )

        AppDatabaseMigrations.MIGRATION_4_5.migrate(db)

        assertTrue("catalogId" in columns("models"))
        db.query("SELECT systemPrompt, hasCustomPrompt FROM models WHERE id = $legacyId").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(0, cursor.getInt(1))
        }
        db.query("SELECT systemPrompt, hasCustomPrompt FROM models WHERE id = $customId").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("You only speak in haiku.", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
    }

    @Test
    fun fullUpgradePathProducesVersionFiveSchema() {
        migrateAll()
        val modelColumns = columns("models")
        assertTrue(modelColumns.containsAll(setOf("systemPrompt", "hasCustomPrompt", "catalogId")))
        assertTrue(tableExists("memories"))
    }

    @Test
    fun migrationsAreIdempotentForInstallsThatAlreadyHaveTheFullSchema() {
        migrateAll()
        val userRow = insertModel { put("name", "Kept") }
        // v2.1.0 created a version-1 DB that already had the final columns.
        migrateAll()
        db.query("SELECT name FROM models WHERE id = $userRow").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Kept", cursor.getString(0))
        }
    }
}
