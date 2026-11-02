package dev.ontask.android

import android.content.Context
import android.os.Build
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.functions
import com.google.firebase.messaging.messaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Firebase access shared by the screens: the signed-in user, live documents and queries, callables, push. */
object Cloud {
    val auth = Firebase.auth
    val db = Firebase.firestore
    val fns = Firebase.functions
    private var appContext: Context? = null

    val uid: String? get() = auth.currentUser?.uid

    /** A live document (null while missing). */
    fun doc(path: String): Flow<DocumentSnapshot?> = callbackFlow {
        val reg = db.document(path).addSnapshotListener { s, _ -> trySend(s?.takeIf { it.exists() }) }
        awaitClose { reg.remove() }
    }

    /** A live query. */
    fun query(q: Query): Flow<QuerySnapshot?> = callbackFlow {
        val reg = q.addSnapshotListener { s, _ -> trySend(s) }
        awaitClose { reg.remove() }
    }

    suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<*, *>? = fns.getHttpsCallable(name).call(data).await().getData() as? Map<*, *>

    fun deviceId(ctx: Context): String {
        val p = ctx.getSharedPreferences("ontask", Context.MODE_PRIVATE)
        return p.getString("deviceId", null) ?: ("phone-" + UUID.randomUUID()).also { p.edit().putString("deviceId", it).apply() }
    }

    /** Creates/updates the profile, registers this phone as a device, and stores its push token. */
    suspend fun ensureUser(ctx: Context, u: FirebaseUser) {
        appContext = ctx.applicationContext
        val ref = db.document("users/${u.uid}")
        val name = u.displayName ?: u.email?.substringBefore('@') ?: "Friend"
        val now = System.currentTimeMillis()
        if (!ref.get().await().exists()) ref.set(mapOf("displayName" to name, "email" to u.email, "createdAt" to now, "lastSeenMs" to now)).await()
        else ref.set(mapOf("lastSeenMs" to now) + (u.email?.let { mapOf("email" to it) } ?: emptyMap()), SetOptions.merge()).await()
        db.document("users/${u.uid}/devices/${deviceId(ctx)}").set(mapOf("kind" to "phone", "name" to "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}", "lastSeenMs" to now), SetOptions.merge())
        runCatching { saveToken(Firebase.messaging.token.await()) }
    }

    fun saveToken(token: String) {
        val u = uid ?: return
        db.document("users/$u").update("fcmTokens.$token", System.currentTimeMillis())
    }

    fun labelRequest(subject: String, category: String, itemKey: String?, title: String?) {
        val u = uid ?: return
        db.collection("users/$u/labelRequests").add(buildMap {
            put("subject", subject); put("category", category); put("ts", System.currentTimeMillis())
            itemKey?.let { put("itemKey", it) }; title?.let { put("title", it) }
        })
    }

    fun removeToken(token: String) { uid?.let { db.document("users/$it").update("fcmTokens.$token", FieldValue.delete()) } }
}
