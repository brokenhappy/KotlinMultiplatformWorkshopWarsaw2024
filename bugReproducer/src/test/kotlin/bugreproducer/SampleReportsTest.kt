package bugreproducer

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SampleReportsTest {
    @Test
    fun `sample reports exercise conflict and compilation preparation`() = runTest {
        val testClasses = Path.of(SampleReportsTest::class.java.protectionDomain.codeSource.location.toURI())
        val sampleDirectory = generateSequence(testClasses) { it.parent }
            .map { it.resolve("sample-reports") }
            .first { Files.isDirectory(it.resolve("client_bug_reports")) }
        val reports = loadClientBugReports(sampleDirectory)
        assertTrue(reports.malformed.isEmpty(), reports.malformed.toString())
        assertTrue(reports.reports.any { it.report.clientReport.description.contains("conflict") })
        assertTrue(reports.reports.any { it.report.clientReport.description.contains("compilation") })

        val manager = GitBugReproducerWorktreeManager()
        val conflictReport = reports.reports.single { it.report.clientReport.description.contains("conflict") }
        val conflictResult = manager.prepare(parseReproductionDiagnostics(conflictReport.report).getOrThrow())
        val conflict = assertIs<WorktreePreparationResult.Conflicts>(conflictResult, conflictResult.toString())
        try {
            assertTrue(conflict.paths.any { it.endsWith("ShipmentTracking.kt") })
        } finally {
            manager.cleanup(conflict.worktree)
        }

        val compilationReport = reports.reports.single {
            it.report.clientReport.description.contains("compilation")
        }
        val compilationResult = manager.prepare(parseReproductionDiagnostics(compilationReport.report).getOrThrow())
        assertTrue(compilationResult !is WorktreePreparationResult.Failed, compilationResult.toString())
        val compilation = assertIs<WorktreePreparationResult.Ready>(compilationResult)
        try {
            assertTrue(
                compilation.prepared.directory
                    .resolve("client/src/main/kotlin/kmpworkshop/client/ClientScaffolding.kt")
                    .readText()
                    .contains("this is intentionally invalid Kotlin"),
            )
            assertTrue(apiKeyFromAppliedClientFiles(compilation.prepared.directory).isSuccess)
        } finally {
            manager.cleanup(compilation.prepared.directory)
        }
    }
}
