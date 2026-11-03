package dev.ontask.android.track

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ontask.android.Cloud
import dev.ontask.android.dayKey
import dev.ontask.android.fmtDuration
import dev.ontask.android.num
import dev.ontask.android.ui.Muted
import dev.ontask.android.ui.OnTaskTheme
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/**
 * Steps 2 and 3 on the phone: a full-screen OnTask screen over what you were on, answerable at once. Back closes it,
 * and it returns after 30 seconds while you're still drifting.
 */
class PromptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        val step = intent.getIntExtra("step", 2)
        val bedtime = intent.getStringExtra("bedtime")
        setContent { OnTaskTheme { if (bedtime != null) Bedtime(bedtime) else Screen(step) } }
    }

    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { Tracker.running?.prompts?.screenClosed(); finish() }

    /** The third and later bedtime reminder: full screen (answerable at once). */
    @androidx.compose.runtime.Composable
    private fun Bedtime(text: String) {
        val p = Tracker.running?.prompts
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp).padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text.substringBefore('\n'), style = MaterialTheme.typography.headlineMedium)
            Text(text.substringAfter('\n'), style = MaterialTheme.typography.bodyLarge)
            Button({ p?.bedtimeAnswer(bed = true); finish() }, Modifier.fillMaxWidth()) { Text("Going to bed") }
            OutlinedButton({ p?.bedtimeAnswer(bed = false); finish() }, Modifier.fillMaxWidth()) { Text("10 more minutes") }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Screen(step: Int) {
        val p = Tracker.running?.prompts
        var help by remember { mutableStateOf(false) }
        val uid = Cloud.uid
        val checkin by remember { Cloud.doc("users/$uid/checkins/${dayKey()}") }.collectAsState(null)
        val inWork = step == 3
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(24.dp).padding(top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Time to come back", style = MaterialTheme.typography.headlineMedium)
            Muted("You've been off track on your phone for a while.")
            p?.state?.let { s -> if (s["breaksTodaySec"].num() >= 60) Muted("You've had ${fmtDuration(s["breaksHourSec"].num())} of breaks in the last hour (${fmtDuration(s["breaksTodaySec"].num())} today).") }
            checkin?.getString("plan")?.takeIf { it.isNotBlank() }?.let { Text("Today's plan", style = MaterialTheme.typography.titleSmall); Text(it) }
            Button({ p?.backToWork(); finish() }, Modifier.fillMaxWidth()) { Text("Back to work") }
            OutlinedButton({ p?.working(); finish() }, Modifier.fillMaxWidth()) { Text("It's actually work") }
            if (!help) TextButton({ help = true }) { Text("Help me get started") }
            else {
                Muted("Pick the smallest next step from your plan and give it 10 minutes. Put the phone face down.")
                if (inWork) {
                    var text by remember { mutableStateOf("I've been drifting on my phone. I'm getting back to work.\nNo need to reply — but a 👍 helps.") }
                    var sent by remember { mutableStateOf(false) }
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedButton({ p?.messagePartner(text); sent = true }, enabled = !sent) { Text(if (sent) "Sent ✓" else "Message my partner") }
                }
            }
        }
    }

    companion object {
        private var current: WeakReference<PromptActivity>? = null
        fun closeAll() { current?.get()?.finish() }
    }
}
