package dev.trung.colabterm;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.Manifest;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import java.nio.charset.StandardCharsets;

public final class TermuxShellActivity extends Activity {
    private static final int COLOR_BG = Color.BLACK;
    private static final int COLOR_TEXT = Color.rgb(218, 232, 222);
    private static final int COLOR_MUTED = Color.rgb(139, 163, 146);
    private static final int COLOR_ACCENT = Color.rgb(141, 224, 177);
    private static final String FONT_SIZE_PREFERENCE = "terminal_font_size_sp";
    private static final int REQUEST_POST_NOTIFICATIONS = 42;
    private static final String NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested";

    private TerminalView terminalView;
    private TerminalSession terminalSession;
    private TextView status;
    private Button ctrlButton;
    private final TerminalModifierState modifierState = new TerminalModifierState();
    private Button fontSizeButton;
    private int fontSizeSp;
    private Button restartButton;
    private Button stopShellButton;
    private Button notificationButton;
    private ShellTerminalViewClient viewClient;
    private ShellTerminalSessionClient sessionClient;
    private ShellTerminalService shellService;
    private boolean serviceBound;
    private boolean destroyed;

    private final ServiceConnection shellServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof ShellTerminalService.LocalBinder)) return;
            shellService = ((ShellTerminalService.LocalBinder) binder).getService();
            shellService.setUiClient(sessionClient);
            shellService.startShellFromVisibleActivity();
            attachServiceSession();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (shellService != null) shellService.clearUiClient(sessionClient);
            shellService = null;
            terminalSession = null;
            showStatus("Terminal service disconnected; try reopening the app.");
            restartButton.setVisibility(View.VISIBLE);
            stopShellButton.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppDiagnostics.mark(this, "TermuxShellActivity.onCreate entered");
        if (AppDiagnostics.hasPendingCrash(this)) {
            showCrashRecovery();
            return;
        }
        fontSizeSp = TerminalFontSize.clamp(getPreferences(MODE_PRIVATE).getInt(
                FONT_SIZE_PREFERENCE, TerminalFontSize.DEFAULT_SP));
        AppDiagnostics.mark(this, "Building shell interface");
        buildUi();
        AppDiagnostics.mark(this, "Shell interface built; service will start while Activity is visible");
    }

    @SuppressLint("SetTextI18n")
    private void showCrashRecovery() {
        AppDiagnostics.mark(this, "Showing saved crash report");
        String report = AppDiagnostics.readCrashReport(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("ColabTerm · Diagnostics");
        title.setTextColor(COLOR_ACCENT);
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText("An unexpected shutdown was detected during the previous launch. The report includes the app version, device information, startup milestones, and stack trace. The app does not actively collect OAuth tokens, terminal/file contents, or logcat; the system trace may contain system data or paths. Review it before sharing.");
        explanation.setTextColor(COLOR_TEXT);
        explanation.setTextSize(14);
        explanation.setPadding(0, dp(12), 0, dp(10));
        root.addView(explanation);

        ScrollView reportScroll = new ScrollView(this);
        reportScroll.setBackgroundColor(Color.rgb(20, 29, 24));
        reportScroll.setPadding(dp(10), dp(8), dp(10), dp(8));
        TextView reportView = new TextView(this);
        reportView.setText(report);
        reportView.setTextColor(COLOR_TEXT);
        reportView.setTextSize(12);
        reportView.setTypeface(Typeface.MONOSPACE);
        reportView.setTextIsSelectable(true);
        reportScroll.addView(reportView);
        root.addView(reportScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        Button shareButton = makeButton("Share debug report");
        shareButton.setOnClickListener(view -> shareCrashReport(report));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        buttonParams.topMargin = dp(10);
        root.addView(shareButton, buttonParams);

        Button retryButton = makeButton("Clear report and exit");
        retryButton.setOnClickListener(view -> clearReportAndExit());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        retryParams.topMargin = dp(6);
        root.addView(retryButton, retryParams);

        TextView retryHint = new TextView(this);
        retryHint.setText("After exiting, reopen ColabTerm to try a normal startup.");
        retryHint.setTextColor(COLOR_MUTED);
        retryHint.setTextSize(12);
        retryHint.setPadding(0, dp(6), 0, 0);
        root.addView(retryHint);

        setContentView(root);
        WindowInsetsHelper.apply(root);
    }

    private void shareCrashReport(String report) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, "ColabTerm debug report");
        shareIntent.putExtra(Intent.EXTRA_TEXT, report);
        try {
            startActivity(Intent.createChooser(shareIntent, "Share diagnostic report"));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "No app found to share the report.", Toast.LENGTH_LONG).show();
        }
    }

    private void clearReportAndExit() {
        if (!AppDiagnostics.clearCrashReport(this)) {
            Toast.makeText(this, "Unable to clear the report; it has been retained.", Toast.LENGTH_LONG).show();
            return;
        }
        finishAndRemoveTask();
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> android.os.Process.killProcess(android.os.Process.myPid()), 250);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(0, 0, 0, 0);

        terminalView = new TerminalView(this, null);
        terminalView.setBackgroundColor(Color.BLACK);
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.setTextSize(TerminalFontSize.toPixels(fontSizeSp,
                getResources().getDisplayMetrics().scaledDensity));
        terminalView.setKeepScreenOn(true);
        viewClient = new ShellTerminalViewClient();
        sessionClient = new ShellTerminalSessionClient();
        terminalView.setTerminalViewClient(viewClient);
        LinearLayout.LayoutParams terminalParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        terminalParams.bottomMargin = 0;
        root.addView(terminalView, terminalParams);

        status = new TextView(this);
        showStatus("Opening terminal…");
        status.setTextColor(COLOR_MUTED);
        status.setTextSize(10);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(18)));

        HorizontalScrollView keyScroll = new HorizontalScrollView(this);
        keyScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.setOrientation(LinearLayout.HORIZONTAL);

        Button keyboardButton = makeButton("Keyboard");
        keyboardButton.setOnClickListener(view -> showTerminalKeyboard());
        keys.addView(keyboardButton, keyParams());

        ctrlButton = makeButton("CTRL");
        ctrlButton.setOnClickListener(view -> {
            modifierState.toggleControlKey();
            updateToggleButton(ctrlButton, modifierState.isControlKeyDown());
        });
        keys.addView(ctrlButton, keyParams());

        Button altButton = makeButton("ALT");
        altButton.setOnClickListener(view -> {
            viewClient.altKeyDown = !viewClient.altKeyDown;
            updateToggleButton(altButton, viewClient.altKeyDown);
        });
        keys.addView(altButton, keyParams());

        addKeyButton(keys, "ESC", "\u001b");
        addKeyButton(keys, "TAB", "\t");
        addKeyButton(keys, "↑", "\u001b[A");
        addKeyButton(keys, "↓", "\u001b[B");
        addKeyButton(keys, "←", "\u001b[D");
        addKeyButton(keys, "→", "\u001b[C");
        addFontSizeButton(keys, "A−", -1);
        fontSizeButton = makeButton(getString(R.string.terminal_font_size, fontSizeSp));
        fontSizeButton.setContentDescription(getString(
                R.string.terminal_font_size_content_description, fontSizeSp));
        fontSizeButton.setOnClickListener(view -> setTerminalFontSize(TerminalFontSize.DEFAULT_SP));
        keys.addView(fontSizeButton, keyParams());
        addFontSizeButton(keys, "A+", 1);

        Button colabButton = makeButton("CLI");
        colabButton.setContentDescription("Open the Colab CLI screen");
        colabButton.setOnClickListener(view -> startActivity(
                new Intent(TermuxShellActivity.this, MainActivity.class)));
        keys.addView(colabButton, keyParams());
        restartButton = makeButton("New shell");
        restartButton.setVisibility(View.GONE);
        restartButton.setOnClickListener(view -> startShell());
        keys.addView(restartButton, keyParams());
        stopShellButton = makeButton("Stop");
        stopShellButton.setVisibility(View.GONE);
        stopShellButton.setOnClickListener(view -> stopShell());
        keys.addView(stopShellButton, keyParams());

        notificationButton = makeButton("Notifications");
        notificationButton.setContentDescription("Grant permission to show terminal notifications");
        notificationButton.setOnClickListener(view -> requestNotificationPermission());
        keys.addView(notificationButton, keyParams());
        keyScroll.addView(keys);
        root.addView(keyScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        WindowInsetsHelper.apply(root);
        updateNotificationButtonVisibility();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (terminalView != null) startAndBindShellService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNotificationButtonVisibility();
    }

    @Override
    protected void onStop() {
        detachFromShellService();
        super.onStop();
    }

    private void addFontSizeButton(LinearLayout row, String label, int changeSp) {
        Button button = makeButton(label);
        button.setContentDescription(changeSp < 0 ? "Decrease terminal font size" : "Increase terminal font size");
        button.setOnClickListener(view -> setTerminalFontSize(
                TerminalFontSize.adjust(fontSizeSp, changeSp)));
        row.addView(button, keyParams());
    }

    private void startAndBindShellService() {
        if (terminalView == null) return;
        Intent intent = new Intent(this, ShellTerminalService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            if (!serviceBound) {
                serviceBound = bindService(intent, shellServiceConnection, Context.BIND_AUTO_CREATE);
                if (!serviceBound) {
                    showStatus("Could not connect to the terminal service.");
                    restartButton.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception error) {
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            showStatus("Could not start the terminal service: " + message);
            restartButton.setVisibility(View.VISIBLE);
        }
    }

    private void detachFromShellService() {
        if (shellService != null) shellService.clearUiClient(sessionClient);
        if (serviceBound) {
            try {
                unbindService(shellServiceConnection);
            } catch (IllegalArgumentException error) {
                Log.w("TermuxShellActivity", "Terminal service binding was already removed", error);
            }
            serviceBound = false;
        }
        shellService = null;
    }

    private void attachServiceSession() {
        if (shellService == null || terminalView == null) return;
        TerminalSession session = shellService.getTerminalSession();
        if (session == null) {
            terminalSession = null;
            String startupError = shellService.getStartupError();
            if (startupError != null) {
                showStatus("Could not start the shell: " + startupError);
                restartButton.setVisibility(View.VISIBLE);
            } else {
                showStatus("Starting shell…");
                restartButton.setVisibility(View.GONE);
            }
            stopShellButton.setVisibility(View.GONE);
            return;
        }

        if (terminalSession != session) {
            terminalSession = session;
            terminalView.attachSession(session);
            terminalView.requestFocus();
            terminalView.post(this::showTerminalKeyboard);
        } else {
            terminalView.onScreenUpdated();
        }
        if (session.isRunning()) {
            status.setVisibility(View.GONE);
            restartButton.setVisibility(View.GONE);
            stopShellButton.setVisibility(View.VISIBLE);
        } else {
            showStatus("Shell exited · code " + session.getExitStatus());
            restartButton.setVisibility(View.VISIBLE);
            stopShellButton.setVisibility(View.GONE);
        }
    }

    private void showStatus(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void startShell() {
        showStatus("Starting shell…");
        restartButton.setVisibility(View.GONE);
        startAndBindShellService();
        if (shellService != null) {
            shellService.startShellFromVisibleActivity();
            attachServiceSession();
        }
    }

    private void stopShell() {
        if (shellService == null) return;
        boolean wasRunning = shellService.isSessionRunning();
        shellService.stopShell();
        showStatus("Stopping shell/SSH…");
        stopShellButton.setVisibility(View.GONE);
        restartButton.setVisibility(wasRunning ? View.GONE : View.VISIBLE);
    }

    private void updateNotificationButtonVisibility() {
        if (notificationButton == null) return;
        boolean needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        notificationButton.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            updateNotificationButtonVisibility();
            return;
        }

        boolean alreadyAsked = getPreferences(MODE_PRIVATE).getBoolean(
                NOTIFICATION_PERMISSION_REQUESTED, false);
        if (alreadyAsked) {
            Intent settingsIntent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            try {
                startActivity(settingsIntent);
            } catch (android.content.ActivityNotFoundException error) {
                Intent appSettingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(appSettingsIntent);
            }
            return;
        }

        getPreferences(MODE_PRIVATE).edit()
                .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
                .apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return;
        updateNotificationButtonVisibility();
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Terminal status notifications enabled.", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this,
                    "The terminal is still running; Android may show only the service in Task Manager.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void addKeyButton(LinearLayout row, String label, String sequence) {
        Button button = makeButton(label);
        button.setOnClickListener(view -> writeToShell(sequence));
        row.addView(button, keyParams());
    }

    private LinearLayout.LayoutParams keyParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        params.rightMargin = dp(5);
        return params;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(COLOR_ACCENT);
        button.setMinWidth(dp(48));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(25, 38, 30)));
        return button;
    }

    private void updateToggleButton(Button button, boolean enabled) {
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                enabled ? COLOR_ACCENT : Color.rgb(25, 38, 30)));
        button.setTextColor(enabled ? COLOR_BG : COLOR_ACCENT);
    }

    private void consumeControlModifier() {
        if (modifierState.consumeControlKey() && ctrlButton != null) {
            updateToggleButton(ctrlButton, false);
        }
    }

    private void writeToShell(String value) {
        if (terminalSession == null || !terminalSession.isRunning()) return;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        terminalSession.write(bytes, 0, bytes.length);
        terminalView.requestFocus();
    }

    private void setTerminalFontSize(int requestedSizeSp) {
        fontSizeSp = TerminalFontSize.clamp(requestedSizeSp);
        getPreferences(MODE_PRIVATE).edit().putInt(FONT_SIZE_PREFERENCE, fontSizeSp).apply();
        if (fontSizeButton != null) {
            fontSizeButton.setText(getString(R.string.terminal_font_size, fontSizeSp));
            fontSizeButton.setContentDescription(getString(
                    R.string.terminal_font_size_content_description, fontSizeSp));
        }
        if (terminalView != null) {
            terminalView.setTextSize(TerminalFontSize.toPixels(fontSizeSp,
                    getResources().getDisplayMetrics().scaledDensity));
            terminalView.requestFocus();
        }
    }

    private void showTerminalKeyboard() {
        if (terminalView == null) return;
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.requestFocus();
        terminalView.post(() -> {
            if (destroyed || terminalView == null || !terminalView.isAttachedToWindow()) return;
            InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) manager.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        detachFromShellService();
        super.onDestroy();
    }

    private final class ShellTerminalViewClient implements TerminalViewClient {
        private boolean altKeyDown;

        @Override
        public float onScale(float scale) {
            if (!Float.isFinite(scale) || scale <= 0f) return 1.0f;
            int scaledSizeSp = TerminalFontSize.scale(fontSizeSp, scale);
            if (scaledSizeSp != fontSizeSp) {
                setTerminalFontSize(scaledSizeSp);
                return 1.0f;
            }
            if ((fontSizeSp == TerminalFontSize.MAX_SP && scale > 1f)
                    || (fontSizeSp == TerminalFontSize.MIN_SP && scale < 1f)) return 1.0f;
            return scale;
        }

        @Override
        public void onSingleTapUp(MotionEvent event) {
            showTerminalKeyboard();
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
            return false;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return false;
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) { }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) {
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            consumeControlModifier();
            return false;
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
            return false;
        }

        @Override
        public boolean readControlKey() {
            return modifierState.isControlKeyDown();
        }

        @Override
        public boolean readAltKey() {
            return altKeyDown;
        }

        @Override
        public boolean readShiftKey() {
            return false;
        }

        @Override
        public boolean readFnKey() {
            return false;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            consumeControlModifier();
            return false;
        }

        @Override
        public void onEmulatorSet() { }

        @Override
        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(tag, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(tag, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception error) {
            Log.e(tag, message, error);
        }

        @Override
        public void logStackTrace(String tag, Exception error) {
            Log.e(tag, "TerminalView error", error);
        }
    }

    private final class ShellTerminalSessionClient implements TerminalSessionClient {
        @Override
        public void onTextChanged(TerminalSession changedSession) {
            if (changedSession == terminalSession) terminalView.onScreenUpdated();
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
            if (changedSession == terminalSession && changedSession.getTitle() != null) {
                runOnUiThread(() -> showStatus(changedSession.getTitle()));
            }
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            if (finishedSession != terminalSession) return;
            if (!destroyed) {
                runOnUiThread(() -> {
                    showStatus("Shell exited · code " + finishedSession.getExitStatus());
                    restartButton.setVisibility(View.VISIBLE);
                    stopShellButton.setVisibility(View.GONE);
                });
            }
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("terminal", text));
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
            ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) return;
            ClipData clip = manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0 || session.getEmulator() == null) return;
            CharSequence text = clip.getItemAt(0).coerceToText(TermuxShellActivity.this);
            if (text != null) session.getEmulator().paste(text.toString());
        }

        @Override
        public void onBell(TerminalSession session) { }

        @Override
        public void onColorsChanged(TerminalSession changedSession) {
            if (changedSession == terminalSession) terminalView.onScreenUpdated();
        }

        @Override
        public void onTerminalCursorStateChange(boolean enabled) {
            if (terminalView != null) terminalView.setTerminalCursorBlinkerState(enabled, true);
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return null;
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
            Log.i(tag, message);
        }

        @Override
        public void logDebug(String tag, String message) {
            Log.d(tag, message);
        }

        @Override
        public void logVerbose(String tag, String message) {
            Log.v(tag, message);
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception error) {
            Log.e(tag, message, error);
        }

        @Override
        public void logStackTrace(String tag, Exception error) {
            Log.e(tag, "Terminal session error", error);
        }
    }
}
