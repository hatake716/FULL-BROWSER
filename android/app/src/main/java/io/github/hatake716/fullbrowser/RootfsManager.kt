package io.github.hatake716.fullbrowser

import android.content.Context
import android.net.ConnectivityManager
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * rootfs イメージの取得・検証・展開と、セッション前に rootfs へ書き込むファイルの管理。
 *
 * 配置 (docs/ARCHITECTURE.md §7):
 *   files/images/<file>            ダウンロード途中/完了の .tar.xz (展開後に削除)
 *   files/rootfs/<variant>.tmp     展開中
 *   files/rootfs/<variant>         完成 (rename で原子的に切替)
 *   files/lib/                     proot のランタイム lib (assets からコピー)
 *   files/tmp/                     PROOT_TMP_DIR
 */
class RootfsManager(private val context: Context) {

    data class Image(val variant: String, val file: String, val sha256: String, val size: Long, val extracted: Long, val browser: String)
    data class Manifest(val build: String, val suite: String, val baseUrl: String, val images: Map<String, Image>)

    sealed class Progress {
        data class Downloading(val percent: Int, val bytes: Long, val total: Long) : Progress()
        object Verifying : Progress()
        data class Extracting(val percent: Int) : Progress()
        object Done : Progress()
    }

    class NotEnoughSpace(val neededBytes: Long) : IOException("not enough space: $neededBytes")

    val filesDir: File get() = context.filesDir
    val libDir: File get() = File(filesDir, "lib")
    val tmpDir: File get() = File(filesDir, "tmp")
    private val imagesDir: File get() = File(filesDir, "images")

    fun rootfsDir(variant: String): File = File(filesDir, "rootfs/$variant")
    fun isInstalled(variant: String): Boolean = File(rootfsDir(variant), "usr/local/bin/fb-session").exists()

    /** ブラウザが使える状態か (Chrome は本体の取得まで終わっているか) */
    fun isBrowserReady(b: Browser): Boolean {
        if (!b.supported || !isInstalled(b.imageVariant)) return false
        if (b.needsOnDeviceInstall) return File(rootfsDir(b.imageVariant), "opt/google/chrome/chrome").exists()
        return true
    }

    fun readyBrowsers(): List<Browser> = Browser.selectable.filter { isBrowserReady(it) }

    fun prootPaths(variant: String) = ProotCommand.Paths(
        nativeLibDir = File(context.applicationInfo.nativeLibraryDir),
        filesDir = filesDir,
        rootfs = rootfsDir(variant),
    )

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // ------------------------------------------------------------------ manifest

    /**
     * manifest の取得先。開発時は `files/manifest-url.override` に URL を書けば差し替えられる
     * (`adb shell run-as <pkg> sh -c 'echo http://127.0.0.1:8000/manifest.json > files/manifest-url.override'`)。
     * アプリ専用領域なので第三者は書けない。
     */
    fun manifestUrl(): String =
        File(filesDir, "manifest-url.override").takeIf { it.isFile }
            ?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: MANIFEST_URL

    suspend fun fetchManifest(url: String = manifestUrl()): Manifest = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("manifest HTTP ${res.code}")
            parseManifest(res.body?.string() ?: throw IOException("empty manifest"))
        }
    }

    fun parseManifest(text: String): Manifest {
        val j = JSONObject(text)
        require(j.optInt("schema", 0) == 1) { "unsupported manifest schema" }
        val images = mutableMapOf<String, Image>()
        val ji = j.getJSONObject("images")
        for (key in ji.keys()) {
            val o = ji.getJSONObject(key)
            images[key] = Image(
                variant = key,
                file = o.getString("file"),
                sha256 = o.getString("sha256").lowercase(),
                size = o.getLong("size"),
                extracted = o.optLong("extracted", 0L),
                browser = o.optString("browser", key),
            )
        }
        return Manifest(j.getString("build"), j.optString("suite", ""), j.getString("base_url"), images)
    }

    // ------------------------------------------------------------------ install

    fun freeBytes(): Long = StatFs(filesDir.absolutePath).availableBytes

    /** 展開後サイズ + ダウンロードファイル + 余裕 (1.2 倍) */
    fun requiredBytes(img: Image): Long = ((img.extracted.takeIf { it > 0 } ?: img.size * 4) * 1.2).toLong() + img.size

    /**
     * イメージを取得して files/rootfs/<variant> に展開する。途中失敗は次回に続きから再開できる。
     */
    suspend fun install(manifest: Manifest, img: Image, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        if (freeBytes() < requiredBytes(img)) throw NotEnoughSpace(requiredBytes(img))
        imagesDir.mkdirs(); tmpDir.mkdirs()
        val archive = File(imagesDir, img.file)

        if (!(archive.exists() && archive.length() == img.size && sha256(archive) == img.sha256)) {
            download(manifest.baseUrl + img.file, archive, img.size, onProgress)
            onProgress(Progress.Verifying)
            val sum = sha256(archive)
            if (sum != img.sha256) {
                archive.delete()
                throw IOException("sha256 mismatch: $sum")
            }
        }

        val finalDir = rootfsDir(img.variant)
        val tmp = File(finalDir.path + ".tmp")
        if (tmp.exists()) tmp.deleteRecursively()
        if (finalDir.exists()) finalDir.deleteRecursively()
        tmp.mkdirs()
        extractTarXz(archive, tmp, onProgress)
        if (!tmp.renameTo(finalDir)) throw IOException("rename failed: $tmp -> $finalDir")
        archive.delete()
        onProgress(Progress.Done)
    }

    private suspend fun download(url: String, dest: File, total: Long, onProgress: (Progress) -> Unit) {
        var have = if (dest.exists()) dest.length() else 0L
        if (have > total) { dest.delete(); have = 0L }
        val rb = Request.Builder().url(url)
        if (have > 0) rb.header("Range", "bytes=$have-")
        http.newCall(rb.build()).execute().use { res ->
            val append = res.code == 206
            if (!append && res.code != 200) throw IOException("download HTTP ${res.code}")
            if (!append) have = 0L
            val body = res.body ?: throw IOException("empty body")
            FileOutputStream(dest, append).use { out ->
                val buf = ByteArray(1 shl 16)
                var last = -1
                body.byteStream().use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        val pct = if (total > 0) (have * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; onProgress(Progress.Downloading(pct, have, total)) }
                    }
                }
            }
        }
        if (total > 0 && dest.length() != total) throw IOException("download incomplete: ${dest.length()} / $total")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** symlink / 権限を保ったまま .tar.xz を展開する。デバイスファイル等は無視。 */
    private suspend fun extractTarXz(archive: File, dest: File, onProgress: (Progress) -> Unit) {
        val destCanon = dest.canonicalPath
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 20))
        val total = archive.length().coerceAtLeast(1)
        var last = -1
        TarArchiveInputStream(XZInputStream(counting)).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                val out = File(dest, name)
                val canon = out.canonicalPath
                if (canon != destCanon && !canon.startsWith("$destCanon/")) throw IOException("bad entry: ${e.name}")
                val mode = e.mode and 0b111_111_111_111
                when {
                    e.isDirectory -> { out.mkdirs(); chmod(out, mode or 0b111_000_000) }
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs(); if (out.exists() || isLink(out)) out.delete()
                        Os.symlink(e.linkName, out.path)
                    }
                    e.isLink -> { // hard link: アプリ領域では作れないのでコピー
                        val src = File(dest, e.linkName.removePrefix("./"))
                        out.parentFile?.mkdirs(); src.copyTo(out, overwrite = true); chmod(out, mode)
                    }
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { o -> tar.copyTo(o, 1 shl 16) }
                        chmod(out, mode)
                    }
                    else -> Log.w(App.TAG, "skip ${e.name}")
                }
                val pct = (counting.count * 100 / total).toInt().coerceIn(0, 100)
                if (pct != last) { last = pct; onProgress(Progress.Extracting(pct)) }
            }
        }
    }

    private fun isLink(f: File): Boolean = try { Os.lstat(f.path); true } catch (_: ErrnoException) { false }
    private fun chmod(f: File, mode: Int) { try { Os.chmod(f.path, mode) } catch (e: ErrnoException) { Log.w(App.TAG, "chmod ${f.path}: $e") } }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count = 0L; private set
        override fun read(): Int = inner.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) count += it }
        override fun close() = inner.close()
    }

    // ------------------------------------------------------------------ runtime files

    /** assets/proot の lib を files/lib へ (バージョンが変わったときだけコピー) */
    fun ensureRuntimeLibs() {
        val am = context.assets
        val version = am.open("proot/VERSION").bufferedReader().readText()
        val stamp = File(libDir, "VERSION")
        if (stamp.exists() && stamp.readText() == version) return
        libDir.mkdirs()
        for (name in am.list("proot").orEmpty()) {
            if (name == "VERSION") continue
            am.open("proot/$name").use { i -> FileOutputStream(File(libDir, name)).use { o -> i.copyTo(o) } }
        }
        stamp.writeText(version)
        Log.i(App.TAG, "runtime libs installed: ${version.trim()}")
    }

    /** セッション直前: resolv.conf, 設定 env, 作業ディレクトリ */
    fun prepareForSession(variant: String, prefs: Prefs) {
        val r = rootfsDir(variant)
        tmpDir.mkdirs(); File(tmpDir, "l2s").mkdirs()
        File(r, "tmp/.shm").mkdirs(); File(r, "tmp/.X11-unix").mkdirs()
        // 音声 FIFO はここ (Android 側) で作る。ゲストが作り直すと、先に open して
        // ブロックしている読み手が削除済み inode に取り残される競合が起きる
        val fifo = File(r, "tmp/.fb-audio")
        val isFifo = runCatching { android.system.OsConstants.S_ISFIFO(android.system.Os.stat(fifo.path).st_mode) }.getOrDefault(false)
        if (!isFifo) {
            fifo.delete()
            runCatching { android.system.Os.mkfifo(fifo.path, "666".toInt(8)) }
        }
        File(r, "root/.config/fullbrowser").mkdirs()
        File(r, "root/.config/fullbrowser/env").writeText(prefs.guestEnvFile())
        File(r, "etc/resolv.conf").writeText(resolvConf())
    }

    private fun resolvConf(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val servers = cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers
            ?.map { it.hostAddress ?: "" }?.filter { it.isNotEmpty() && !it.startsWith("127.") }.orEmpty()
        val list = if (servers.isEmpty()) listOf("1.1.1.1", "8.8.8.8") else servers + listOf("1.1.1.1")
        return list.joinToString("") { "nameserver $it\n" } + "options timeout:2 attempts:2\n"
    }

    fun xkbConfigRoot(variant: String): File = File(rootfsDir(variant), "usr/share/X11/xkb")
    fun guestTmpDir(variant: String): File = File(rootfsDir(variant), "tmp")

    companion object {
        const val MANIFEST_URL = "https://github.com/hatake716/FULL-BROWSER/releases/download/rootfs-latest/manifest.json"
    }
}
