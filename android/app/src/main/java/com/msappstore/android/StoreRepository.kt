package com.msappstore.android

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class StoreApp(
    val id: String,
    val name: String,
    val developerName: String,
    val appType: String,
    val category: String,
    val version: String,
    val description: String,
    val packageName: String,
    val iconUrl: String?,
    val apkPath: String
)

class StoreRepository {
    companion object {
        // Public publishable key only. Never put a Supabase secret/service-role key in the app.
        private const val BASE_URL = "https://opnubbmamtybumihkdpa.supabase.co"
        private const val PUBLISHABLE_KEY = "sb_publishable_98EvT0vNnoYEu9DCUcqjzA_kKxLjwRk"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun requestBuilder(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("apikey", PUBLISHABLE_KEY)
        .header("Authorization", "Bearer $PUBLISHABLE_KEY")

    suspend fun loadPublishedApps(): List<StoreApp> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/rest/v1/apps?select=id,name,developer_name,app_type,category,version,description,package_name,icon_path,apk_path&status=eq.approved&order=created_at.desc"
        val request = requestBuilder(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Store load failed (${response.code}): ${response.body?.string().orEmpty()}")
            val array = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val iconPath = item.optString("icon_path").takeIf { it.isNotBlank() && it != "null" }
                    add(
                        StoreApp(
                            id = item.optString("id"),
                            name = item.optString("name", "Unnamed app"),
                            developerName = item.optString("developer_name", "Developer"),
                            appType = item.optString("app_type", "app"),
                            category = item.optString("category", ""),
                            version = item.optString("version", ""),
                            description = item.optString("description", ""),
                            packageName = item.optString("package_name", ""),
                            iconUrl = iconPath?.let { signedAssetUrl("app-screenshots", it) },
                            apkPath = item.optString("apk_path", "")
                        )
                    )
                }
            }
        }
    }

    private fun signedAssetUrl(bucket: String, path: String): String? {
        val encodedPath = path.split("/").joinToString("/") { Uri.encode(it) }
        val body = JSONObject().put("expiresIn", 3600).toString()
            .toRequestBody("application/json".toMediaType())
        val request = requestBuilder("$BASE_URL/storage/v1/object/sign/$bucket/$encodedPath")
            .post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val signed = JSONObject(response.body?.string().orEmpty()).optString("signedURL")
                if (signed.isBlank()) null else if (signed.startsWith("http")) signed else "$BASE_URL$signed"
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun createApkDownloadUrl(path: String): String = withContext(Dispatchers.IO) {
        if (path.isBlank()) throw IOException("APK file path is missing.")
        val encodedPath = path.split("/").joinToString("/") { Uri.encode(it) }
        val body = JSONObject().put("expiresIn", 900).toString()
            .toRequestBody("application/json".toMediaType())
        val request = requestBuilder("$BASE_URL/storage/v1/object/sign/app-apks/$encodedPath")
            .post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("APK download link failed (${response.code}). Storage access policy needs checking.")
            val signed = JSONObject(response.body?.string().orEmpty()).optString("signedURL")
            if (signed.isBlank()) throw IOException("Supabase did not return a download link.")
            if (signed.startsWith("http")) signed else "$BASE_URL$signed"
        }
    }
}
