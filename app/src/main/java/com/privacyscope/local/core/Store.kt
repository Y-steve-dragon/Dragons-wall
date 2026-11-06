package com.privacyscope.local.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本机存储。全部落在 App 私有目录里（Android/data/com.privacyscope.local/databases），
 * 不上传、不同步、不备份（manifest 里 allowBackup=false）。
 */
class Store(context: Context) : SQLiteOpenHelper(context, "privacy.db", null, 1) {

    data class Event(
        val id: Long,
        val ts: Long,
        val pkg: String,
        val kind: String,
        val detail: String
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                pkg TEXT NOT NULL,
                kind TEXT NOT NULL,
                detail TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts DESC)")
        db.execSQL(
            """CREATE TABLE snapshots(
                pkg TEXT NOT NULL,
                op TEXT NOT NULL,
                mode TEXT NOT NULL,
                ts INTEGER NOT NULL,
                PRIMARY KEY(pkg, op)
            )"""
        )
        db.execSQL(
            """CREATE TABLE appcache(
                pkg TEXT PRIMARY KEY,
                label TEXT,
                score INTEGER,
                sdks TEXT,
                perms TEXT,
                ts INTEGER
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 还没有迁移需求
    }

    fun addEvent(ts: Long, pkg: String, kind: String, detail: String) {
        val v = ContentValues()
        v.put("ts", ts)
        v.put("pkg", pkg)
        v.put("kind", kind)
        v.put("detail", detail)
        writableDatabase.insert("events", null, v)
    }

    fun recentEvents(limit: Int = 300): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery(
            "SELECT id, ts, pkg, kind, detail FROM events ORDER BY ts DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Event(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4)))
            }
        }
        return out
    }

    fun clearEvents() {
        writableDatabase.delete("events", null, null)
    }

    fun saveSnapshot(pkg: String, op: String, mode: String) {
        val v = ContentValues()
        v.put("pkg", pkg)
        v.put("op", op)
        v.put("mode", mode)
        v.put("ts", System.currentTimeMillis())
        writableDatabase.insertWithOnConflict("snapshots", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun snapshot(pkg: String): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        readableDatabase.rawQuery("SELECT op, mode FROM snapshots WHERE pkg = ?", arrayOf(pkg)).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = c.getString(1)
        }
        return m
    }

    fun eventCount(): Long {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }
}
