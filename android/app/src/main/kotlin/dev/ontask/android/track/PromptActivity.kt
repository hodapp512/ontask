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
        countdown = intent.getStringExtra("countdown") != null
        setContent {
            OnTaskTheme {
                when {
                    countdown -> Countdown(intent.getStringExtra("trigger").orEmpty(), intent.getStringExtra("message").orEmpty(), intent.getIntExtra("seconds", 10), intent.getLongExtra("driftSec", 600))
                    bedtime != null -> Bedtime(bedtime)
                    else -> Screen(step)
                }
            }
        }
    }

    private var countdown = false

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); setIntent(intent); recreate() }

    override fun onDestroy() { if (current?.get() === this) current = null; super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (!countdown) Tracker.running?.prompts?.screenClosed(); finish() }
    /** Going home (or to another app) with the screen still up counts like closing it. */
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (!countdown && !isFinishing) Tracker.running?.prompts?.screenClosed() }

    /**
     * "Sending a message to your partner in 10…": why, and the message word for word. Closing it or "I'm back to
     * work" only closes the screen (still on time-wasting when the time's up sends it); "I'm actually on task" files
     * what you pick as work and sends nothing.
     */
    @androidx.compose.runtime.Composable
    private fun Countdown(trigger: String, message: String, seconds: Int, driftSec: Long) {
        val p = Tracker.running?.prompts
        var left by remember { mutableStateOf(seconds) }
        var onTask by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- }; if (!onTask) { delay(1500); finish() } }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(24.dp).padding(top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (left > 0) "Sending a message to your partner in $left…" else "Sending…", style = MaterialTheme.typography.headlineSmall)
            Text(trigger, style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp))
            }
            Muted("Back at work when the time is up, nothing goes, unless you're back on time-wasting in the next 10 minutes.")
            if (dev.ontask.android.ui.rememberPlan().isNotEmpty()) { Text("Today's plan", style = MaterialTheme.typography.titleSmall); dev.ontask.android.ui.PlanList() }
            if (!onTask) {
                Button({ p?.backToWorkFromCountdown(); finish() }, Modifier.fillMaxWidth()) { Text("I'm back to work") }
                OutlinedButton({ onTask = true }, Modifier.fillMaxWidth()) { Text("I'm actually on task") }
                // Lunchtime (11–2, once a day): starts lunch, and nothing is sent.
                if (p?.lunchOffered() == true) OutlinedButton({ p.startBreak(lunch = true); finish() }, Modifier.fillMaxWidth()) { Text("I'm at lunch") }
            } else {
                val items = remember { Tracker.running?.driftItems(minOf(3600L, driftSec + 900)).orEmpty() }
                var picked by remember { mutableStateOf(items.firstOrNull()) }
                var whole by remember { mutableStateOf(false) }
                Text("What were you working on?", style = MaterialTheme.typography.titleMedium)
                if (items.isEmpty()) Muted("Nothing time-wasting recorded in this drift.")
                items.forEach { d ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(picked == d, { picked = d; whole = false })
                        Text(d.label, Modifier.weight(1f)); Text(fmtDuration(d.seconds), style = MaterialTheme.typography.bodySmall)
                    }
                }
                picked?.takeIf { it.pageKey != null }?.let { d ->
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(!whole, { whole = false }); Text("Just this one")
                        androidx.compose.material3.RadioButton(whole, { whole = true }); Text("All of ${d.siteKey.substringAfter('|')}")
                    }
                }
                Button({
                    val d = picked
                    if (d != null) p?.countdownOnTask(if (d.pageKey != null && !whole) d.pageKey else d.siteKey, d.title) else p?.working()
                    finish()
                }, Modifier.fillMaxWidth()) { Text("It's work — don't send") }
            }
        }
    }

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
        val uid = Cloud.uid
        val checkin by remember { Cloud.doc("users/$uid/checkins/${dayKey()}") }.collectAsState(null)
        val inWork = step == 3
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(24.dp).padding(top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Time to come back", style = MaterialTheme.typography.headlineMedium)
            Muted("You've been off track on your phone for a while.")
            p?.state?.let { s -> if (s["breaksTodaySec"].num() >= 60) Muted("You've had ${fmtDuration(s["breaksHourSec"].num())} of breaks in the last hour (${fmtDuration(s["breaksTodaySec"].num())} today).") }
            if (dev.ontask.android.ui.rememberPlan().isNotEmpty()) { Text("Today's plan", style = MaterialTheme.typography.titleSmall); dev.ontask.android.ui.PlanList() }
            Button({ p?.backToWork(); finish() }, Modifier.fillMaxWidth()) { Text("Back to work") }
            OutlinedButton({ p?.working(); finish() }, Modifier.fillMaxWidth()) { Text("It's actually work") }
            if (p?.lunchOffered() == true) OutlinedButton({ p.startBreak(lunch = true); finish() }, Modifier.fillMaxWidth()) { Text("I'm at lunch") }
            // What used to be behind "Help me get started", shown right away.
            run {
                Muted("Pick the smallest next step from your plan and give it 10 minutes. Put the phone face down.")
                if (inWork) {
                    var text by remember { mutableStateOf("I've been drifting on my phone. I'm getting back to work.\nNo need to reply — but a 👍 helps.") }
                    var sent by remember { mutableStateOf(false) }
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3)
                    OutlinedButton({ p?.messagePartner(text); sent = true }, enabled = !sent) { Text(if (sent) "Sent ✓" else "Message my partner") }
                    if (step == 3) Muted("It's your call. You can edit the message.")
                }
            }
        }
    }

    companion object {
        private var current: WeakReference<PromptActivity>? = null
        fun closeAll() { current?.get()?.finish() }
    }
}
