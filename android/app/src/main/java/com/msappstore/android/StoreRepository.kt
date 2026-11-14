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
    val screenshotUrls: List<String>,
    val apkPath: String,
    val apkSizeBytes: Long,
    val status: String = "approved",
    val adminComment: String? = null
)

data class AppReview(val id: String, val userName: String, val avatarUrl: String, val rating: Int, val comment: String, val createdAt: String)

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
        val url = "$BASE_URL/rest/v1/apps?select=id,name,developer_name,app_type,category,version,description,package_name,icon_path,apk_path,apk_size_bytes&status=eq.approved&order=created_at.desc"
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
                            screenshotUrls = loadScreenshotUrls(item.optString("id")),
                            apkPath = item.optString("apk_path", ""),
                            apkSizeBytes = item.optLong("apk_size_bytes", 0L),
                            status = item.optString("status", "approved"),
                            adminComment = item.optString("admin_comment").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
        }
    }

    suspend fun loadMyApps(accessToken: String, userId: String): List<StoreApp> = withContext(Dispatchers.IO) {
        if (accessToken.isBlank() || userId.isBlank()) return@withContext emptyList()
        val url = "$BASE_URL/rest/v1/apps?select=id,name,developer_name,app_type,category,version,description,package_name,icon_path,apk_path,apk_size_bytes,status,admin_comment&user_id=eq.${Uri.encode(userId)}&order=created_at.desc"
        val request = requestBuilder(url).header("Authorization", "Bearer $accessToken").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Your apps load failed (${response.code}): ${response.body?.string().orEmpty()}")
            val array = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val iconPath = item.optString("icon_path").takeIf { it.isNotBlank() && it != "null" }
                    add(StoreApp(
                        id = item.optString("id"), name = item.optString("name", "Unnamed app"),
                        developerName = item.optString("developer_name", "Developer"),
                        appType = item.optString("app_type", "app"), category = item.optString("category", ""),
                        version = item.optString("version", ""), description = item.optString("description", ""),
                        packageName = item.optString("package_name", ""),
                        iconUrl = iconPath?.let { signedAssetUrl("app-screenshots", it) }, screenshotUrls = emptyList(),
                        apkPath = item.optString("apk_path", ""), apkSizeBytes = item.optLong("apk_size_bytes", 0L),
                        status = item.optString("status", "pending"),
                        adminComment = item.optString("admin_comment").takeIf { it.isNotBlank() && it != "null" }
                    ))
                }
            }
        }
    }

    private fun loadScreenshotUrls(appId: String): List<String> {
        if (appId.isBlank()) return emptyList()
        val request = requestBuilder(
            "$BASE_URL/rest/v1/app_screenshots?select=storage_path&app_id=eq.${Uri.encode(appId)}&order=created_at.asc"
        ).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val rows = JSONArray(response.body?.string().orEmpty())
                buildList {
                    for (i in 0 until rows.length()) {
                        val path = rows.getJSONObject(i).optString("storage_path")
                        if (path.isNotBlank() && path != "null") {
                            signedAssetUrl("app-screenshots", path)?.let(::add)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun signedAssetUrl(bucket: String, path: String): String? {
        if (path.isBlank()) return null
        val encodedPath = path.split("/").joinToString("/") { Uri.encode(it) }
        val body = JSONObject().put("expiresIn", 3600).toString()
            .toRequestBody("application/json".toMediaType())
        val request = requestBuilder("$BASE_URL/storage/v1/object/sign/$bucket/$encodedPath")
            .post(body).build()
        return try {
            client.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) return null
                val signed = JSONObject(responseText).optString("signedURL")
                if (signed.isBlank()) null else absoluteSignedUrl(signed)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun absoluteSignedUrl(signed: String): String {
        if (signed.startsWith("http://") || signed.startsWith("https://")) return signed
        val normalized = if (signed.startsWith("/")) signed else "/$signed"
        // Supabase returns paths relative to /storage/v1, not the project root.
        return "$BASE_URL/storage/v1$normalized"
    }


    suspend fun loadAppReviews(appId: String): List<AppReview> = withContext(Dispatchers.IO) {
        val request = requestBuilder("$BASE_URL/rest/v1/app_reviews?select=id,user_name,avatar_url,rating,comment,created_at&app_id=eq.${Uri.encode(appId)}&order=created_at.desc&limit=100").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Reviews load failed (${response.code}).")
            val rows = JSONArray(response.body?.string().orEmpty())
            buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    add(AppReview(row.optString("id"), row.optString("user_name", "User"), row.optString("avatar_url", ""), row.optInt("rating"), row.optString("comment"), row.optString("created_at")))
                }
            }
        }
    }

    suspend fun submitAppReview(appId: String, accessToken: String, userId: String, userName: String, avatarUrl: String, rating: Int, comment: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("app_id", appId).put("user_id", userId)
            .put("user_name", userName).put("avatar_url", avatarUrl)
            .put("rating", rating).put("comment", comment.trim())
        val request = Request.Builder().url("$BASE_URL/rest/v1/app_reviews?on_conflict=app_id,user_id")
            .header("apikey", PUBLISHABLE_KEY).header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json").header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Review post failed (${response.code}): ${response.body?.string().orEmpty()}")
        }
    }

    suspend fun loadDownloadCount(appId: String): Int = withContext(Dispatchers.IO) {
        val request = requestBuilder("$BASE_URL/rest/v1/app_downloads?select=id&app_id=eq.${Uri.encode(appId)}")
            .header("Prefer", "count=exact").header("Range", "0-0").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext 0
            val total = response.header("Content-Range")?.substringAfter("/")?.toIntOrNull()
            total ?: JSONArray(response.body?.string().orEmpty()).length()
        }
    }

    suspend fun recordDownload(appId: String, accessToken: String = "", userId: String = "") = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("app_id", appId)
        if (userId.isBlank()) payload.put("user_id", JSONObject.NULL) else payload.put("user_id", userId)
        val builder = Request.Builder().url("$BASE_URL/rest/v1/app_downloads")
            .header("apikey", PUBLISHABLE_KEY).header("Content-Type", "application/json")
        if (accessToken.isNotBlank()) builder.header("Authorization", "Bearer $accessToken")
        else builder.header("Authorization", "Bearer $PUBLISHABLE_KEY")
        val request = builder.post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download count update failed (${response.code}).")
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
            absoluteSignedUrl(signed)
        }
    }
}
