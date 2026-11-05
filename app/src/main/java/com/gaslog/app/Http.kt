package com.gaslog.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An HTTP error from Google, with Google's own message when it sent one. */
class ApiException(val code: Int, message: String) : IOException(message)

object Http {
    /** Blocking request — call from a background dispatcher. Returns the response body. */
    fun request(
        method: String,
        url: String,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        readTimeoutMs: Int = 30_000,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiException(code, errorMessage(text, code))
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(text: String, code: Int): String =
        try {
            JSONObject(text).getJSONObject("error").getString("message")
        } catch (e: Exception) {
            "HTTP $code"
        }

    /** Percent-encodes a URL path segment or query value. */
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
