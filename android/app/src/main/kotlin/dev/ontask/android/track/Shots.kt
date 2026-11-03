package dev.ontask.android.track

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import java.io.File
import java.security.MessageDigest

/**
 * Screenshots for "Help sort these": one per site, app, page or video that's been unsorted for 15 s, taken through
 * the Accessibility service. They stay on this phone (never uploaded) until it's sorted, 7 days at most.
 */
object Shots {
    fun siteKey(subject: String) = "site:" + if ('.' in subject && ' ' !in subject) dev.ontask.classify.siteDomain(subject) else subject
    fun itemKey(item: String) = "item:$item"

    private fun dir(ctx: Context) = File(ctx.filesDir, "shots")
    fun file(ctx: Context, key: String) = File(dir(ctx), MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg")
    private val taking = HashSet<String>()

    fun take(svc: AccessibilityService, key: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val f = file(svc, key)
        if (f.exists() || !synchronized(taking) { taking.add(key) }) return
        runCatching {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace) ?: return@runCatching
                        val scale = 1000f / maxOf(hw.width, hw.height)
                        val small = Bitmap.createScaledBitmap(hw.copy(Bitmap.Config.ARGB_8888, false), (hw.width * scale).toInt(), (hw.height * scale).toInt(), true)
                        dir(svc).mkdirs()
                        f.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
                        r.hardwareBuffer.close()
                    }
                    synchronized(taking) { taking.remove(key) }
                }
                override fun onFailure(code: Int) { synchronized(taking) { taking.remove(key) } }
            })
        }.onFailure { synchronized(taking) { taking.remove(key) } }
    }

    fun delete(ctx: Context, key: String) { runCatching { file(ctx, key).delete() } }

    /** Screenshots older than 7 days go, sorted or not. */
    fun prune(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 7 * 86_400_000L
        dir(ctx).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}
