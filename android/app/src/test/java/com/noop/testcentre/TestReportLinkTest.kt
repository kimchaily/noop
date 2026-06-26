package com.noop.testcentre

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirrors the Swift TestReportLinkTests. No Robolectric in this project (junit only), so we test the
 * pure reportUrlString() rather than the Uri wrapper. Must byte-match the Swift URLComponents output:
 * same field order, "%2C" for the label comma, "%5B"/"%5D" for the title brackets, "%20" for spaces.
 */
class TestReportLinkTest {

    @Test
    fun sleepProfileEncodesEveryFieldAndLabel() {
        val s = TestReportLink.reportUrlString(
            profile = TestDomain.SLEEP, title = "no score last night",
            version = "7.3.0", platform = "Android", osVersion = "15")
        assertTrue(s.startsWith("https://github.com/NoopApp/noop/issues/new?"))
        assertTrue(s.contains("template=bug_report.yml"))
        assertTrue(s.contains("labels=bug%2Ctest:sleep"))
        assertTrue(s.contains("version=7.3.0"))
        assertTrue(s.contains("platform=Android"))
        assertTrue(s.contains("os_version=15"))
        assertTrue(s.contains("test_profile=sleep"))
        assertTrue(s.contains("title=%5Bsleep%5D%20no%20score%20last%20night"))
    }

    @Test
    fun importProfileUsesImportWireId() {
        val s = TestReportLink.reportUrlString(
            profile = TestDomain.IMPORT, title = "x",
            version = "7.3.0", platform = "Android", osVersion = "15")
        assertTrue(s.contains("test_profile=import"))
        assertTrue(s.contains("labels=bug%2Ctest:import"))
    }

    @Test
    fun masterProfileLabelIsTestAll() {
        val s = TestReportLink.reportUrlString(
            profile = TestDomain.MASTER, title = "x",
            version = "7.3.0", platform = "Android", osVersion = "15")
        assertTrue(s.contains("labels=bug%2Ctest:all"))
    }
}
