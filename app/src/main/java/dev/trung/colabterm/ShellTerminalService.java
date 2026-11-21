package dev.trung.colabterm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/** Owns the PTY and authenticated Colab bridge independently of the terminal Activity. */
public final class ShellTerminalService extends Service implements TerminalSessionClient {
    public static final String ACTION_STOP_SHELL = "dev.trung.colabterm.action.STOP_SHELL";

    private static final String LOG_TAG = "ShellTerminalService";
    private static final String NOTIFICATION_CHANNEL_ID = "terminal_session";
    private static final int NOTIFICATION_ID = 4201;

    private final LocalBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile WeakReference<TerminalSessionClient> uiClient = new WeakReference<>(null);

    private TerminalSession terminalSession;
    private ColabBridgeServer bridgeServer;
    private String startupError;
    private boolean foreground;
    private boolean destroyed;

    public final class LocalBinder extends Binder {
        public ShellTerminalService getService() {
            return ShellTerminalService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppDiagnostics.mark(this, "ShellTerminalService.onCreate entered");
        createNotificationChannel();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SHELL.equals(intent.getAction())) {
            stopShell();
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        try {
            promoteToForeground();
            startShellIfNeeded();
        } catch (Throwable error) {
            handleStartupFailure(error);
        }
        return START_NOT_STICKY;
    }

    /** Called by the visible Activity before it binds or requests a new shell. */
    public void startShellFromVisibleActivity() {
        try {
            promoteToForeground();
            startShellIfNeeded();
        } catch (Throwable error) {
            handleStartupFailure(error);
        }
    }

    public synchronized TerminalSession getTerminalSession() {
        return terminalSession;
    }

    public synchronized String getStartupError() {
        return startupError;
    }

    public synchronized boolean isSessionRunning() {
        return terminalSession != null && terminalSession.isRunning();
    }

    public void setUiClient(TerminalSessionClient client) {
        uiClient = new WeakReference<>(client);
    }

    public void clearUiClient(TerminalSessionClient client) {
        WeakReference<TerminalSessionClient> reference = uiClient;
        if (reference.get() == client) {
            uiClient = new WeakReference<>(null);
        }
    }

    /** Stop only the local shell/SSH process and bridge; never stop the Colab runtime. */
    public void stopShell() {
        TerminalSession current;
        synchronized (this) {
            current = terminalSession;
            startupError = null;
        }

        if (current != null && current.isRunning()) {
            current.finishIfRunning();
        } else {
            synchronized (this) {
                if (terminalSession == current) terminalSession = null;
            }
        }
        closeBridgeServer();
        stopForegroundNotification();
        stopSelf();
    }

    private void startShellIfNeeded() {
        synchronized (this) {
            if (terminalSession != null && terminalSession.isRunning()) return;
            terminalSession = null;
            startupError = null;
        }

        try {
            AppDiagnostics.mark(this, "Preparing private shell directories");
            File home = getFilesDir();
            File temp = new File(getCacheDir(), "shell-tmp");
            if (!temp.exists() && !temp.mkdirs()) {
                throw new IllegalStateException("Không tạo được thư mục tạm riêng của app.");
            }
            installShellStartupConfig(new File(
                    ShellConfig.startupFilePath(temp.getAbsolutePath())));

            File binaryDirectory = new File(home, "bin");
            if (!binaryDirectory.exists() && !binaryDirectory.mkdirs()) {
                throw new IllegalStateException("Không tạo được thư mục lệnh riêng của app.");
            }
            installColabHelper(new File(binaryDirectory, "colab"));

            if (bridgeServer == null) {
                AppDiagnostics.mark(this, "Creating Colab bridge server");
                bridgeServer = new ColabBridgeServer(this);
                bridgeServer.start();
                AppDiagnostics.mark(this, "Colab bridge server started");
            }

            TerminalSession session = new TerminalSession(
                    ShellConfig.SHELL_PATH,
                    home.getAbsolutePath(),
                    ShellConfig.arguments(),
                    ShellConfig.environment(home.getAbsolutePath(), temp.getAbsolutePath(),
                            binaryDirectory.getAbsolutePath(), bridgeServer.getPort(), bridgeServer.getToken()),
                    2000,
                    this);
            synchronized (this) {
                terminalSession = session;
                startupError = null;
            }
            AppDiagnostics.mark(this, "Terminal session started in foreground service");
        } catch (Throwable error) {
            handleStartupFailure(error);
        }
    }

    private void handleStartupFailure(Throwable error) {
        AppDiagnostics.mark(this, "Shell startup handled failure: " + error.getClass().getSimpleName());
        AppDiagnostics.saveHandledFailure(this, "Handled shell startup failure", error);
        String message = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        synchronized (this) {
            startupError = message;
            terminalSession = null;
        }
        closeBridgeServer();
        stopForegroundNotification();
        stopSelf();
    }

    private void promoteToForeground() {
        if (foreground) return;
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    private void stopForegroundNotification() {
        if (!foreground) return;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Terminal đang chạy",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Trạng thái terminal/SSH ColabTerm khi ứng dụng ở nền.");
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        Intent openIntent = new Intent(this, TermuxShellActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openApp = PendingIntent.getActivity(this, 1, openIntent, pendingFlags);

        Intent stopIntent = new Intent(this, ShellTerminalService.class);
        stopIntent.setAction(ACTION_STOP_SHELL);
        PendingIntent stopShell = PendingIntent.getService(this, 2, stopIntent, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("ColabTerm · Terminal đang hoạt động")
                .setContentText("Shell/SSH tiếp tục khi ứng dụng ở nền")
                .setContentIntent(openApp)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(android.R.drawable.ic_media_pause, "Dừng shell", stopShell)
                .build();
    }

    private void installShellStartupConfig(File target) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(target, false);
             OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8)) {
            writer.write(ShellConfig.startupFileContent());
        }
    }

    private void installColabHelper(File target) throws IOException {
        File nativeHelper = new File(ShellConfig.colabHelperPath(
                getApplicationInfo().nativeLibraryDir));
        if (!nativeHelper.isFile() || !nativeHelper.canExecute()) {
            throw new IOException("Không tìm thấy helper colab executable trong nativeLibraryDir.");
        }

        try {
            Os.remove(target.getAbsolutePath());
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) {
                throw new IOException("Không thể thay helper colab cũ.", error);
            }
        }
        try {
            Os.symlink(nativeHelper.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException error) {
            throw new IOException("Không thể liên kết helper colab trong thư mục lệnh.", error);
        }
        if (!target.canExecute()) {
            throw new IOException("Helper colab không có quyền thực thi sau khi cài.");
        }
    }

    private void closeBridgeServer() {
        ColabBridgeServer server = bridgeServer;
        bridgeServer = null;
        if (server != null) server.close();
    }

    private TerminalSessionClient currentUiClient() {
        return uiClient.get();
    }

    @Override
    public void onTextChanged(TerminalSession changedSession) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.onTextChanged(changedSession);
    }

    @Override
    public void onTitleChanged(TerminalSession changedSession) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.onTitleChanged(changedSession);
    }

    @Override
    public void onSessionFinished(TerminalSession finishedSession) {
        mainHandler.post(() -> {
            synchronized (ShellTerminalService.this) {
                if (destroyed || finishedSession != terminalSession) return;
                terminalSession = null;
            }
            TerminalSessionClient client = currentUiClient();
            if (client != null) client.onSessionFinished(finishedSession);
            closeBridgeServer();
            stopForegroundNotification();
            stopSelf();
        });
    }

    @Override
    public void onCopyTextToClipboard(TerminalSession session, String text) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.onCopyTextToClipboard(session, text);
    }

    @Override
    public void onPasteTextFromClipboard(TerminalSession session) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.onPasteTextFromClipboard(session);
    }

    @Override
    public void onBell(TerminalSession session) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.onBell(session);
    }

    @Override
    public void onColorsChanged(TerminalSession changedSession) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.onColorsChanged(changedSession);
    }

    @Override
    public void onTerminalCursorStateChange(boolean enabled) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.onTerminalCursorStateChange(enabled);
    }

    @Override
    public Integer getTerminalCursorStyle() {
        TerminalSessionClient client = currentUiClient();
        return client == null ? null : client.getTerminalCursorStyle();
    }

    @Override
    public void logError(String tag, String message) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.logError(tag, message);
        else Log.e(tag, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.logWarn(tag, message);
        else Log.w(tag, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.logInfo(tag, message);
        else Log.i(tag, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.logDebug(tag, message);
        else Log.d(tag, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.logVerbose(tag, message);
        else Log.v(tag, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception error) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.logStackTraceWithMessage(tag, message, error);
        else Log.e(tag, message, error);
    }

    @Override
    public void logStackTrace(String tag, Exception error) {
        TerminalSessionClient client = currentUiClient();
        if (client != null) client.logStackTrace(tag, error);
        else Log.e(tag, "Terminal session error", error);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        synchronized (this) {
            TerminalSession current = terminalSession;
            terminalSession = null;
            if (current != null) current.finishIfRunning();
        }
        closeBridgeServer();
        stopForegroundNotification();
        super.onDestroy();
    }
}
