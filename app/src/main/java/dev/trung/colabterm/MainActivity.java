package dev.trung.colabterm;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_FILE = 41;
    static final Object PYTHON_RUN_LOCK = new Object();
    private static final int COLOR_BG = Color.rgb(12, 18, 15);
    private static final int COLOR_PANEL = Color.rgb(20, 29, 24);
    private static final int COLOR_TEXT = Color.rgb(218, 232, 222);
    private static final int COLOR_MUTED = Color.rgb(139, 163, 146);
    private static final int COLOR_ACCENT = Color.rgb(141, 224, 177);

    static {
        System.loadLibrary("colab_termux_core");
    }

    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private final Object inputLock = new Object();
    private volatile boolean commandRunning;
    private volatile boolean waitingForInput;
    private volatile boolean destroyed;
    private CountDownLatch inputLatch;
    private String inputValue;

    private LinearLayout root;
    private TextView terminal;
    private TextView status;
    private EditText commandField;
    private Button sendButton;
    private ScrollView transcriptScroll;

    private static native String nativeNormalizeCommand(String input);
    private static native String nativeNormalizeColabArguments(String[] arguments);

    static String normalizeColabArguments(String[] arguments) {
        return nativeNormalizeColabArguments(arguments);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppDiagnostics.mark(this, "MainActivity.onCreate entered");
        AndroidTokenStore.initialize(this);
        buildUi();
        AppDiagnostics.mark(this, "Colab CLI interface built");
        appendTerminalText("ColabTerm 0.1.0 — terminal cho Google Colab CLI\n");
        appendTerminalText("Chỉ nhận lệnh `colab`; không phải shell Linux tổng quát.\n");
        appendTerminalText("Thử: colab --help | colab version | colab --auth oauth2 sessions\n\n");
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setPadding(dp(14), dp(8), dp(14), dp(10));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(4), dp(8), dp(4), dp(12));
        TextView title = new TextView(this);
        title.setText("ColabTerm");
        title.setTextColor(COLOR_ACCENT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);
        status = new TextView(this);
        status.setText("Rust core · Python/Colab CLI · Android Keystore");
        status.setTextColor(COLOR_MUTED);
        status.setTextSize(12);
        header.addView(status);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        transcriptScroll = new ScrollView(this);
        transcriptScroll.setFillViewport(true);
        transcriptScroll.setBackgroundColor(COLOR_PANEL);
        transcriptScroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        terminal = new TextView(this);
        terminal.setTextColor(COLOR_TEXT);
        terminal.setTextSize(14);
        terminal.setTypeface(Typeface.MONOSPACE);
        terminal.setTextIsSelectable(true);
        terminal.setAutoLinkMask(Linkify.WEB_URLS);
        terminal.setLinkTextColor(COLOR_ACCENT);
        terminal.setMovementMethod(LinkMovementMethod.getInstance());
        terminal.setLineSpacing(dp(2), 1.0f);
        transcriptScroll.addView(terminal);
        root.addView(transcriptScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        HorizontalScrollView shortcutsScroll = new HorizontalScrollView(this);
        shortcutsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout shortcuts = new LinearLayout(this);
        shortcuts.setOrientation(LinearLayout.HORIZONTAL);
        addShortcut(shortcuts, "help", "colab --help");
        addShortcut(shortcuts, "version", "colab version");
        addShortcut(shortcuts, "sessions", "colab --auth oauth2 sessions");
        addShortcut(shortcuts, "Chọn tệp", null);
        shortcutsScroll.addView(shortcuts);
        root.addView(shortcutsScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(0, dp(8), 0, 0);
        commandField = new EditText(this);
        commandField.setSingleLine(true);
        commandField.setTextColor(COLOR_TEXT);
        commandField.setHintTextColor(COLOR_MUTED);
        commandField.setHint("colab <lệnh>");
        commandField.setTextSize(14);
        commandField.setTypeface(Typeface.MONOSPACE);
        commandField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        commandField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        commandField.setPadding(dp(10), dp(9), dp(10), dp(9));
        commandField.setBackgroundColor(COLOR_PANEL);
        commandField.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                submitInput();
                return true;
            }
            return false;
        });
        inputRow.addView(commandField, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        sendButton = new Button(this);
        sendButton.setText("Gửi");
        sendButton.setTextColor(COLOR_BG);
        sendButton.setAllCaps(false);
        sendButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        sendButton.setOnClickListener(view -> submitInput());
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sendParams.leftMargin = dp(8);
        inputRow.addView(sendButton, sendParams);
        root.addView(inputRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        WindowInsetsHelper.apply(root);
    }

    private void addShortcut(LinearLayout row, String label, String command) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setTextColor(COLOR_ACCENT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(COLOR_PANEL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        params.rightMargin = dp(6);
        row.addView(button, params);
        if (command == null) {
            button.setOnClickListener(view -> openFilePicker());
        } else {
            button.setOnClickListener(view -> {
                if (commandRunning) {
                    appendTerminalText("\n[ColabTerm] Đang chạy lệnh khác.\n");
                    return;
                }
                executeCommand(command);
            });
        }
    }

    private void submitInput() {
        String value = commandField.getText().toString();
        if (waitingForInput) {
            CountDownLatch latch;
            synchronized (inputLock) {
                inputValue = value;
                latch = inputLatch;
            }
            commandField.setText("");
            appendTerminalText("\n");
            if (latch != null) latch.countDown();
            return;
        }
        if (commandRunning) {
            appendTerminalText("\n[ColabTerm] Lệnh đang chạy; chờ CLI yêu cầu dữ liệu hoặc hoàn tất.\n");
            return;
        }
        if (value.trim().isEmpty()) return;
        commandField.setText("");
        hideKeyboard();
        executeCommand(value.trim());
    }

    private void executeCommand(String line) {
        final String normalized;
        try {
            normalized = nativeNormalizeCommand(line);
        } catch (Throwable error) {
            appendTerminalText("\n[ColabTerm] Không thể phân tích lệnh Rust: " + error.getMessage() + "\n");
            return;
        }
        if (normalized == null) {
            appendTerminalText("\n[ColabTerm] Parser Rust không trả kết quả.\n");
            return;
        }
        if (normalized.startsWith("!")) {
            appendTerminalText("$ " + line + "\n" + normalized.substring(1) + "\n");
            return;
        }

        final String argsText = normalized;
        appendTerminalText("$ " + line + "\n");
        commandRunning = true;
        setBusyUi(true, "Đang chạy Colab CLI…");
        commandExecutor.execute(() -> {
            try {
                synchronized (PYTHON_RUN_LOCK) {
                    Python python = Python.getInstance();
                    PyObject os = python.getModule("os");
                    os.get("environ").callAttr("__setitem__", "HOME", getFilesDir().getAbsolutePath());
                    os.get("environ").callAttr("__setitem__", "COLABTERM_ANDROID", "1");
                    PyObject runner = python.getModule("colab_runner");
                    PyObject exitCode = runner.callAttr("run_colab", argsText, this);
                    appendTerminalText("\n[exit " + exitCode.toString() + "]\n");
                }
            } catch (Throwable error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                appendTerminalText("\n[ColabTerm] " + message + "\n");
            } finally {
                commandRunning = false;
                setBusyUi(false, "Sẵn sàng · lệnh chỉ chạy khi bạn gửi");
            }
        });
    }

    public void appendTerminalText(String value) {
        if (value == null || value.isEmpty() || destroyed) return;
        runOnUiThread(() -> {
            if (destroyed) return;
            terminal.append(value);
            int extra = terminal.length() - 80000;
            if (extra > 0) {
                CharSequence current = terminal.getText();
                terminal.setText(current.subSequence(extra, current.length()));
            }
            transcriptScroll.post(() -> transcriptScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    public void appendTerminalBytes(byte[] value) {
        if (value == null || value.length == 0) return;
        appendTerminalText(new String(value, StandardCharsets.UTF_8));
    }

    public String readLineForPython(String prompt) {
        final CountDownLatch latch = new CountDownLatch(1);
        synchronized (inputLock) {
            inputLatch = latch;
            inputValue = null;
            waitingForInput = true;
        }
        runOnUiThread(() -> {
            if (prompt != null && !prompt.isEmpty()) appendTerminalText(prompt);
            status.setText("CLI đang chờ bạn nhập dữ liệu trong ô bên dưới");
            commandField.setEnabled(true);
            sendButton.setEnabled(true);
            commandField.setHint("Nhập rồi bấm Gửi");
            commandField.requestFocus();
        });

        String result = "";
        try {
            if (!latch.await(5, TimeUnit.MINUTES)) {
                throw new IllegalStateException("Hết thời gian chờ dữ liệu nhập trong terminal.");
            }
            synchronized (inputLock) {
                result = inputValue == null ? "" : inputValue;
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lệnh bị gián đoạn khi chờ dữ liệu nhập.", e);
        } finally {
            synchronized (inputLock) {
                waitingForInput = false;
                inputLatch = null;
                inputValue = null;
            }
            setBusyUi(commandRunning, commandRunning ? "Đang chạy Colab CLI…" : "Sẵn sàng");
        }
    }

    private void setBusyUi(boolean busy, String message) {
        runOnUiThread(() -> {
            if (destroyed) return;
            status.setText(message);
            boolean canType = !busy || waitingForInput;
            commandField.setEnabled(canType);
            sendButton.setEnabled(canType);
            commandField.setHint(waitingForInput ? "Nhập rồi bấm Gửi" : "colab <lệnh>");
        });
    }

    private void openFilePicker() {
        if (commandRunning) {
            appendTerminalText("\n[ColabTerm] Chờ lệnh hiện tại hoàn tất trước khi chọn tệp.\n");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_FILE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            String displayName = getDisplayName(uri);
            String safeName = displayName.replaceAll("[^A-Za-z0-9._-]", "_");
            if (safeName.isEmpty()) safeName = "upload.bin";
            File uploadDir = new File(getFilesDir(), "uploads");
            if (!uploadDir.exists() && !uploadDir.mkdirs()) throw new IllegalStateException("Không thể tạo thư mục uploads.");
            File target = new File(uploadDir, safeName);
            try (InputStream input = getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(target)) {
                if (input == null) throw new IllegalStateException("Không mở được tệp đã chọn.");
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
            String quoted = target.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"");
            String remoteName = safeName.replace("\"", "");
            String suggestion = "colab upload \"" + quoted + "\" /content/" + remoteName;
            commandField.setText(suggestion);
            commandField.setSelection(commandField.length());
            appendTerminalText("\n[ColabTerm] Đã sao chép tệp vào vùng riêng của app: " + target.getAbsolutePath() + "\n");
        } catch (Exception e) {
            appendTerminalText("\n[ColabTerm] Không thể nhập tệp: " + e.getMessage() + "\n");
        }
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        String segment = uri.getLastPathSegment();
        return segment == null ? "upload.bin" : segment;
    }

    private void hideKeyboard() {
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(commandField.getWindowToken(), 0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        synchronized (inputLock) {
            if (inputLatch != null) {
                inputValue = "";
                inputLatch.countDown();
            }
        }
        commandExecutor.shutdownNow();
        super.onDestroy();
    }
}
