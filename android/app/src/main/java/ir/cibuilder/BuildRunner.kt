package ir.cibuilder

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BuildResult(
    val ok: Boolean,
    val log: String,
    val files: List<Pair<String, Uri>>
)

object BuildRunner {

    /** زیپ کاربر را کپی می‌کند و فایل .build-secrets.env را به آن اضافه می‌کند */
    private fun injectSecrets(ctx: Context, src: Uri, dst: File, secrets: Map<String, String>) {
        ZipOutputStream(dst.outputStream().buffered()).use { out ->
            ctx.contentResolver.openInputStream(src)!!.use { ins ->
                ZipInputStream(ins.buffered()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        if (e.name != ".build-secrets.env") {
                            out.putNextEntry(ZipEntry(e.name))
                            if (!e.isDirectory) zin.copyTo(out)
                            out.closeEntry()
                        }
                        e = zin.nextEntry
                    }
                }
            }
            if (secrets.isNotEmpty()) {
                val text = secrets.entries.joinToString("\n") { (k, v) ->
                    k + ":" + Base64.encodeToString(v.toByteArray(), Base64.NO_WRAP)
                } + "\n"
                out.putNextEntry(ZipEntry(".build-secrets.env"))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "apk" -> "application/vnd.android.package-archive"
        "exe" -> "application/vnd.microsoft.portable-executable"
        "txt", "log" -> "text/plain"
        else -> "application/octet-stream"
    }

    private fun saveDownload(ctx: Context, name: String, bytes: ByteArray): Uri {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: error("ذخیره در Downloads ممکن نشد")
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val res = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory) res += e.name.substringAfterLast('/') to z.readBytes()
                e = z.nextEntry
            }
        }
        return res
    }

    suspend fun run(
        ctx: Context,
        store: Store,
        app: String,
        zip: Uri,
        target: String,
        log: (String) -> Unit
    ): BuildResult = withContext(Dispatchers.IO) {
        val gh = Github(store.token, store.repo)
        val tag = "b" + System.currentTimeMillis()
        val work = File(ctx.cacheDir, tag).apply { mkdirs() }
        var releaseId = 0L
        var runId = 0L
        val artIds = mutableListOf<Long>()
        var logText = ""
        val saved = mutableListOf<Pair<String, Uri>>()
        var ok = false

        try {
            log("آماده‌سازی زیپ و secretها…")
            val packed = File(work, "src.zip")
            injectSecrets(ctx, zip, packed, store.get(app))

            log("آپلود موقت روی گیت‌هاب…")
            releaseId = gh.createRelease(tag)
            val assetId = gh.uploadAsset(releaseId, "src.zip", packed)

            log("شروع کامپایل ($target)…")
            val branch = gh.defaultBranch()
            gh.dispatch(branch, mapOf("asset_id" to assetId.toString(), "target" to target, "run_tag" to tag))

            log("منتظر شروع اجرا…")
            var tries = 0
            while (runId == 0L && tries < 30) {
                delay(3000)
                runId = gh.findRun(tag) ?: 0L
                tries++
            }
            if (runId == 0L) error("اجرای workflow پیدا نشد. فایل build.yml در شاخهٔ پیش‌فرض ریپو هست؟")

            var last = ""
            var conclusion = ""
            while (true) {
                val (status, concl) = gh.runState(runId)
                if (status != last) { log("وضعیت: $status"); last = status }
                if (status == "completed") { conclusion = concl; break }
                delay(5000)
            }
            ok = conclusion == "success"
            log(if (ok) "کامپایل موفق بود." else "کامپایل ناموفق: $conclusion")

            val arts = gh.artifacts(runId)
            arts.values.forEach { artIds += it }

            arts["log"]?.let { id ->
                unzip(gh.downloadArtifact(id)).firstOrNull()?.let { logText = String(it.second) }
            }
            arts["output"]?.let { id ->
                unzip(gh.downloadArtifact(id)).forEach { (name, bytes) ->
                    val uri = saveDownload(ctx, "$app-$name", bytes)
                    saved += "$app-$name" to uri
                }
            }
            if (saved.isNotEmpty()) log("فایل‌ها در پوشهٔ Downloads ذخیره شد.")
        } catch (e: Exception) {
            log("خطا: ${e.message}")
            logText = logText.ifEmpty { e.toString() }
        } finally {
            log("پاک‌سازی گیت‌هاب…")
            artIds.forEach { gh.deleteArtifact(it) }
            if (runId != 0L) gh.deleteRun(runId)
            if (releaseId != 0L) gh.deleteRelease(releaseId)
            work.deleteRecursively()
        }
        BuildResult(ok, logText, saved)
    }
}
