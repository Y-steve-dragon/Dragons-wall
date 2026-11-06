package com.privacyscope.local.core

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件变化观测（"最近新增/改动了哪些文件"）。
 *
 * 两种模式，**互斥**，覆盖面不同 —— 这就是"敏感用户"和"我要全看"的两种选择：
 *
 *  ① 受限模式（默认）：不申请"所有文件访问"。只读系统**媒体库**（图片/视频/音频），
 *     要的是 READ_MEDIA_IMAGES / VIDEO / AUDIO（Android 13+）或 READ_EXTERNAL_STORAGE（12 及以下）。
 *     看得见：相册里的照片视频、录音、下载的音乐。
 *     看不见：文档、压缩包、安装包、别人 App 在 Download 里存的东西。
 *
 *  ② 全盘模式：要 MANAGE_EXTERNAL_STORAGE（"所有文件访问"）。
 *     看得见：Download / Documents / DCIM / Pictures / Movies / Music / Android/media 等公共目录里的**全部**文件。
 *     看不见：别的 App 的私有目录（Android/data/<包名>）—— 那是沙箱，root 才行。
 *
 * 两种模式共同点（写死的约束）：
 *  - **只读**：没有 delete / rename / write，一个字节都不改。
 *  - **只在本机**：不联网、不上传。
 *  - **只比对新增**：首次扫描把当前记为已知，从第二次起才报"新增"。
 */
object FileWatch {

    data class Item(val path: String, val bytes: Long, val mtime: Long)

    const val MODE_LIMITED = "limited"
    const val MODE_FULL = "full"

    private const val PREFS = "file_watch"
    private const val KEY_SEEN = "seen_paths"
    private const val KEY_LAST = "last_scan_ms"
    private const val KEY_MODE = "mode"

    private const val MAX_ITEMS = 400
    private const val MAX_DEPTH = 4

    /** 全盘模式扫这些公共目录（相对外部存储根） */
    private val ROOTS = listOf(
        "Download", "Documents", "DCIM", "Pictures", "Movies", "Music",
        "Screenshots", "Android/media", "Tencent", "WeiXin"
    )

    // ---------- 模式与"够不够条件" ----------

    fun mode(ctx: Context): String {
        val m = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, MODE_LIMITED)
        return if (m == MODE_FULL) MODE_FULL else MODE_LIMITED
    }

    fun setMode(ctx: Context, m: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, if (m == MODE_FULL) MODE_FULL else MODE_LIMITED).apply()
    }

    /** 当前模式下，系统是否已经给了我们读文件的能力 */
    fun available(ctx: Context): Boolean = try {
        when (mode(ctx)) {
            MODE_FULL -> fullAccess(ctx)
            else -> mediaGranted(ctx).isNotEmpty()
        }
    } catch (t: Throwable) {
        false
    }

    /** 全盘模式的条件："所有文件访问"开关（Android 11+）；更早的系统退回读存储权限 */
    fun fullAccess(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 受限模式要申请的运行时权限 */
    fun neededPermissions(ctx: Context): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_AUDIO"
        ) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    /** 受限模式里已经拿到权限的媒体类型（可能只有图片） */
    private fun mediaGranted(ctx: Context): List<String> {
        if (Build.VERSION.SDK_INT >= 33) {
            val out = ArrayList<String>()
            if (granted(ctx, "android.permission.READ_MEDIA_IMAGES")) out.add("images")
            if (granted(ctx, "android.permission.READ_MEDIA_VIDEO")) out.add("video")
            if (granted(ctx, "android.permission.READ_MEDIA_AUDIO")) out.add("audio")
            return out
        }
        return if (granted(ctx, android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
            listOf("images", "video", "audio")
        } else emptyList()
    }

    private fun granted(ctx: Context, p: String): Boolean = try {
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 跳到"所有文件访问"的系统页面（Android 11+ 专属，开关只能用户自己拨） */
    fun openSettings(ctx: Context) {
        val i = try {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + ctx.packageName)
            )
        } catch (t: Throwable) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            try {
                val j = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + ctx.packageName)
                )
                j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(j)
            } catch (_: Throwable) {
            }
        }
    }

    // ---------- 扫描 ----------

    /** 扫最近 [hours] 小时内改动过的文件（按 mtime 倒序）。没条件就返回空列表，不假装扫到。 */
    fun scan(ctx: Context, hours: Int = 24): List<Item> = try {
        when (mode(ctx)) {
            MODE_FULL -> if (fullAccess(ctx)) scanFull(ctx, hours) else emptyList()
            else -> scanMedia(ctx, hours)
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /** 全盘模式：走目录树（只读目录项 + size/mtime） */
    private fun scanFull(ctx: Context, hours: Int): List<Item> {
        val since = System.currentTimeMillis() - hours * 3600_000L
        val out = ArrayList<Item>()
        val base = Environment.getExternalStorageDirectory() ?: return emptyList()
        for (r in ROOTS) {
            walk(File(base, r), 0, since, out)
            if (out.size >= MAX_ITEMS) break
        }
        return out.sortedByDescending { it.mtime }.take(MAX_ITEMS)
    }

    /** 受限模式：只查系统媒体库（相册/视频/音频），不碰文档与下载目录 */
    private fun scanMedia(ctx: Context, hours: Int): List<Item> {
        val ok = mediaGranted(ctx)
        if (ok.isEmpty()) return emptyList()
        val sinceSec = (System.currentTimeMillis() - hours * 3600_000L) / 1000L
        val out = ArrayList<Item>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val collections = ArrayList<Triple<Uri, String, Boolean>>()
        collections.add(Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "图片", ok.contains("images")))
        collections.add(Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "视频", ok.contains("video")))
        collections.add(Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "音频", ok.contains("audio")))

        for ((c, label, allowed) in collections) {
            if (!allowed) continue
            try {
                ctx.contentResolver.query(
                    c, proj,
                    MediaStore.MediaColumns.DATE_ADDED + ">=?",
                    arrayOf(sinceSec.toString()),
                    MediaStore.MediaColumns.DATE_ADDED + " DESC"
                )?.use { cur ->
                    val iName = cur.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iSize = cur.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val iTime = cur.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val iId = cur.getColumnIndex(MediaStore.MediaColumns._ID)
                    while (cur.moveToNext() && out.size < MAX_ITEMS) {
                        val name = if (iName >= 0) cur.getString(iName) ?: "?" else "?"
                        val size = if (iSize >= 0) cur.getLong(iSize) else 0L
                        val sec = if (iTime >= 0) cur.getLong(iTime) else 0L
                        val id = if (iId >= 0) cur.getLong(iId) else 0L
                        // 媒体库给不出真实路径，用「媒体/类型/编号/文件名」当稳定标识
                        val key = "media://$label/$id/$name"
                        out.add(Item(key, size, sec * 1000L))
                    }
                }
            } catch (t: Throwable) {
                // 某类媒体没权限或查询失败：跳过这一类，不影响其它
            }
        }
        return out.sortedByDescending { it.mtime }.take(MAX_ITEMS)
    }

    private fun walk(dir: File, depth: Int, since: Long, out: MutableList<Item>) {
        if (depth > MAX_DEPTH || out.size >= MAX_ITEMS) return
        val kids = try {
            dir.listFiles()
        } catch (t: Throwable) {
            null
        } ?: return
        for (f in kids) {
            if (out.size >= MAX_ITEMS) return
            val name = f.name
            if (name.startsWith(".")) continue
            val isDir = try {
                f.isDirectory
            } catch (t: Throwable) {
                false
            }
            if (isDir) {
                walk(f, depth + 1, since, out)
                continue
            }
            val mt = try {
                f.lastModified()
            } catch (t: Throwable) {
                0L
            }
            if (mt < since) continue
            val len = try {
                f.length()
            } catch (t: Throwable) {
                0L
            }
            out.add(Item(f.absolutePath, len, mt))
        }
    }

    // ---------- 记录 ----------

    fun loadSeen(ctx: Context): MutableSet<String> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        return HashSet(raw)
    }

    fun saveSeen(ctx: Context, seen: Set<String>) {
        val trimmed = if (seen.size <= 5000) seen else LinkedHashSet(seen.toList().takeLast(5000))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_SEEN, trimmed).apply()
    }

    fun saveLastScan(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST, System.currentTimeMillis()).apply()
    }

    fun lastScanText(ctx: Context): String {
        val ms = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)
        if (ms <= 0L) return "还没扫过"
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }

    fun sizeText(b: Long): String = when {
        b >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    fun modeName(ctx: Context): String =
        if (mode(ctx) == MODE_FULL) "全盘模式" else "受限模式"
}
