package com.msappstore.android

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DeepBlack = Color(0xFF07110E)
private val DeepGreen = Color(0xFF0D2119)
private val Emerald = Color(0xFF00C878)
private val Gold = Color(0xFFFFC857)
private val Muted = Color(0xFF9BAFA5)
private val DownloadBlue = Color(0xFF2196F3)

class MainActivity : ComponentActivity() {
    private val repository = StoreRepository()
    private var apps by mutableStateOf<List<StoreApp>>(emptyList())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var selectedApp by mutableStateOf<StoreApp?>(null)
    private var search by mutableStateOf("")
    private var filter by mutableStateOf("All")
    private var downloadProgress by mutableStateOf<Map<String, Int>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadApps()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Emerald, secondary = Gold, background = DeepBlack,
                surface = DeepGreen, onPrimary = DeepBlack,
                onBackground = Color.White, onSurface = Color.White
            )) {
                Surface(Modifier.fillMaxSize(), color = DeepBlack) {
                    if (selectedApp == null) StoreHome(
                        apps = apps, loading = loading, error = error,
                        search = search, onSearch = { search = it },
                        filter = filter, onFilter = { filter = it },
                        onRefresh = { loadApps() }, onSelect = { selectedApp = it }
                    ) else AppDetails(
                        app = selectedApp!!, progress = downloadProgress[selectedApp!!.id],
                        onBack = { selectedApp = null }, onInstall = { startDownload(selectedApp!!) }
                    )
                }
            }
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            loading = true
            error = null
            try { apps = repository.loadPublishedApps() }
            catch (e: Exception) { error = e.message ?: "Could not load apps." }
            finally { loading = false }
        }
    }

    private fun startDownload(app: StoreApp) {
        lifecycleScope.launch {
            try {
                val url = repository.createApkDownloadUrl(app.apkPath)
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(app.name)
                    .setDescription("Downloading from MS App Store")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(this@MainActivity, Environment.DIRECTORY_DOWNLOADS,
                        "${app.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk")
                val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val downloadId = manager.enqueue(request)
                downloadProgress = downloadProgress + (app.id to 0)
                monitorDownload(manager, downloadId, app)
                Toast.makeText(this@MainActivity, "Download started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun monitorDownload(manager: DownloadManager, id: Long, app: StoreApp) {
        withContext(Dispatchers.IO) {
            var finished = false
            while (!finished) {
                val cursor: Cursor? = manager.query(DownloadManager.Query().setFilterById(id))
                cursor?.use {
                    if (it.moveToFirst()) {
                        val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val soFar = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (total > 0) {
                            val percent = ((soFar * 100) / total).toInt().coerceIn(0, 100)
                            withContext(Dispatchers.Main) { downloadProgress = downloadProgress + (app.id to percent) }
                        }
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            finished = true
                            val uri = manager.getUriForDownloadedFile(id)
                            withContext(Dispatchers.Main) {
                                if (uri != null) installApk(uri)
                                else Toast.makeText(this@MainActivity, "Downloaded file could not be opened.", Toast.LENGTH_LONG).show()
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            finished = true
                            withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Download failed. Please retry.", Toast.LENGTH_LONG).show() }
                        }
                    } else finished = true
                } ?: run { finished = true }
                if (!finished) delay(500)
            }
        }
    }

    private fun installApk(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) }
        catch (_: Exception) { Toast.makeText(this, "Android could not open the installer.", Toast.LENGTH_LONG).show() }
    }
}

@Composable
private fun StoreHome(
    apps: List<StoreApp>, loading: Boolean, error: String?,
    search: String, onSearch: (String) -> Unit,
    filter: String, onFilter: (String) -> Unit,
    onRefresh: () -> Unit, onSelect: (StoreApp) -> Unit
) {
    val shown = apps.filter {
        (filter == "All" || (filter == "Games" && it.appType == "game") || (filter == "Apps" && it.appType != "game")) &&
            (search.isBlank() || listOf(it.name, it.developerName, it.category, it.description).any { value -> value.contains(search, true) })
    }
    Column(Modifier.fillMaxSize().background(DeepBlack)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MS App Store", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("Discover apps & games", color = Muted, fontSize = 12.sp)
            }
            IconButton(onClick = { }) { Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = Gold) }
        }
        OutlinedTextField(
            value = search, onValueChange = onSearch, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Search apps and games") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            singleLine = true, shape = RoundedCornerShape(16.dp)
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Apps", "Games").forEach { item ->
                val selected = filter == item
                Surface(color = if (selected) Emerald else DeepGreen, shape = CircleShape,
                    modifier = Modifier.clickable { onFilter(item) }) {
                    Text(item, color = if (selected) DeepBlack else Color.White,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("↻", color = Gold, modifier = Modifier.clickable { onRefresh() }.padding(8.dp), fontSize = 20.sp)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Emerald) }
            error != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Store could not load", color = Color.White, fontWeight = FontWeight.Bold)
                Text(error, color = Muted, modifier = Modifier.padding(vertical = 10.dp))
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Emerald)) { Text("Retry", color = DeepBlack) }
            }
            shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No published apps found.", color = Muted) }
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.id }) { app ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(DeepGreen)
                            .clickable { onSelect(app) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app.iconUrl != null) AsyncImage(app.iconUrl, contentDescription = app.name,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)), contentScale = ContentScale.Crop)
                        else Box(Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF173B2B)), contentAlignment = Alignment.Center) {
                            Text("MS", color = Gold, fontWeight = FontWeight.ExtraBold)
                        }
                        Column(Modifier.weight(1f).padding(start = 13.dp)) {
                            Text(app.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.developerName, color = Muted, fontSize = 12.sp)
                            Text("${app.category} · v${app.version}", color = Gold, fontSize = 11.sp)
                        }
                        Icon(Icons.Default.Download, contentDescription = "Open app", tint = Emerald)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppDetails(app: StoreApp, progress: Int?, onBack: () -> Unit, onInstall: () -> Unit) {
    Column(Modifier.fillMaxSize().background(DeepBlack)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("App details", color = Gold, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (app.iconUrl != null) AsyncImage(app.iconUrl, contentDescription = app.name,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)).background(DeepGreen), contentAlignment = Alignment.Center) {
                    Text("MS", color = Gold, fontWeight = FontWeight.ExtraBold)
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(app.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(app.developerName, color = Muted)
                    Text("v${app.version} · ${app.category}", color = Gold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            if (progress == null || progress >= 100) {
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald)) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = DeepBlack)
                    Text("Install", color = DeepBlack, modifier = Modifier.padding(start = 8.dp), fontWeight = FontWeight.Bold)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { progress / 100f }, modifier = Modifier.size(54.dp),
                            color = DownloadBlue, trackColor = DeepGreen, strokeWidth = 5.dp)
                        Text("$progress%", color = Color.White, fontSize = 11.sp)
                    }
                    Text("Downloading… $progress%", color = DownloadBlue, modifier = Modifier.padding(start = 14.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Divider(color = DeepGreen)
            Text("About this app", color = Gold, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Text(app.description.ifBlank { "No description available." }, color = Muted, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Text("Package: ${app.packageName.ifBlank { "Not provided" }}", color = Muted, fontSize = 12.sp)
        }
    }
}
