package com.sqlai.assistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test

/** [SelfTestSuite] contract: >= 30 checks and they all pass on the JVM. */
class SelfTestSuiteTest {

    @Test
    fun suiteHasAtLeastThirtyChecksAndAllPass() {
        val report = SelfTestSuite.run()

        assertTrue(
            "expected >= 30 checks, got ${report.total}",
            report.total >= 30
        )
        val failed = report.failed.joinToString { "${it.name}: ${it.detail}" }
        assertTrue("failing checks: $failed", report.allPass)
    }

    @Test
    fun suiteIsDeterministicAcrossRuns() {
        val first = SelfTestSuite.run()
        val second = SelfTestSuite.run()

        assertTrue(first.results.map { it.name } == second.results.map { it.name })
        assertTrue(first.results.map { it.ok } == second.results.map { it.ok })
    }

    @Test
    fun reportExposesCounts() {
        val report = SelfTestSuite.run()

        assertTrue(report.total == report.passed + report.failed.size)
        assertTrue(report.passed == report.total)
    }
}
