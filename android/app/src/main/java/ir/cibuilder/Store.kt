package ir.cibuilder

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * ذخیرهٔ رمزنگاری‌شده: توکن گیت‌هاب، نام ریپو و secretها به تفکیک نام برنامه.
 * ساختار secretها: { "نام برنامه": { "KEY": "value", ... }, ... }
 */
class Store(ctx: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        ctx,
        "cibuilder_store",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    /** به شکل owner/repo */
    var repo: String
        get() = prefs.getString("repo", "") ?: ""
        set(v) = prefs.edit().putString("repo", v).apply()

    private fun load() = JSONObject(prefs.getString("secrets", "{}") ?: "{}")
    private fun save(o: JSONObject) = prefs.edit().putString("secrets", o.toString()).apply()

    fun apps(): List<String> = load().keys().asSequence().toList().sorted()

    fun get(app: String): Map<String, String> {
        val o = load().optJSONObject(app) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    fun put(app: String, key: String, value: String) {
        val all = load()
        val o = all.optJSONObject(app) ?: JSONObject().also { all.put(app, it) }
        o.put(key, value)
        save(all)
    }

    fun remove(app: String, key: String) {
        val all = load()
        all.optJSONObject(app)?.remove(key)
        save(all)
    }

    fun deleteApp(app: String) {
        val all = load()
        all.remove(app)
        save(all)
    }

    /** خروجی برای بک‌آپ (فقط secretها، بدون توکن) */
    fun export(): String = load().toString(2)

    /** بازیابی بک‌آپ؛ با داده‌های فعلی ادغام می‌شود */
    fun import(json: String) {
        val inc = JSONObject(json)
        val all = load()
        inc.keys().forEach { app ->
            val src = inc.getJSONObject(app)
            val dst = all.optJSONObject(app) ?: JSONObject().also { all.put(app, it) }
            src.keys().forEach { k -> dst.put(k, src.getString(k)) }
        }
        save(all)
    }
}
