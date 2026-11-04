package dev.ontask.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dev.ontask.android.ui.HomeScreen
import dev.ontask.android.ui.Muted
import dev.ontask.android.ui.OnTaskTheme
import dev.ontask.android.ui.PeopleScreen
import dev.ontask.android.ui.PersonScreen
import dev.ontask.android.ui.SettingsScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private var notifGranted by mutableStateOf(false)
    /** A "Which category is this?" question opened from a notification: key, label, is it a page or video. */
    private var ask by mutableStateOf<Triple<String, String, Boolean>?>(null)

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); takeAsk(intent) }
    /** A newer version, found when the app is opened: asked about once per opening ("Later" waits for the next). */
    private var update by mutableStateOf<Updater.Release?>(null)
    private var updateAsked = false

    /** A check-in opened from a notification (PLANNING, MIDDAY, EVENING). */
    private var checkin by mutableStateOf<String?>(null)
    /** "Help sort these" after a check-in: asked for ([wantSort]), then shown with what's waiting. */
    private var wantSort by mutableStateOf(false)
    private var sorting by mutableStateOf<List<dev.ontask.android.ui.SortRow>?>(null)

    private fun takeAsk(i: android.content.Intent?) {
        i?.getStringExtra(CHECKIN)?.let { checkin = it }
        if (i?.getStringExtra("type") == "evening_checkin_phone") checkin = "EVENING"
        val key = i?.getStringExtra(ASK_KEY) ?: return
        ask = Triple(key, i.getStringExtra(ASK_LABEL) ?: key, i.getBooleanExtra(ASK_ITEM, false))
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        takeAsk(intent)
        setContent { OnTaskTheme { Root() } }
        // Updates used to be a notification; clear one left over from an older version.
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).cancel(7005) }
    }

    override fun onStart() {
        super.onStart()
        if (updateAsked) return
        updateAsked = true
        lifecycleScope.launch { Updater.check(this@MainActivity)?.let { update = it } }
    }

    override fun onStop() { super.onStop(); if (update == null) updateAsked = false }

    companion object {
        const val ASK_KEY = "ask.key"; const val ASK_LABEL = "ask.label"; const val ASK_ITEM = "ask.item"
        const val CHECKIN = "checkin"
    }

    @Composable
    private fun Root() {
        var user by remember { mutableStateOf(Cloud.auth.currentUser) }
        DisposableEffect(Unit) {
            val l = FirebaseAuth.AuthStateListener { user = it.currentUser }
            Cloud.auth.addAuthStateListener(l); onDispose { Cloud.auth.removeAuthStateListener(l) }
        }
        val u = user
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (u == null) Login() else Signed(u)
        }
        ask?.let { (key, label, item) -> AskDialog(key, label, item) { ask = null } }
        if (wantSort) LaunchedEffect(Unit) { wantSort = false; dev.ontask.android.ui.sortRows().takeIf { it.isNotEmpty() }?.let { sorting = it } }
        update?.let { r -> UpdateDialog(r) { update = null } }
    }

    /** "Version 0.1.106 is available": Install now, or Later (asked again the next time the app is opened). */
    @Composable
    private fun UpdateDialog(r: Updater.Release, onDone: () -> Unit) {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf<String?>(null) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDone,
            title = { Text("OnTask update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Version ${r.name} is available" + (r.notes?.let { ": $it" } ?: "."))
                    if (!Updater.canInstall(this@MainActivity)) Muted("The first time, allow OnTask to install apps, then come back and tap Install.")
                    status?.let { Muted(it) }
                }
            },
            confirmButton = {
                androidx.compose.material3.Button({
                    if (!Updater.canInstall(this@MainActivity)) Updater.askPermission(this@MainActivity)
                    else scope.launch {
                        status = "Downloading…"
                        val err = Updater.install(this@MainActivity)
                        if (err == null) onDone() else status = "Couldn't install: $err"
                    }
                }) { Text("Install") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onDone) { Text("Later") } },
        )
    }

    /** "Which category is this?" for something the phone couldn't sort: the answer is saved for every device. */
    @Composable
    private fun AskDialog(key: String, label: String, item: Boolean, onDone: () -> Unit) {
        val cats = remember { dev.ontask.Category.assignable.filter { !item || !it.variable } }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDone,
            title = { Text(if (item) "Is this work? What is it?" else "Which category is this?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    cats.forEach { c ->
                        androidx.compose.material3.OutlinedButton({
                            val t = dev.ontask.android.track.Tracker.running
                            if (t != null) t.file(key, c, null, dev.ontask.Source.USER_LABEL)
                            else if (item) dev.ontask.android.track.PhoneRules.saveVerdict(Cloud.deviceId(this@MainActivity), key, null, c.name, dev.ontask.Source.USER_LABEL, null)
                            else dev.ontask.android.track.PhoneRules.saveRule(dev.ontask.classify.RuleKind.valueOf(key.substringBefore('|')), key.substringAfter('|'), c.name)
                            onDone()
                        }, Modifier.fillMaxWidth()) { Text(c.label) }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onDone) { Text("Not now") } },
        )
    }

    @Composable
    private fun Login() {
        val scope = rememberCoroutineScope()
        var err by remember { mutableStateOf<String?>(null) }
        Column(Modifier.fillMaxSize().padding(24.dp).padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("OnTask", style = MaterialTheme.typography.headlineMedium)
            Muted("Stay on task together. Sign in with the same Google account as on your Mac.")
            Button({
                scope.launch {
                    err = runCatching {
                        val option = GetSignInWithGoogleOption.Builder(getString(R.string.default_web_client_id)).build()
                        val cred = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity, GetCredentialRequest(listOf(option))).credential
                        val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                        Cloud.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    }.exceptionOrNull()?.message
                }
            }) { Text("Continue with Google") }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    @Composable
    private fun Signed(u: FirebaseUser) {
        LaunchedEffect(u.uid) {
            runCatching { Cloud.ensureUser(this@MainActivity, u) }
            dev.ontask.android.track.PhoneRules.start(u.uid)
            if (!notifGranted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        var tab by remember { mutableStateOf(0) }
        var person by remember { mutableStateOf<String?>(null) }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf("Home" to "⌂", "People" to "👥", "Settings" to "⚙").forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(tab == i, { tab = i; person = null }, icon = { Text(icon) }, label = { Text(label) },
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer))
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                when {
                    checkin != null -> {
                        val kind = checkin!!
                        // Done with the midday or evening check-in: then "Help sort these", if anything is waiting.
                        dev.ontask.android.ui.CheckinScreen(kind, onSaved = { if (kind == "MIDDAY" || kind == "EVENING") wantSort = true }) { checkin = null }
                    }
                    sorting != null -> dev.ontask.android.ui.SortScreen(sorting!!) { sorting = null }
                    person != null -> PersonScreen(u.uid, person!!) { person = null }
                    tab == 0 -> HomeScreen(u.uid, u.displayName ?: "")
                    tab == 1 -> PeopleScreen(u.uid) { person = it }
                    else -> SettingsScreen(u.uid, notifGranted, { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Cloud.auth.signOut() }
                }
            }
        }
    }
}
