package com.gaslog.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

object LocationHelper {
    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A fresh-ish location, or null (no permission, location off, or no fix within ~12 s). */
    @SuppressLint("MissingPermission")
    suspend fun current(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (recent != null && System.currentTimeMillis() - recent.time < 2 * 60_000L) return recent

        val provider = providers.firstOrNull() ?: return recent
        val fresh = withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(ctx)) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            }
        }
        return fresh ?: recent
    }
}

object Connectivity {
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** True for "couldn't reach the internet" failures (worth queueing), not for Google saying no. */
    fun isNetworkError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is UnknownHostException || t is SocketTimeoutException || t is ConnectException ||
                t is NoRouteToHostException || t is SSLException
            ) return true
            if (t is com.google.android.gms.common.api.ApiException &&
                t.statusCode == com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR
            ) return true
            if (t is ApiException) return false
            t = t.cause
        }
        return e is IOException && e.message?.contains("Unable to resolve host", ignoreCase = true) == true
    }
}

fun installedBuild(ctx: Context): Int = runCatching {
    @Suppress("DEPRECATION")
    val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
    PackageInfoCompat.getLongVersionCode(info).toInt()
}.getOrDefault(0)
