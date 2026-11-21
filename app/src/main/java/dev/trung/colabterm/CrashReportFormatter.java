package dev.trung.colabterm;

final class CrashReportFormatter {
    static final int MAX_REPORT_CHARS = 96 * 1024;
    private static final String TRUNCATION_MARKER = "\n[Report truncated: size limit reached]\n";

    private CrashReportFormatter() { }

    static String format(String appVersion, String device, String androidVersion, int apiLevel,
                         String abi, String timestamp, String startupEvents, String stackTrace) {
        StringBuilder report = new StringBuilder();
        report.append("ColabTerm debug report\n")
                .append("Time: ").append(oneLine(timestamp)).append('\n')
                .append("App version: ").append(oneLine(appVersion)).append('\n')
                .append("Device: ").append(oneLine(device)).append('\n')
                .append("Android: ").append(oneLine(androidVersion))
                .append(" (API ").append(apiLevel).append(")\n")
                .append("ABI: ").append(oneLine(abi)).append("\n\n")
                .append("Startup events:\n");
        appendBounded(report, startupEvents == null ? "(none)\n" : startupEvents);
        appendBounded(report, "\nCrash / process exit details:\n");
        appendBounded(report, stackTrace == null ? "(no stack trace)\n" : stackTrace);

        if (report.length() > MAX_REPORT_CHARS) {
            int contentLimit = MAX_REPORT_CHARS - TRUNCATION_MARKER.length();
            report.setLength(contentLimit);
            report.append(TRUNCATION_MARKER);
        }
        return report.toString();
    }

    private static void appendBounded(StringBuilder target, String value) {
        int remaining = MAX_REPORT_CHARS + 1 - target.length();
        if (remaining <= 0) return;
        target.append(value, 0, Math.min(remaining, value.length()));
    }

    private static String oneLine(String value) {
        if (value == null || value.isEmpty()) return "unknown";
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
