package com.github.supersliser.supabase

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SupabaseClient {
    companion object {
        const val SUPABASE_URL = "https://qjzgfwithyvmwctesnqs.supabase.co"
        const val SUPABASE_KEY = "sb_publishable_cxq8QZp9BDtjE4G5qiPCFA_lUZ4Cbdh"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
    }

    fun supabaseGet(table: String, filters: Map<String, String> = emptyMap()): String {
        val query = buildString {
            append("select=*")
            for ((key, value) in filters) {
                append("&$key=eq.${urlEncode(value)}")
            }
        }

        val endpoint = "$SUPABASE_URL/rest/v1/$table?$query"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("apikey", SUPABASE_KEY)
            setRequestProperty("Authorization", "Bearer $SUPABASE_KEY")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val body = runCatching {
                connection.inputStream.bufferedReader().use { it.readText() }
            }.getOrElse { "" }

            val code = connection.responseCode
            if (code !in 200..299) {
                println("[KeyIntercept] Supabase request failed: $code")
            }

            body
        } finally {
            connection.disconnect()
        }
    }
}
