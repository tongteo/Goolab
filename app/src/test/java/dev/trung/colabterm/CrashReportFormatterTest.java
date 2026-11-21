package dev.trung.colabterm;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CrashReportFormatterTest {
    @Test
    public void reportIncludesAppDeviceStartupAndCrashDetails() {
        String report = CrashReportFormatter.format(
                "0.1.0", "Google/Pixel 8/oriole", "16", 35, "arm64-v8a",
                "2026-11-21 17:00:00 +0700", "Application.onCreate entered\n",
                "java.lang.IllegalStateException: startup failed\n at Example.run(Example.java:1)\n");

        assertTrue(report.contains("ColabTerm debug report"));
        assertTrue(report.contains("App version: 0.1.0"));
        assertTrue(report.contains("Device: Google/Pixel 8/oriole"));
        assertTrue(report.contains("Android: 16 (API 35)"));
        assertTrue(report.contains("ABI: arm64-v8a"));
        assertTrue(report.contains("Application.onCreate entered"));
        assertTrue(report.contains("IllegalStateException: startup failed"));
    }

    @Test
    public void reportIsBoundedWhenExceptionTextIsUnusuallyLarge() {
        StringBuilder oversizedTrace = new StringBuilder();
        for (int index = 0; index < CrashReportFormatter.MAX_REPORT_CHARS * 2; index++) {
            oversizedTrace.append('x');
        }

        String report = CrashReportFormatter.format(
                "0.1.0", "brand/model", "16", 35, "arm64-v8a",
                "time", "startup\n", oversizedTrace.toString());

        assertEquals(CrashReportFormatter.MAX_REPORT_CHARS, report.length());
        assertTrue(report.startsWith("ColabTerm debug report"));
    }
}
