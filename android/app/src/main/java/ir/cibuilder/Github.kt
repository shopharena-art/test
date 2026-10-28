package ir.cibuilder

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class Github(private val token: String, private val repo: String) {

    private val http = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    private val api get() = "https://api.github.com/repos/$repo"

    private fun req(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun call(r: Request): Response {
        val resp = http.newCall(r).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            val body = resp.body?.string()
            resp.close()
            throw IOException("HTTP $code: $body")
        }
        return resp
    }

    private fun json(r: Request): JSONObject = call(r).use { JSONObject(it.body!!.string()) }

    private fun jsonBody(s: String) = s.toRequestBody("application/json".toMediaType())

    fun defaultBranch(): String = json(req(api).build()).getString("default_branch")

    fun createRelease(tag: String): Long {
        val body = JSONObject().put("tag_name", tag).put("name", tag).put("draft", true)
        return json(req("$api/releases").post(jsonBody(body.toString())).build()).getLong("id")
    }

    fun uploadAsset(releaseId: Long, name: String, file: File): Long {
        val url = "https://uploads.github.com/repos/$repo/releases/$releaseId/assets?name=$name"
        val body = file.asRequestBody("application/zip".toMediaType())
        return json(req(url).post(body).build()).getLong("id")
    }

    fun dispatch(ref: String, inputs: Map<String, String>) {
        val i = JSONObject(); inputs.forEach { (k, v) -> i.put(k, v) }
        val body = JSONObject().put("ref", ref).put("inputs", i)
        call(req("$api/actions/workflows/build.yml/dispatches").post(jsonBody(body.toString())).build()).close()
    }

    /** پیدا کردن اجرایی که run-name آن شامل tag است */
    fun findRun(tag: String): Long? {
        val o = json(req("$api/actions/runs?event=workflow_dispatch&per_page=20").build())
        val runs = o.getJSONArray("workflow_runs")
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("display_title").contains(tag) || r.optString("name").contains(tag)) {
                return r.getLong("id")
            }
        }
        return null
    }

    /** (status, conclusion) */
    fun runState(id: Long): Pair<String, String> {
        val o = json(req("$api/actions/runs/$id").build())
        return o.getString("status") to o.optString("conclusion", "")
    }

    /** نام artifact -> شناسه */
    fun artifacts(runId: Long): Map<String, Long> {
        val arr = json(req("$api/actions/runs/$runId/artifacts").build()).getJSONArray("artifacts")
        return (0 until arr.length()).associate {
            val a = arr.getJSONObject(it); a.getString("name") to a.getLong("id")
        }
    }

    fun downloadArtifact(id: Long): ByteArray =
        call(req("$api/actions/artifacts/$id/zip").build()).use { it.body!!.bytes() }

    // پاک‌سازی؛ خطاها نادیده گرفته می‌شوند
    private fun del(url: String) = try { call(req(url).delete().build()).close() } catch (_: Exception) {}

    fun deleteRelease(id: Long) = del("$api/releases/$id")
    fun deleteArtifact(id: Long) = del("$api/actions/artifacts/$id")
    fun deleteRun(id: Long) = del("$api/actions/runs/$id")
}
