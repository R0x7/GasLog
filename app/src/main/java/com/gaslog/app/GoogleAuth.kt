package com.gaslog.app

import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Supplies Google access tokens for the Sheets API. */
interface TokenSource {
    suspend fun token(): String
    suspend fun invalidate(token: String)
}

/**
 * Asks Google for permission to use the person's spreadsheets (one-time account picker + consent),
 * then hands out short-lived access tokens. Must be created during Activity.onCreate.
 */
class GoogleAuth(private val activity: ComponentActivity) : TokenSource {
    private val client: AuthorizationClient get() = Identity.getAuthorizationClient(activity)
    private var pending: CompletableDeferred<AuthorizationResult>? = null

    private val consentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val waiting = pending ?: return@registerForActivityResult
        pending = null
        try {
            waiting.complete(client.getAuthorizationResultFromIntent(result.data))
        } catch (e: Exception) {
            waiting.completeExceptionally(IOException("Google access wasn't granted.", e))
        }
    }

    override suspend fun token(): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SHEETS_SCOPE)))
            .build()
        var result = client.authorize(request).await()
        if (result.hasResolution()) {
            val intent = result.pendingIntent ?: throw IOException("Google sign-in couldn't start.")
            val waiting = CompletableDeferred<AuthorizationResult>()
            pending = waiting
            consentLauncher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
            result = waiting.await()
        }
        return result.accessToken ?: throw IOException("Google didn't return an access token.")
    }

    override suspend fun invalidate(token: String) {
        runCatching {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        }
    }

    companion object {
        const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    }
}

/** Awaits a Play services Task without an extra library. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
