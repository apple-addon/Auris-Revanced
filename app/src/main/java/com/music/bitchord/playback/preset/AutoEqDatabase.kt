package com.music.bitchord.playback.preset

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class AutoEqEntry(
    val id: Long,
    val name: String,
    val source: String,
    val parametric: Boolean,
)

/** A fully decoded row. No claim is made that this class performs native or software filtering. */
data class AutoEqPreset(
    val id: Long,
    val name: String,
    val source: String,
    val parameters: PawsEqParameters,
) {
    val parametric: Boolean get() = parameters.parametric
    val preamp: Float get() = parameters.preamp
    val bands: List<PawsBand> get() = parameters.bands
    val regularBands: List<PawsBand> get() = parameters.regularBands
}

class AutoEqDatabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Read-only Android SQLite access to the packaged `assets/auto_eq.db`.
 *
 * Android cannot query a compressed asset directly, so [open] copies it to a unique private cache
 * file, validates the schema with SQLite itself, and opens with [SQLiteDatabase.OPEN_READONLY].
 * [close] closes the handle and removes that private copy. Instances serialize their own methods;
 * do not share one instance across threads without external lifecycle coordination.
 */
class AutoEqDatabase private constructor(
    private val database: SQLiteDatabase,
    private val copy: File,
    private val copyDirectory: File,
) : Closeable {
    private var closed = false

    @Synchronized
    fun count(parametric: Boolean? = null): Int {
        checkOpen()
        val filter = Filter(parametric, "")
        val sql = "SELECT COUNT(*) FROM eq_presets" + filter.where
        return database.rawQuery(sql, filter.args.toTypedArray()).use { cursor ->
            if (!cursor.moveToFirst()) throw AutoEqDatabaseException("AutoEq count returned no row")
            cursor.getInt(0)
        }
    }

    /**
     * Lists lightweight rows, optionally matching both name and source. LIKE metacharacters in
     * [query] are escaped and all values are bound arguments, never concatenated SQL.
     */
    @Synchronized
    fun list(parametric: Boolean? = null, query: String = "", limit: Int = 500): List<AutoEqEntry> {
        checkOpen()
        if (limit !in 1..MAX_LIMIT) {
            throw IllegalArgumentException("AutoEq list limit must be in 1..$MAX_LIMIT")
        }
        val filter = Filter(parametric, query)
        val sql = "SELECT _id, name, meta, parametric FROM eq_presets" + filter.where +
            " ORDER BY name COLLATE NOCASE, meta COLLATE NOCASE, parametric, _id LIMIT ?"
        return database.rawQuery(sql, (filter.args + limit.toString()).toTypedArray()).use { cursor ->
            val result = ArrayList<AutoEqEntry>(minOf(limit, cursor.count.coerceAtLeast(0)))
            while (cursor.moveToNext()) {
                result += AutoEqEntry(
                    id = cursor.getLong(0),
                    name = cursor.requiredString(1, "name"),
                    source = cursor.requiredString(2, "meta"),
                    parametric = cursor.requiredParametric(3),
                )
            }
            result
        }
    }

    fun search(query: String, parametric: Boolean? = null, limit: Int = 200): List<AutoEqEntry> =
        list(parametric, query, limit)

    @Synchronized
    fun get(id: Long): AutoEqPreset? {
        checkOpen()
        return database.rawQuery(
            "SELECT _id, name, meta, parametric, data_blob FROM eq_presets WHERE _id = ?",
            arrayOf(id.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val sqlParametric = cursor.requiredParametric(3)
            val parameters = try {
                PawsAutoEqBlob.decode(cursor.requiredBlob(4))
            } catch (error: PresetCodecException) {
                throw AutoEqDatabaseException("AutoEq row $id has an invalid PAWS blob", error)
            }
            if (parameters.parametric != sqlParametric) {
                throw AutoEqDatabaseException("AutoEq row $id disagrees with its PAWS blob type")
            }
            AutoEqPreset(
                id = cursor.getLong(0),
                name = cursor.requiredString(1, "name"),
                source = cursor.requiredString(2, "meta"),
                parameters = parameters,
            )
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            database.close()
        } finally {
            copy.delete()
            // Remove only an empty private reader directory; never delete arbitrary cache content.
            copyDirectory.delete()
        }
    }

    private fun checkOpen() {
        if (closed) throw AutoEqDatabaseException("AutoEq database is closed")
    }

    private class Filter(parametric: Boolean?, query: String) {
        val where: String
        val args: List<String>

        init {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            when (parametric) {
                null -> Unit
                else -> {
                    clauses += "parametric = ?"
                    args += if (parametric) "1" else "0"
                }
            }
            if (query.isNotEmpty()) {
                clauses += "(name LIKE ? ESCAPE '\\' OR meta LIKE ? ESCAPE '\\')"
                val pattern = "%" + query.likeEscaped() + "%"
                args += pattern
                args += pattern
            }
            where = if (clauses.isEmpty()) "" else clauses.joinToString(" AND ", prefix = " WHERE ")
            this.args = args
        }

        private fun String.likeEscaped(): String = buildString(length) {
            this@likeEscaped.forEach { ch ->
                if (ch == '\\' || ch == '%' || ch == '_') append('\\')
                append(ch)
            }
        }
    }

    companion object {
        const val ASSET_NAME = "auto_eq.db"
        private const val MAX_LIMIT = 20_000
        private const val MAX_ASSET_BYTES = 32 * 1024 * 1024
        private val REQUIRED_COLUMNS = mapOf(
            "_id" to "INTEGER",
            "name" to "TEXT",
            "meta" to "TEXT",
            "parametric" to "INTEGER",
            "data_blob" to "BLOB",
        )

        @Throws(AutoEqDatabaseException::class)
        fun open(context: Context): AutoEqDatabase {
            val appContext = context.applicationContext
            val directory = File(appContext.cacheDir, "auto_eq_reader")
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw AutoEqDatabaseException("Cannot create AutoEq cache directory")
            }
            val copy = try {
                File.createTempFile("asset-", ".db", directory)
            } catch (error: IOException) {
                throw AutoEqDatabaseException("Cannot create AutoEq database copy", error)
            }
            var database: SQLiteDatabase? = null
            try {
                appContext.assets.open(ASSET_NAME).use { input ->
                    FileOutputStream(copy, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            total += read
                            if (total > MAX_ASSET_BYTES) throw IOException("AutoEq asset is too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                database = SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                validate(database)
                return AutoEqDatabase(database, copy, directory)
            } catch (error: Exception) {
                try {
                    database?.close()
                } catch (closeError: Exception) {
                    error.addSuppressed(closeError)
                }
                copy.delete()
                directory.delete()
                if (error is AutoEqDatabaseException) throw error
                throw AutoEqDatabaseException("Cannot open packaged AutoEq database", error)
            }
        }

        private fun validate(database: SQLiteDatabase) {
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok" || cursor.moveToNext()) {
                    throw AutoEqDatabaseException("AutoEq database failed SQLite quick_check")
                }
            }
            val actual = mutableMapOf<String, String>()
            database.rawQuery("PRAGMA table_info(eq_presets)", null).use { cursor ->
                val name = cursor.columnIndexOrThrow("name")
                val type = cursor.columnIndexOrThrow("type")
                while (cursor.moveToNext()) {
                    actual[cursor.getString(name)] = cursor.getString(type).uppercase()
                }
            }
            REQUIRED_COLUMNS.forEach { (name, type) ->
                if (actual[name] != type) {
                    throw AutoEqDatabaseException("AutoEq schema is missing or changes $name $type")
                }
            }
        }

        private fun Cursor.requiredString(index: Int, field: String): String {
            if (isNull(index)) throw AutoEqDatabaseException("AutoEq row has null $field")
            return getString(index)
        }

        private fun Cursor.requiredBlob(index: Int): ByteArray {
            if (isNull(index)) throw AutoEqDatabaseException("AutoEq row has null data_blob")
            return getBlob(index)
        }

        private fun Cursor.requiredParametric(index: Int): Boolean {
            if (isNull(index)) throw AutoEqDatabaseException("AutoEq row has null parametric value")
            return when (getInt(index)) {
                0 -> false
                1 -> true
                else -> throw AutoEqDatabaseException("AutoEq row has invalid parametric value")
            }
        }

        private fun Cursor.columnIndexOrThrow(name: String): Int {
            val index = getColumnIndex(name)
            if (index < 0) throw AutoEqDatabaseException("AutoEq schema has no $name column metadata")
            return index
        }
    }
}
