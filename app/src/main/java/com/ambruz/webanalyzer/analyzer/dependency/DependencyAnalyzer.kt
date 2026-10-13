package com.ambruz.webanalyzer.analyzer.dependency

import com.ambruz.webanalyzer.model.EndpointRecord
import com.ambruz.webanalyzer.model.ResourceRecord

data class DependencyEdge(
    val from: String,
    val to: String,
    val relation: String
)

/** M5: peta hubungan halaman → resource → API (yang teramati). */
class DependencyAnalyzer {
    fun build(pageUrl: String, resources: List<ResourceRecord>, endpoints: List<EndpointRecord>): List<DependencyEdge> {
        val edges = mutableListOf<DependencyEdge>()
        resources.forEach { r ->
            edges += DependencyEdge(pageUrl, r.url, "loads:${r.kind}")
        }
        endpoints.forEach { e ->
            edges += DependencyEdge(e.triggerPage ?: pageUrl, e.url, "calls:${e.classification}")
        }
        return edges
    }
}
