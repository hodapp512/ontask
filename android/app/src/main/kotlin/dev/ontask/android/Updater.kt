package dev.ontask.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates without a cable: new builds are published with the web app (ontask-ed2df.web.app/android/), and this
 * checks android/version.json when the app is opened (no notification), offers the update, downloads the APK and hands it to Android's installer (which asks
 * you to confirm). The first time, Android asks you to allow OnTask to install apps.
 */
object Updater {
    private const val BASE = "https://ontask-ed2df.web.app/android/"
    data class Release(val code: Long, val name: String, val notes: String?)

    fun installed(ctx: Context): Pair<Long, String> = ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { it.longVersionCode to (it.versionName ?: "?") }

    /** The newer release, if there is one. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL(BASE + "version.json?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            Release(j.getLong("versionCode"), j.getString("versionName"), j.optString("notes").ifBlank { null })
        }.getOrNull()?.takeIf { it.code > installed(ctx).first }
    }

    /** Whether Android lets OnTask install updates; if not, [askPermission] opens the switch for it. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()
    fun askPermission(ctx: Context) = ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Downloads the APK and starts the install; Android shows its own confirmation. */
    suspend fun install(ctx: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val pi = ctx.packageManager.packageInstaller
            val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) })
            pi.openSession(id).use { s ->
                val c = URL(BASE + "ontask.apk?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.inputStream.use { input -> s.openWrite("ontask.apk", 0, -1).use { out -> input.copyTo(out); s.fsync(out) } }
                val done = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResult::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(done.intentSender)
            }
            null
        }.exceptionOrNull()?.message
    }
}

/** The installer's answer: it needs your confirmation (shown here), or it's done / failed. */
class InstallResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
