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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

private val appCategories = listOf(
    "Art & Design", "Auto & Vehicles", "Beauty", "Books & Reference", "Business",
    "Comics", "Communication", "Dating", "Education", "Entertainment", "Events",
    "Finance", "Food & Drink", "Health & Fitness", "House & Home", "Libraries & Demo",
    "Lifestyle", "Maps & Navigation", "Medical", "Music & Audio", "News & Magazines",
    "Parenting", "Personalization", "Photography", "Productivity", "Shopping", "Social",
    "Sports", "Tools", "Travel & Local", "Video Players & Editors", "Weather", "Downloader"
)
private val gameCategories = listOf(
    "Action", "Adventure", "Arcade", "Board", "Card", "Casino", "Casual",
    "Educational", "Music", "Puzzle", "Racing", "Role Playing", "Simulation",
    "Sports", "Strategy", "Trivia", "Word"
)
private val allCategories = appCategories + gameCategories

class MainActivity : ComponentActivity() {
    private val repository = StoreRepository()
    private lateinit var authRepository: AuthRepository
    private var account by mutableStateOf(AccountState())
    private var apps by mutableStateOf<List<StoreApp>>(emptyList())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var selectedApp by mutableStateOf<StoreApp?>(null)
    private var selectedScreenshotUrl by mutableStateOf<String?>(null)
    private var search by mutableStateOf("")
    private var selectedTab by mutableStateOf("Apps")
    private var selectedCategory by mutableStateOf("All")
    private var downloadProgress by mutableStateOf<Map<String, Int>>(emptyMap())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authRepository = AuthRepository(this)
        account = authRepository.load()
        loadApps()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Emerald, secondary = Gold, background = DeepBlack,
                surface = DeepGreen, onPrimary = DeepBlack,
                onBackground = Color.White, onSurface = Color.White
            )) {
                Surface(Modifier.fillMaxSize(), color = DeepBlack) {
                    when {
                        selectedScreenshotUrl != null -> ScreenshotViewer(
                            imageUrl = selectedScreenshotUrl!!,
                            onClose = { selectedScreenshotUrl = null }
                        )
                        selectedApp == null -> StoreShell(
                            apps = apps, loading = loading, error = error,
                            search = search, onSearch = { search = it },
                            selectedTab = selectedTab, onTab = {
                                selectedTab = it
                                if (it != "Search") { search = ""; selectedCategory = "All" }
                            },
                            selectedCategory = selectedCategory,
                            onCategory = { selectedCategory = it },
                            onRefresh = { loadApps() },
                            onSelect = { selectedApp = it },
                            onScreenshot = { selectedScreenshotUrl = it },
                            account = account,
                            onAuthenticate = { email, password, name, signup -> lifecycleScope.launch { try { account = authRepository.authenticate(email, password, name, signup) } catch (e: Exception) { Toast.makeText(this@MainActivity, e.message ?: "Sign in failed", Toast.LENGTH_LONG).show() } } },
                            onSignOut = { account = authRepository.signOut() },
                            onAvatar = { account = authRepository.saveAvatar(it) }
                        )
                        selectedApp != null -> {
                        AppDetails(
                            app = selectedApp!!, progress = downloadProgress[selectedApp!!.id],
                            onBack = { selectedApp = null },
                            onInstall = { startDownload(selectedApp!!) },
                            onScreenshot = { selectedScreenshotUrl = it }
                        )
                        }
                    }
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(settingsIntent)
            Toast.makeText(
                this,
                "Allow MS App Store to install apps, then tap Install again.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
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
private fun StoreShell(
    apps: List<StoreApp>, loading: Boolean, error: String?,
    search: String, onSearch: (String) -> Unit,
    selectedTab: String, onTab: (String) -> Unit,
    selectedCategory: String, onCategory: (String) -> Unit,
    onRefresh: () -> Unit, onSelect: (StoreApp) -> Unit,
    onScreenshot: (String) -> Unit,
    account: AccountState, onAuthenticate: (String, String, String, Boolean) -> Unit,
    onSignOut: () -> Unit, onAvatar: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(DeepBlack)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when (selectedTab) {
            "Search" -> SearchPage(
                apps, loading, error, search, onSearch, selectedCategory, onCategory,
                onRefresh, onSelect, onScreenshot
            )
            "You" -> ProfilePage(account, onAuthenticate, onSignOut, onAvatar)
            else -> HomePage(
                apps, loading, error, selectedTab, onRefresh, onSelect, onScreenshot, account
            )
        }
        }
        BottomNavigation(selectedTab, onTab)
    }
}

@Composable
private fun HomePage(
    apps: List<StoreApp>, loading: Boolean, error: String?,
    tab: String, onRefresh: () -> Unit, onSelect: (StoreApp) -> Unit,
    onScreenshot: (String) -> Unit, account: AccountState
) {
    var showNotifications by remember { mutableStateOf(false) }
    val gamesOnly = tab == "Games"
    val shown = apps.filter { (it.appType.equals("game", true)) == gamesOnly }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("MS App Store", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (gamesOnly) "Discover games" else "Discover apps", color = Muted, fontSize = 12.sp)
            }
            if (account.avatarUri.isNotBlank()) {
                AsyncImage(account.avatarUri, contentDescription = "Profile photo", modifier = Modifier.size(42.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(42.dp).clip(CircleShape).background(DeepGreen), contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = "Profile", tint = Gold) }
            }
            IconButton(onClick = { showNotifications = true }) {
                Icon(Icons.Default.Notifications, contentDescription = "Notifications", tint = Gold)
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryPill("Top charts", false) { }
            CategoryPill("Categories", false) { }
        }
        StoreContent(shown, loading, error, onRefresh, onSelect, onScreenshot)
        if (showNotifications) AlertDialog(onDismissRequest = { showNotifications = false }, title = { Text("Notifications") }, text = { Text("You are all caught up. New store updates will appear here.") }, confirmButton = { TextButton(onClick = { showNotifications = false }) { Text("OK") } })
    }
}

@Composable
private fun SearchPage(
    apps: List<StoreApp>, loading: Boolean, error: String?,
    search: String, onSearch: (String) -> Unit,
    selectedCategory: String, onCategory: (String) -> Unit,
    onRefresh: () -> Unit, onSelect: (StoreApp) -> Unit,
    onScreenshot: (String) -> Unit
) {
    if (selectedCategory != "All") {
        val categoryApps = apps.filter { it.category.equals(selectedCategory, true) }
        Column(Modifier.fillMaxSize().background(DeepBlack)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onCategory("All") }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back to all categories", tint = Gold)
                }
                Column(Modifier.weight(1f)) {
                    Text(selectedCategory, color = Gold, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${categoryApps.size} apps and games", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Notifications, contentDescription = "Refresh", tint = Gold)
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Emerald)
                }
                error != null -> Text(error, color = Muted, modifier = Modifier.padding(18.dp))
                categoryApps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No apps available in $selectedCategory yet.", color = Muted)
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(categoryApps, key = { "category-${it.id}" }) { app -> AppRow(app, onSelect, onScreenshot) }
                }
            }
        }
    } else {
        val matchingApps = apps.filter { app ->
            search.isBlank() || listOf(app.name, app.developerName, app.category, app.description)
                .any { it.contains(search, true) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Search", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Notifications, contentDescription = "Refresh", tint = Gold)
                    }
                }
                OutlinedTextField(
                    value = search, onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    placeholder = { Text("Search apps & games") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true, shape = RoundedCornerShape(24.dp)
                )
                Text(
                    "All Categories", color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 22.dp, bottom = 10.dp)
                )
                CategoryPill("All", true) { onCategory("All") }
                Spacer(Modifier.height(8.dp))
            }
            items(allCategories.chunked(2)) { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pair.forEach { category ->
                        Box(Modifier.weight(1f)) {
                            CategoryTile(category, false) { onCategory(category) }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Text("Search Results", color = Gold, fontSize = 17.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 20.dp, bottom = 8.dp))
                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Emerald)
                    }
                } else if (error != null) {
                    Text(error, color = Muted, modifier = Modifier.padding(18.dp))
                } else if (matchingApps.isEmpty()) {
                    Text("No apps found.", color = Muted, modifier = Modifier.padding(18.dp))
                }
            }
            items(matchingApps, key = { "search-${it.id}" }) { app -> AppRow(app, onSelect, onScreenshot) }
        }
    }
}

@Composable
private fun CategoryTile(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Emerald.copy(alpha = 0.20f) else DeepGreen,
        shape = RoundedCornerShape(13.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (selected) Emerald else Color(0xFF234334)
        )
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(name, color = if (selected) Gold else Color.White, fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 2, modifier = Modifier.weight(1f))
            Text("›", color = Emerald, fontSize = 20.sp)
        }
    }
}

@Composable
private fun CategoryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Emerald else DeepGreen,
        shape = CircleShape,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(label, color = if (selected) DeepBlack else Color.White,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StoreContent(
    apps: List<StoreApp>, loading: Boolean, error: String?,
    onRefresh: () -> Unit, onSelect: (StoreApp) -> Unit,
    onScreenshot: (String) -> Unit
) {
    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Emerald)
        }
        error != null -> Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Store could not load", color = Color.White, fontWeight = FontWeight.Bold)
            Text(error, color = Muted, modifier = Modifier.padding(vertical = 10.dp))
            Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Emerald)) {
                Text("Retry", color = DeepBlack)
            }
        }
        apps.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No published apps found.", color = Muted)
        }
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(apps, key = { it.id }) { app -> AppRow(app, onSelect, onScreenshot) }
        }
    }
}

@Composable
private fun AppRow(app: StoreApp, onSelect: (StoreApp) -> Unit, onScreenshot: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(DeepGreen)
            .clickable { onSelect(app) }.padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!app.iconUrl.isNullOrBlank()) {
                AsyncImage(model = app.iconUrl, contentDescription = app.name,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)),
                    contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF173B2B)),
                    contentAlignment = Alignment.Center) {
                    Text("MS", color = Gold, fontWeight = FontWeight.ExtraBold)
                }
            }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text(app.name, color = Color.White, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.developerName, color = Muted, fontSize = 12.sp)
                Text("${app.category} · v${app.version}", color = Gold, fontSize = 11.sp)
            }
            Icon(Icons.Default.Download, contentDescription = "Open app", tint = Emerald)
        }
        if (app.screenshotUrls.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(app.screenshotUrls) { screenshot ->
                    AsyncImage(model = screenshot, contentDescription = "${app.name} screenshot",
                        modifier = Modifier.width(174.dp).height(104.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onScreenshot(screenshot) },
                        contentScale = ContentScale.Crop)
                }
            }
        }
    }
}

@Composable
private fun ProfilePage(account: AccountState, onAuthenticate: (String, String, String, Boolean) -> Unit, onSignOut: () -> Unit, onAvatar: (String) -> Unit) {
    var signup by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf(account.email) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(account.displayName) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            onAvatar(uri.toString())
        }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        if (account.avatarUri.isNotBlank()) AsyncImage(account.avatarUri, contentDescription = "Profile photo", modifier = Modifier.size(92.dp).clip(CircleShape).clickable { picker.launch(arrayOf("image/*")) }, contentScale = ContentScale.Crop)
        else Box(Modifier.size(92.dp).clip(CircleShape).background(DeepGreen).clickable { picker.launch(arrayOf("image/*")) }, contentAlignment = Alignment.Center) { Icon(Icons.Default.Person, contentDescription = "Choose photo", tint = Gold, modifier = Modifier.size(48.dp)) }
        Text(if (account.signedIn) account.displayName.ifBlank { "Your Profile" } else "Your Profile", color = Gold, fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
        if (account.signedIn) {
            Text(account.email, color = Muted, modifier = Modifier.padding(8.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Change profile photo") }
            Button(onClick = onSignOut, colors = ButtonDefaults.buttonColors(containerColor = Emerald)) { Text("Sign out", color = DeepBlack) }
        } else {
            Text(if (signup) "Create your account" else "Sign in to MS App Store", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            if (signup) OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onAuthenticate(email, password, name, signup) }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), enabled = email.contains("@") && password.length >= 6, colors = ButtonDefaults.buttonColors(containerColor = Emerald)) { Text(if (signup) "Create account" else "Sign in", color = DeepBlack) }
            TextButton(onClick = { signup = !signup }) { Text(if (signup) "Already have an account? Sign in" else "New here? Create an account", color = Gold) }
        }
    }
}

@Composable
private fun BottomNavigation(selected: String, onTab: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DeepGreen).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem("Games", selected == "Games", { Icon(Icons.Default.SportsEsports, null) }) { onTab("Games") }
        NavItem("Apps", selected == "Apps", { Icon(Icons.Default.Apps, null) }) { onTab("Apps") }
        NavItem("Search", selected == "Search", { Icon(Icons.Default.Search, null) }) { onTab("Search") }
        NavItem("You", selected == "You", { Icon(Icons.Default.Person, null) }) { onTab("You") }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)
            .background(if (selected) Emerald.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) Gold else Muted) { icon() }
        Text(label, color = if (selected) Gold else Muted, fontSize = 11.sp)
    }
}

@Composable
private fun UtilityPage(
    page: String, versionName: String, versionCode: Int, account: AccountState,
    onBack: () -> Unit, onNavigate: (String) -> Unit,
    onOpenProfile: () -> Unit, onOpenDeveloper: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(DeepBlack)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text(page, color = Gold, fontWeight = FontWeight.Bold, fontSize = 21.sp)
        }
        when (page) {
            "Settings" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("MS App Store settings", color = Muted, fontSize = 13.sp)
                SettingsRow("Notifications", "Store updates and alerts") { onNavigate("Notifications") }
                SettingsRow("Profile", "View your account and profile details", onOpenProfile)
                SettingsRow("Developer Account", "Create or apply for a developer account", onOpenDeveloper)
                SettingsRow("App Policy", "Read the store rules and publishing policy") { onNavigate("App Policy") }
                SettingsRow("App About", "Version and application information") { onNavigate("App About") }
            }
            "Notifications" -> Column(Modifier.padding(20.dp)) {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = Gold, modifier = Modifier.size(42.dp))
                Text("Notifications", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("You are all caught up. Store announcements and account updates will appear here when available.", color = Muted, lineHeight = 22.sp)
            }
            "App Policy" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("MS App Store Policy", color = Gold, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Effective date: 13 November 2026", color = Muted, fontSize = 12.sp)
                PolicySection("1. Purpose", "MS App Store provides a platform to discover and download Android applications and games. Apps are published only after store review and approval.")
                PolicySection("2. Developer responsibility", "Developers must provide accurate app names, descriptions, version details, package names, icons, screenshots and contact information. Developers are responsible for maintaining their apps.")
                PolicySection("3. Security and harmful content", "Apps must not contain malware, spyware, hidden data collection, deceptive behavior, unauthorized access, or features intended to damage devices or users. Unsafe apps may be rejected or removed.")
                PolicySection("4. Legal content and intellectual property", "Developers may upload only content they own or are authorized to distribute. Impersonation, copyright infringement, stolen apps and illegal content are prohibited.")
                PolicySection("5. Review and publishing", "Submitting an app does not guarantee publication. MS App Store may review, reject, suspend or remove apps that violate this policy, applicable law or user safety.")
                PolicySection("6. User downloads", "Users should review app details and permissions before installing. Download availability is not a guarantee that an app is endorsed, secure or free from defects.")
                PolicySection("7. Privacy", "Developers must explain their app's data practices and obtain any consent required by applicable law. Do not submit another person's personal or confidential information without authorization.")
                PolicySection("8. Updates and availability", "App information, availability and versions may change. Developers should promptly provide security fixes and accurate update information.")
                PolicySection("9. Reporting and enforcement", "Users may report suspicious, unsafe, misleading or infringing apps to the store operator. MS App Store may investigate and remove violating apps.")
                PolicySection("10. Policy changes", "This policy may be updated to improve safety, clarity and compliance. The current version displayed in the app is the applicable store policy, subject to local law.")
                Text("Contact: Use the contact details published on the official MS App Store website for policy or safety reports.", color = Muted, fontSize = 12.sp)
            }
            "App About" -> Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("MS App Store", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("Android app marketplace", color = Muted)
                Spacer(Modifier.height(18.dp))
                AboutRow("Version Name", versionName)
                AboutRow("Version Code", versionCode.toString())
                AboutRow("Application ID", "com.msappstore.android")
                AboutRow("Platform", "Android")
                AboutRow("Minimum Android", "Android 8.0 (API 26)")
                AboutRow("Publisher", "MS App Store")
                Spacer(Modifier.height(12.dp))
                Text("Discover apps and games, view app details and screenshots, and download published APKs from MS App Store.", color = Muted, lineHeight = 22.sp)
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(DeepGreen).clickable(onClick = onClick).padding(16.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PolicySection(title: String, body: String) {
    Text(title, color = Gold, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    Text(body, color = Muted, lineHeight = 20.sp)
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(color = DeepGreen)
}

@Composable
private fun ScreenshotViewer(imageUrl: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Full screen app screenshot",
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
                contentScale = ContentScale.Fit
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .background(DeepGreen.copy(alpha = 0.85f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close screenshot", tint = Color.White)
            }
        }
    }
}

@Composable
private fun AppDetails(
    app: StoreApp, progress: Int?, onBack: () -> Unit,
    onInstall: () -> Unit, onScreenshot: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().background(DeepBlack)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text("App details", color = Gold, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!app.iconUrl.isNullOrBlank()) AsyncImage(model = app.iconUrl, contentDescription = app.name,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)),
                    contentScale = ContentScale.Crop)
                else Box(Modifier.size(88.dp).clip(RoundedCornerShape(20.dp)).background(DeepGreen),
                    contentAlignment = Alignment.Center) {
                    Text("MS", color = Gold, fontWeight = FontWeight.ExtraBold)
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(app.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    Text(app.developerName, color = Muted)
                    Text("v${app.version} · ${app.category}", color = Gold, fontSize = 12.sp)
                }
            }
            if (app.screenshotUrls.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Screenshots", color = Gold, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(app.screenshotUrls) { screenshot ->
                        AsyncImage(model = screenshot, contentDescription = "${app.name} screenshot",
                            modifier = Modifier.width(240.dp).height(144.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onScreenshot(screenshot) },
                            contentScale = ContentScale.Crop)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (progress == null || progress >= 100) {
                Button(onClick = onInstall, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Emerald)) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = DeepBlack)
                    Text("Install", color = DeepBlack, modifier = Modifier.padding(start = 8.dp),
                        fontWeight = FontWeight.Bold)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { progress / 100f },
                            modifier = Modifier.size(54.dp), color = DownloadBlue,
                            trackColor = DeepGreen, strokeWidth = 5.dp)
                        Text("$progress%", color = Color.White, fontSize = 11.sp)
                    }
                    Text("Downloading… $progress%", color = DownloadBlue,
                        modifier = Modifier.padding(start = 14.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = DeepGreen)
            Text("About this app", color = Gold, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Text(app.description.ifBlank { "No description available." }, color = Muted, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            Text("Package: ${app.packageName.ifBlank { "Not provided" }}", color = Muted, fontSize = 12.sp)
        }
    }
}
