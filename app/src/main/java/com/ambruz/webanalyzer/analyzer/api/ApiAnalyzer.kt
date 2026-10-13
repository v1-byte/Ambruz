package com.ambruz.webanalyzer.analyzer.api

import com.ambruz.webanalyzer.model.EndpointClass
import com.ambruz.webanalyzer.model.EndpointRecord
import java.util.regex.Pattern

/**
 * M5: inventaris endpoint yang terlihat di source/HTML yang tersedia.
 * Klasifikasi = perkiraan; pengguna dapat mengoreksi.
 */
class ApiAnalyzer {
    fun findInText(pageUrl: String, text: String): List<EndpointRecord> {
        val out = linkedMapOf<String, EndpointRecord>()
        val re = Pattern.compile(
            """https?://[^\s"'<>\\]+|/api/[^\s"'<>\\]+""",
            Pattern.CASE_INSENSITIVE
        )
        val m = re.matcher(text)
        while (m.find()) {
            var u = m.group().trimEnd('.', ',', ')', ']', '}', '"', '\'')
            if (u.startsWith("/")) {
                // path relatif — simpan apa adanya; resolusi penuh butuh origin
                u = pageUrl.trimEnd('/') + u
            }
            if (u !in out) {
                out[u] = EndpointRecord(
                    url = u,
                    classification = classify(u),
                    triggerPage = pageUrl
                )
            }
        }
        return out.values.toList()
    }

    fun classify(url: String): EndpointClass {
        val u = url.lowercase()
        return when {
            "login" in u || "auth" in u || "oauth" in u || "token" in u -> EndpointClass.AUTH
            "upload" in u -> EndpointClass.UPLOAD
            "download" in u -> EndpointClass.DOWNLOAD
            "pay" in u || "billing" in u || "checkout" in u -> EndpointClass.PAYMENT
            "analytics" in u || "track" in u || "telemetry" in u -> EndpointClass.ANALYTICS
            "game" in u || "play" in u || "match" in u -> EndpointClass.GAME
            "user" in u || "profile" in u || "me" in u -> EndpointClass.USER_PROFILE
            "config" in u || "settings" in u -> EndpointClass.CONFIG
            "ws://" in u || "wss://" in u -> EndpointClass.WEBSOCKET
            "/api/" in u -> EndpointClass.PUBLIC
            else -> EndpointClass.UNKNOWN
        }
    }
}
