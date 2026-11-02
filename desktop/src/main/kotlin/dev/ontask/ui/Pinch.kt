package dev.ontask.ui

/**
 * Trackpad pinch on macOS. Compose doesn't deliver pinch gestures, so this listens to Apple's own gesture events on
 * the window (com.apple.eawt.event, opened with --add-exports in the build) and hands each pinch to whatever is under
 * the pointer and asked for it, such as the timeline. Reflection keeps it optional: anywhere it isn't available
 * (Gradle run without the flag, another OS), nothing happens.
 */
object Pinch {
    /** Set while the pointer is over something zoomable: gets the zoom factor (< 1 = zoom in). */
    @Volatile var target: ((Double) -> Unit)? = null

    fun install(component: javax.swing.JComponent) = runCatching {
        val listener = Class.forName("com.apple.eawt.event.MagnificationListener")
        val utils = Class.forName("com.apple.eawt.event.GestureUtilities")
        val event = Class.forName("com.apple.eawt.event.MagnificationEvent")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { _, method, args ->
            if (method.name == "magnify") {
                val m = event.getMethod("getMagnification").invoke(args!![0]) as Double
                target?.let { t -> javax.swing.SwingUtilities.invokeLater { t(1.0 / (1.0 + m)) } }
            }
            null
        }
        utils.getMethod("addGestureListenerTo", javax.swing.JComponent::class.java, Class.forName("com.apple.eawt.event.GestureListener")).invoke(null, component, proxy)
    }
}
