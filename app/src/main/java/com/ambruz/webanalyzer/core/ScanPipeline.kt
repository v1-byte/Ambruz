package com.ambruz.webanalyzer.core

import com.ambruz.webanalyzer.analyzer.api.ApiAnalyzer
import com.ambruz.webanalyzer.analyzer.dependency.DependencyAnalyzer
import com.ambruz.webanalyzer.analyzer.domain.DomainAnalyzer
import com.ambruz.webanalyzer.analyzer.resource.ResourceAnalyzer
import com.ambruz.webanalyzer.analyzer.server.ServerAnalyzer
import com.ambruz.webanalyzer.model.AccessLevel
import com.ambruz.webanalyzer.model.Project
import com.ambruz.webanalyzer.model.ProjectStatus
import com.ambruz.webanalyzer.model.ScanLimits
import com.ambruz.webanalyzer.scanner.UrlScanner

/**
 * Orkestrasi scan Level A (public) — fondasi M1–M5.
 */
class ScanPipeline(
    private val scanner: UrlScanner = UrlScanner(),
    private val resources: ResourceAnalyzer = ResourceAnalyzer(),
    private val domains: DomainAnalyzer = DomainAnalyzer(),
    private val apis: ApiAnalyzer = ApiAnalyzer(),
    private val servers: ServerAnalyzer = ServerAnalyzer(),
    private val deps: DependencyAnalyzer = DependencyAnalyzer(),
    private val limits: ScanLimits = ScanLimits()
) {
    fun run(rawUrl: String, access: AccessLevel = AccessLevel.A_PUBLIC): Project {
        val limitations = mutableListOf(
            "Level ${access.name}: hanya data yang tersedia sesuai akses.",
            "DOM/HTML yang diambil bukan pengganti source code backend.",
            "Endpoint yang tidak teramati tidak berarti tidak ada."
        )
        if (access == AccessLevel.A_PUBLIC) {
            limitations += "Public Inspection tidak menjamin source privat, log, atau DB."
        }

        val page = scanner.scan(rawUrl)
        if (page.error != null) {
            return Project(
                sourceUrl = rawUrl,
                status = ProjectStatus.ERROR,
                accessLevel = access,
                limitations = limitations + listOf("Scan gagal: ${page.error}")
            )
        }

        val html = page.bodySnippet.orEmpty()
        val res = resources.extractFromHtml(page.finalUrl, html).take(limits.maxResources)
        val dom = domains.analyze(page.finalUrl, res)
        val endpoints = apis.findInText(page.finalUrl, html)
        val serverObs = servers.fromHeaders(page.finalUrl, page.statusCode, page.headers)
        limitations += serverObs.notes
        if (page.fragment != null) {
            limitations += "Fragmen SPA (#${page.fragment}) tidak dikirim ke server HTTP; analisis rute mungkin butuh navigasi WebView atau source yang diizinkan."
        }

        val edgeCount = deps.build(page.finalUrl, res, endpoints).size
        limitations += "Dependency edges teramati: $edgeCount"

        return Project(
            sourceUrl = rawUrl,
            finalUrl = page.finalUrl,
            accessLevel = access,
            status = ProjectStatus.ANALYZED,
            resources = res,
            domains = dom,
            endpoints = endpoints,
            limitations = limitations,
            notes = "status=${page.statusCode}; type=${page.contentType}"
        )
    }
}
