package dev.trung.colabterm;

import android.annotation.TargetApi;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

final class AppDiagnostics {
    private static final String REPORT_FILE = "startup-crash.txt";
    private static final String EVENTS_FILE = "startup-events.txt";
    private static final String EXPECTED_EXIT_FILE = "diagnostics-exit.flag";
    private static final int MAX_EVENT_LOG_CHARS = 32 * 1024;
    private static final int MAX_SYSTEM_TRACE_CHARS = 64 * 1024;
    private static final Object FILE_LOCK = new Object();

    private static Context appContext;
    private static boolean handlerInstalled;

    private AppDiagnostics() { }

    static synchronized void install(Context context) {
        appContext = context.getApplicationContext();
        if (handlerInstalled) return;

        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof DiagnosticsExceptionHandler) {
            handlerInstalled = true;
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(
                new DiagnosticsExceptionHandler(appContext, previous));
        handlerInstalled = true;
    }

    static void beginStartup(Context context) {
        synchronized (FILE_LOCK) {
            try {
                File file = getEventsFile(context);
                File directory = file.getParentFile();
                if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return;
                writeSynced(file, "Startup at " + timestamp() + "\n");
            } catch (Throwable ignored) {
                // Diagnostics must never prevent the app from starting.
            }
        }
    }

    static boolean capturePreviousExit(Context context) {
        if (consumeExpectedExit(context)) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasPendingCrash(context)) return false;
        return captureAndroidExit(context);
    }

    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.R)
    private static boolean captureAndroidExit(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 8);
            if (exits == null) return false;

            ApplicationExitInfo latestAppExit = null;
            for (ApplicationExitInfo exit : exits) {
                if (context.getPackageName().equals(exit.getProcessName())) {
                    latestAppExit = exit;
                    break;
                }
            }
            if (latestAppExit == null || !isCrashExitReason(latestAppExit.getReason())) return false;

            StringBuilder details = new StringBuilder();
            details.append("Android process exit: ")
                    .append(exitReasonName(latestAppExit.getReason()))
                    .append(" (reason ").append(latestAppExit.getReason()).append(")\n")
                    .append("Process: ").append(latestAppExit.getProcessName())
                    .append("; PID: ").append(latestAppExit.getPid())
                    .append("; status: ").append(latestAppExit.getStatus())
                    .append("; time: ").append(timestamp(latestAppExit.getTimestamp())).append('\n');
            if (latestAppExit.getDescription() != null) {
                details.append("Description: ").append(latestAppExit.getDescription()).append('\n');
            }
            String systemTrace = readSystemTrace(latestAppExit);
            if (!systemTrace.isEmpty()) details.append("\nAndroid system trace:\n").append(systemTrace);

            String report = CrashReportFormatter.format(
                    appVersion(context), deviceDescription(), Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT, abiDescription(), timestamp(), readFile(getEventsFile(context)),
                    details.toString());
            File file = getReportFile(context);
            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return false;
            writeSynced(file, report);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.R)
    private static boolean isCrashExitReason(int reason) {
        return reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                || reason == ApplicationExitInfo.REASON_SIGNALED;
    }

    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.R)
    private static String exitReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: return "Java crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "application not responding (ANR)";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "initialization failure";
            case ApplicationExitInfo.REASON_SIGNALED: return "process terminated by signal";
            default: return "other";
        }
    }

    @SuppressLint("UseRequiresApi")
    @TargetApi(Build.VERSION_CODES.R)
    private static String readSystemTrace(ApplicationExitInfo exit) {
        try (InputStream stream = exit.getTraceInputStream()) {
            if (stream == null) return "";
            StringBuilder trace = new StringBuilder();
            InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            char[] buffer = new char[4096];
            int count;
            while (trace.length() < MAX_SYSTEM_TRACE_CHARS
                    && (count = reader.read(buffer, 0,
                    Math.min(buffer.length, MAX_SYSTEM_TRACE_CHARS - trace.length()))) != -1) {
                trace.append(buffer, 0, count);
            }
            if (trace.length() >= MAX_SYSTEM_TRACE_CHARS) trace.append("\n[system trace truncated]\n");
            return trace.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void mark(Context context, String event) {
        if (event == null || event.isEmpty()) return;
        synchronized (FILE_LOCK) {
            try {
                File file = getEventsFile(context);
                File directory = file.getParentFile();
                if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return;
                String previous = readFile(file);
                String line = timestamp() + "  " + event.replace('\n', ' ').replace('\r', ' ') + "\n";
                String combined = previous + line;
                if (combined.length() > MAX_EVENT_LOG_CHARS) {
                    combined = combined.substring(combined.length() - MAX_EVENT_LOG_CHARS);
                    int firstLineEnd = combined.indexOf('\n');
                    if (firstLineEnd >= 0 && firstLineEnd + 1 < combined.length()) {
                        combined = combined.substring(firstLineEnd + 1);
                    }
                }
                writeSynced(file, combined);
            } catch (Throwable ignored) {
                // Diagnostics must never prevent the app from starting.
            }
        }
    }

    static boolean hasPendingCrash(Context context) {
        try {
            File file = getReportFile(context);
            return file.isFile() && file.length() > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String readCrashReport(Context context) {
        synchronized (FILE_LOCK) {
            try {
                File file = getReportFile(context);
                if (!file.isFile()) return "No saved crash report.";
                return readFile(file);
            } catch (Throwable error) {
                return "Could not read the crash report: " + error.getClass().getSimpleName();
            }
        }
    }

    static boolean clearCrashReport(Context context) {
        synchronized (FILE_LOCK) {
            try {
                File expectedExit = new File(getDirectory(context), EXPECTED_EXIT_FILE);
                File directory = expectedExit.getParentFile();
                if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return false;
                writeSynced(expectedExit, "User cleared diagnostics before process exit.\n");
                File file = getReportFile(context);
                if (!file.exists() || file.delete()) return true;
                expectedExit.delete();
                return false;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    static void saveHandledFailure(Context context, String label, Throwable error) {
        writeCrashReport(context, label, Thread.currentThread(), error);
    }

    private static boolean consumeExpectedExit(Context context) {
        synchronized (FILE_LOCK) {
            try {
                File file = new File(getDirectory(context), EXPECTED_EXIT_FILE);
                if (!file.isFile()) return false;
                file.delete();
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    private static void writeCrashReport(Context context, Thread thread, Throwable error) {
        writeCrashReport(context, "Uncaught exception", thread, error);
    }

    private static void writeCrashReport(Context context, String failureType,
                                         Thread thread, Throwable error) {
        synchronized (FILE_LOCK) {
            try {
                File file = getReportFile(context);
                if (file.isFile() && file.length() > 0) return;
                StringWriter traceBuffer = new StringWriter();
                error.printStackTrace(new PrintWriter(traceBuffer));
                String events = readFile(getEventsFile(context));
                String threadLine = failureType + " on thread: "
                        + (thread == null ? "unknown" : thread.getName()) + "\n";
                String report = CrashReportFormatter.format(
                        appVersion(context), deviceDescription(), Build.VERSION.RELEASE,
                        Build.VERSION.SDK_INT, abiDescription(), timestamp(),
                        threadLine + events, traceBuffer.toString());
                File directory = file.getParentFile();
                if (directory != null && !directory.isDirectory() && !directory.mkdirs()) return;
                writeSynced(file, report);
            } catch (Throwable ignored) {
                // Avoid replacing the original uncaught exception with a logging failure.
            }
        }
    }

    private static String appVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String version = info.versionName == null ? "unknown" : info.versionName;
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return version + " (" + code + ")";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String deviceDescription() {
        return Build.MANUFACTURER + "/" + Build.MODEL + "/" + Build.DEVICE;
    }

    private static String abiDescription() {
        return Build.SUPPORTED_ABIS == null ? "unknown" : Arrays.toString(Build.SUPPORTED_ABIS);
    }

    private static String timestamp() {
        return timestamp(System.currentTimeMillis());
    }

    private static String timestamp(long timeMillis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(new Date(timeMillis));
    }

    private static File getReportFile(Context context) {
        return new File(getDirectory(context), REPORT_FILE);
    }

    private static File getEventsFile(Context context) {
        return new File(getDirectory(context), EVENTS_FILE);
    }

    private static File getDirectory(Context context) {
        Context effectiveContext = context.getApplicationContext();
        if (effectiveContext == null) effectiveContext = context;
        return new File(effectiveContext.getFilesDir(), "diagnostics");
    }

    private static String readFile(File file) throws Exception {
        if (!file.isFile()) return "";
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) content.append(buffer, 0, count);
        }
        return content.toString();
    }

    private static void writeSynced(File file, String content) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file, false);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     stream, StandardCharsets.UTF_8))) {
            writer.write(content);
            writer.flush();
            stream.getFD().sync();
        }
    }

    private static final class DiagnosticsExceptionHandler implements Thread.UncaughtExceptionHandler {
        private final Context context;
        private final Thread.UncaughtExceptionHandler previous;

        private DiagnosticsExceptionHandler(Context context,
                                            Thread.UncaughtExceptionHandler previous) {
            this.context = context;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            writeCrashReport(context, thread, error);
            if (previous != null && previous != this) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        }
    }
}
