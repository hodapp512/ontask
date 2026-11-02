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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    private var notifGranted by mutableStateOf(false)
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        notifGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        setContent { OnTaskTheme { Root() } }
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
            if (!notifGranted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        var tab by remember { mutableStateOf(0) }
        var person by remember { mutableStateOf<String?>(null) }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar {
                    listOf("Home" to "⌂", "People" to "👥", "Settings" to "⚙").forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(tab == i, { tab = i; person = null }, icon = { Text(icon) }, label = { Text(label) })
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
                when {
                    person != null -> PersonScreen(u.uid, person!!) { person = null }
                    tab == 0 -> HomeScreen(u.uid, u.displayName ?: "")
                    tab == 1 -> PeopleScreen(u.uid) { person = it }
                    else -> SettingsScreen(u.uid, notifGranted, { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Cloud.auth.signOut() }
                }
            }
        }
    }
}
