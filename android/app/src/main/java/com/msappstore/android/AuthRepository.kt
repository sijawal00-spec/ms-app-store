package com.msappstore.android

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AccountState(
    val accessToken: String = "",
    val email: String = "",
    val displayName: String = "",
    val avatarUri: String = ""
) {
    val signedIn: Boolean get() = accessToken.isNotBlank()
}

class AuthRepository(context: Context) {
    private val prefs = context.getSharedPreferences("ms_account", Context.MODE_PRIVATE)
    private val client = OkHttpClient()
    private val base = "https://opnubbmamtybumihkdpa.supabase.co"
    private val key = "sb_publishable_98EvT0vNnoYEu9DCUcqjzA_kKxLjwRk"

    fun load(): AccountState = AccountState(
        accessToken = prefs.getString("access_token", "").orEmpty(),
        email = prefs.getString("email", "").orEmpty(),
        displayName = prefs.getString("display_name", "").orEmpty(),
        avatarUri = prefs.getString("avatar_uri", "").orEmpty()
    )

    fun saveAvatar(uri: String): AccountState {
        prefs.edit().putString("avatar_uri", uri).apply()
        return load()
    }

    fun signOut(): AccountState {
        prefs.edit().clear().apply()
        return load()
    }

    suspend fun authenticate(email: String, password: String, name: String, signup: Boolean): AccountState {
        val endpoint = if (signup) "$base/auth/v1/signup" else "$base/auth/v1/token?grant_type=password"
        val payload = JSONObject().put("email", email.trim()).put("password", password)
        if (signup && name.isNotBlank()) payload.put("data", JSONObject().put("full_name", name.trim()))
        val request = Request.Builder().url(endpoint)
            .header("apikey", key).header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return withContext(Dispatchers.IO) { client.newCall(request).execute().use { response ->
            val body = JSONObject(response.body?.string().orEmpty().ifBlank { "{}" })
            if (!response.isSuccessful) {
                val message = body.optString("msg").ifBlank {
                    body.optString("message").ifBlank { body.optString("error_description").ifBlank { "Authentication failed (" + response.code + ")." } }
                }
                throw IOException(message)
            }
            val token = body.optString("access_token")
            val user = body.optJSONObject("user") ?: body
            if (token.isBlank()) {
                if (signup) throw IOException("Account created. Email verification may be required; verify your email, then sign in.")
                throw IOException("Supabase did not return a sign-in session.")
            }
            val metadata = user.optJSONObject("user_metadata") ?: JSONObject()
            val state = AccountState(
                accessToken = token,
                email = user.optString("email", email.trim()),
                displayName = metadata.optString("full_name", name).ifBlank { email.substringBefore("@") },
                avatarUri = load().avatarUri
            )
            prefs.edit().putString("access_token", state.accessToken)
                .putString("email", state.email).putString("display_name", state.displayName).apply()
            state
        } }
    }
}
