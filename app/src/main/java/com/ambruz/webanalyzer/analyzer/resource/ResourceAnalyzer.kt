package com.ambruz.webanalyzer.analyzer.resource

import com.ambruz.webanalyzer.model.ResourceKind
import com.ambruz.webanalyzer.model.ResourceRecord
import java.net.URI
import java.util.regex.Pattern

/**
 * M3: inventaris resource dari HTML yang tersedia (bukan pengganti source backend).
 */
class ResourceAnalyzer {

    fun extractFromHtml(pageUrl: String, html: String): List<ResourceRecord> {
        val found = linkedMapOf<String, ResourceRecord>()
        fun add(raw: String, kind: ResourceKind) {
            val abs = resolve(pageUrl, raw) ?: return
            if (abs !in found) {
                found[abs] = ResourceRecord(
                    url = abs,
                    kind = kind,
                    discoveredFrom = pageUrl
                )
            }
        }

        ATTR_PATTERNS.forEach { (pattern, kind) ->
            val m = pattern.matcher(html)
            while (m.find()) add(m.group(1), kind)
        }
        // link rel
        val linkRe = Pattern.compile(
            """<link[^>]+href=["']([^"']+)["'][^>]*>""",
            Pattern.CASE_INSENSITIVE
        )
        val lm = linkRe.matcher(html)
        while (lm.find()) {
            val tag = lm.group(0).lowercase()
            val href = lm.group(1)
            val kind = when {
                "stylesheet" in tag -> ResourceKind.CSS
                "icon" in tag -> ResourceKind.ICON
                "manifest" in tag -> ResourceKind.MANIFEST
                else -> ResourceKind.OTHER
            }
            add(href, kind)
        }
        return found.values.toList()
    }

    private fun resolve(base: String, ref: String): String? {
        val t = ref.trim()
        if (t.isEmpty() || t.startsWith("data:") || t.startsWith("javascript:")) return null
        return try {
            URI(base).resolve(t).toString()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private val ATTR_PATTERNS = listOf(
            Pattern.compile("""<script[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE) to ResourceKind.JAVASCRIPT,
            Pattern.compile("""<img[^>]+src=["']([^"']+)["']""", Pattern.CASE_INSENSITIVE) to ResourceKind.IMAGE,
            Pattern.compile("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", Pattern.CASE_INSENSITIVE) to ResourceKind.OTHER,
        )
    }
}
