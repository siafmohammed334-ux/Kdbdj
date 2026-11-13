package com.saif.downloading

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {

    private lateinit var web: WebView
    private val infoPool = Executors.newCachedThreadPool()
    private val dlPool = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val readyLatch = CountDownLatch(1)

    @Volatile private var initError: String? = null
    @Volatile private var pageLoaded = false
    @Volatile private var pendingShared: String? = null

    private val platforms = mapOf(
        "youtube" to listOf("youtube.com", "youtu.be"),
        "tiktok" to listOf("tiktok.com"),
        "instagram" to listOf("instagram.com", "instagr.am"),
        "facebook" to listOf("facebook.com", "fb.watch", "fb.com"),
        "twitter" to listOf("twitter.com", "x.com", "t.co")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B1020")
        window.navigationBarColor = Color.parseColor("#0B1020")

        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 11)
        }

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0B1020"))
        setContentView(web)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                deliverShared()
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")

        startEngine()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ---------- مشاركة رابط من تطبيق تاني ----------

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action == Intent.ACTION_SEND && (i.type ?: "").startsWith("text/")) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT)
            if (!t.isNullOrBlank()) {
                pendingShared = t
                deliverShared()
            }
        }
    }

    private fun deliverShared() {
        val t = pendingShared ?: return
        if (!pageLoaded) return
        pendingShared = null
        js("window.onSharedLink(${q(t)})")
    }

    // ---------- تشغيل محرك التحميل + تحديثه تلقائيًا ----------

    private fun startEngine() {
        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
            } catch (t: Throwable) {
                initError = t.message ?: t.toString()
            }
            if (initError == null) {
                // تحديث yt-dlp عند كل فتح للتطبيق (ده اللي بيمنع "فشل التحميل بسبب نسخة قديمة")
                val u = Thread { updateEngine() }
                u.start()
                try { u.join(25000) } catch (_: Throwable) {}
            }
            readyLatch.countDown()
        }.start()
    }

    private fun updateEngine() {
        try {
            val yd = YoutubeDL.getInstance()
            val m = yd.javaClass.methods.firstOrNull { it.name == "updateYoutubeDL" } ?: return
            val types = m.parameterTypes
            val args = arrayOfNulls<Any>(types.size)
            for (idx in types.indices) {
                val t = types[idx]
                args[idx] = if (Context::class.java.isAssignableFrom(t)) applicationContext else pickChannel(t)
            }
            m.invoke(yd, *args)
        } catch (_: Throwable) {
        }
    }

    private fun pickChannel(t: Class<*>): Any? {
        try { return t.getField("STABLE").get(null) } catch (_: Throwable) {}
        try {
            val e = t.enumConstants
            if (e != null && e.isNotEmpty()) return e[0]
        } catch (_: Throwable) {}
        try {
            for (c in t.declaredClasses) {
                if (c.simpleName.replace("_", "").equals("STABLE", ignoreCase = true)) {
                    return c.getField("INSTANCE").get(null)
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun awaitReady() {
        readyLatch.await(60, TimeUnit.SECONDS)
    }

    // ---------- أدوات ----------

    private fun js(code: String) {
        runOnUiThread {
            try { web.evaluateJavascript(code, null) } catch (_: Throwable) {}
        }
    }

    private fun q(s: String): String = JSONObject.quote(s)

    private fun hostOk(url: String, platform: String): Boolean {
        val host = (try { URI(url.trim()).host?.lowercase(Locale.ROOT) } catch (e: Exception) { null }) ?: return false
        val list = platforms[platform] ?: return false
        return list.any { host == it || host.endsWith(".$it") }
    }

    private fun formatsFor(quality: String): List<String> = when (quality) {
        "medium" -> listOf(
            "bv*[vcodec^=avc1][height<=720]+ba[ext=m4a]/bv*[height<=720]+ba/b[height<=720]",
            "bv*[height<=480]+ba/b[height<=480]",
            "bv*+ba/b",
            "best"
        )
        "low" -> listOf(
            "bv*[vcodec^=avc1][height<=360]+ba[ext=m4a]/bv*[height<=360]+ba/b[height<=360]",
            "bv*[height<=480]+ba/b[height<=480]",
            "worst",
            "best"
        )
        else -> listOf(
            "bv*[vcodec^=avc1][height<=1080]+ba[ext=m4a]/bv*[height<=1080]+ba/b[height<=1080]",
            "bv*+ba/b",
            "best"
        )
    }

    private fun pickFile(dir: File): File? {
        val files = dir.listFiles()?.filter {
            it.isFile && it.length() > 0 &&
                !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".temp")
        } ?: return null
        val video = files.filter {
            it.extension.lowercase(Locale.ROOT) in setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "flv")
        }
        return (if (video.isNotEmpty()) video else files).maxByOrNull { it.length() }
    }

    private fun shortError(t: Throwable?): String {
        val raw = t?.message ?: return ""
        val line = raw.lineSequence().lastOrNull { it.contains("ERROR") } ?: raw.lineSequence().firstOrNull() ?: raw
        return line.trim().take(220)
    }

    private fun saveToGallery(f: File): String {
        val name = f.name
        val mime = when (f.extension.lowercase(Locale.ROOT)) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Downloading")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("مقدرتش احفظ الملف في المعرض")
            contentResolver.openOutputStream(uri)!!.use { out ->
                f.inputStream().use { it.copyTo(out) }
            }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, v, null, null)
        } else {
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Downloading")
            d.mkdirs()
            val t = File(d, name)
            f.copyTo(t, true)
            MediaScannerConnection.scanFile(this, arrayOf(t.absolutePath), null, null)
        }
        return "Movies/Downloading/$name"
    }

    private fun doDownload(url: String, quality: String) {
        awaitReady()
        initError?.let { throw RuntimeException("المحرك مشتغلش: $it") }

        val dir = File(cacheDir, "dl_" + System.currentTimeMillis())
        dir.mkdirs()
        try {
            val formats = formatsFor(quality)
            var last: Throwable? = null
            var out: File? = null
            var updated = false
            var i = 0
            while (i < formats.size && out == null) {
                try {
                    dir.listFiles()?.forEach { it.delete() }
                    val req = YoutubeDLRequest(url)
                    req.addOption("-f", formats[i])
                    req.addOption("--no-playlist")
                    req.addOption("--merge-output-format", "mp4")
                    req.addOption("--no-mtime")
                    req.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
                    YoutubeDL.getInstance().execute(
                        request = req,
                        processId = null,
                        callback = { progress, _, _ ->
                            if (!progress.isNaN()) {
                                js("window.onProgress(" + String.format(Locale.US, "%.1f", progress) + ")")
                            }
                        }
                    )
                    out = pickFile(dir)
                    if (out == null) i++
                } catch (t: Throwable) {
                    last = t
                    if (!updated) {
                        // أول فشل: حدّث yt-dlp وجرّب نفس الجودة تاني
                        updated = true
                        js("window.onStage('جاري تحديث المحرك وإعادة المحاولة...')")
                        updateEngine()
                    } else {
                        i++
                    }
                }
            }
            val file = out ?: throw RuntimeException(shortError(last).ifEmpty { "فشل التحميل" })
            js("window.onStage('جاري الحفظ في المعرض...')")
            val path = saveToGallery(file)
            js("window.onDone(${q(path)})")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------- الجسر بين الواجهة (HTML) والكود ----------

    inner class Bridge {

        @JavascriptInterface
        fun paste(): String {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
            } catch (_: Throwable) {
                ""
            }
        }

        @JavascriptInterface
        fun getInfo(url: String, platform: String, rid: String) {
            infoPool.execute {
                try {
                    if (!hostOk(url, platform)) {
                        js("window.onInfoError(${q(rid)}, ${q("الرابط ده مش تبع القسم اللي انت فيه")}, true)")
                        return@execute
                    }
                    awaitReady()
                    val req = YoutubeDLRequest(url)
                    req.addOption("--no-playlist")
                    val i = YoutubeDL.getInstance().getInfo(req)
                    val o = JSONObject()
                    o.put("title", i.title ?: "")
                    o.put("thumbnail", i.thumbnail ?: "")
                    o.put("duration", i.duration)
                    o.put("uploader", i.uploader ?: "")
                    js("window.onInfo(${q(rid)}, $o)")
                } catch (t: Throwable) {
                    js("window.onInfoError(${q(rid)}, ${q(shortError(t))}, false)")
                }
            }
        }

        @JavascriptInterface
        fun startDownload(url: String, platform: String, quality: String) {
            if (!hostOk(url, platform)) {
                js("window.onDownloadError(${q("الرابط ده مش تبع القسم اللي انت فيه")})")
                return
            }
            if (!busy.compareAndSet(false, true)) {
                js("window.onDownloadError(${q("فيه تحميل شغال دلوقتي، استنى يخلص")})")
                return
            }
            dlPool.execute {
                try {
                    doDownload(url.trim(), quality)
                } catch (t: Throwable) {
                    val msg = (t.message ?: "").trim().ifEmpty { "فشل التحميل" }
                    js("window.onDownloadError(${q(msg.take(240))})")
                } finally {
                    busy.set(false)
                }
            }
        }
    }
}
