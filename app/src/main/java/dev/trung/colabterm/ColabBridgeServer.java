package dev.trung.colabterm;

import android.content.Context;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ColabBridgeServer implements AutoCloseable {
    private static final String LOG_TAG = "ColabBridge";
    private static final byte[] MAGIC = new byte[]{'C', 'T', 'C', '1'};
    private static final byte[] INTERACTIVE_MAGIC = new byte[]{'C', 'T', 'C', '2'};
    private static final int TOKEN_LENGTH = 64;
    private static final int MAX_ARGUMENTS = 256;
    private static final int MAX_ARGUMENT_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_INPUT_LINE_BYTES = 1024 * 1024;
    private static final int MAX_INPUT_FRAME_BYTES = 1024 * 1024;
    private static final int OUTPUT_CHUNK_BYTES = 32 * 1024;

    private final Context context;
    private final ServerSocket serverSocket;
    private final String token;
    private final ExecutorService requestExecutor = Executors.newSingleThreadExecutor();
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Thread acceptThread;

    public ColabBridgeServer(Context context) throws IOException {
        this.context = context.getApplicationContext();
        this.token = createToken();
        this.serverSocket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public String getToken() {
        return token;
    }

    public void start() {
        if (acceptThread != null) return;
        acceptThread = new Thread(this::acceptLoop, "ColabBridgeAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (!closed.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (!socket.getInetAddress().isLoopbackAddress()) {
                    socket.close();
                    continue;
                }
                activeSockets.add(socket);
                requestExecutor.execute(() -> {
                    try (Socket client = socket) {
                        handleClient(client);
                    } catch (Exception error) {
                        if (!closed.get()) Log.w(LOG_TAG, "Bridge request ended: " + error.getMessage());
                    } finally {
                        activeSockets.remove(socket);
                    }
                });
            } catch (SocketException error) {
                if (!closed.get()) Log.e(LOG_TAG, "Bridge listener failed", error);
                return;
            } catch (IOException error) {
                if (!closed.get()) Log.e(LOG_TAG, "Bridge accept failed", error);
            }
        }
    }

    private void handleClient(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());

        byte[] magic = new byte[MAGIC.length];
        input.readFully(magic);
        boolean interactive;
        if (Arrays.equals(magic, MAGIC)) {
            interactive = false;
        } else if (Arrays.equals(magic, INTERACTIVE_MAGIC)) {
            interactive = true;
        } else {
            throw new IOException("Invalid bridge protocol header");
        }

        byte[] receivedToken = new byte[TOKEN_LENGTH];
        input.readFully(receivedToken);
        if (!MessageDigest.isEqual(receivedToken, token.getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("Bridge authentication failed");
        }

        int argumentCount = input.readInt();
        if (argumentCount < 0 || argumentCount > MAX_ARGUMENTS) {
            throw new IOException("Invalid argument count");
        }
        String[] arguments = new String[argumentCount];
        int totalBytes = 0;
        for (int index = 0; index < argumentCount; index++) {
            int length = input.readInt();
            if (length < 0 || length > MAX_ARGUMENT_BYTES || totalBytes + length > MAX_REQUEST_BYTES) {
                throw new IOException("Command arguments exceed the allowed size");
            }
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            arguments[index] = new String(bytes, StandardCharsets.UTF_8);
            totalBytes += length;
        }
        int terminalColumns = 80;
        int terminalRows = 24;
        if (interactive) {
            terminalColumns = input.readUnsignedShort();
            terminalRows = input.readUnsignedShort();
            if (terminalColumns == 0 || terminalRows == 0) {
                throw new IOException("Invalid initial terminal size");
            }
        }
        socket.setSoTimeout(0);

        String normalized = MainActivity.normalizeColabArguments(arguments);
        if (normalized == null) {
            sendOutput(output, "[ColabTerm] No result was returned by the Rust parser.\n");
            sendExit(output, 2);
            return;
        }
        if (normalized.startsWith("!")) {
            sendOutput(output, "[ColabTerm] " + normalized.substring(1) + "\n");
            sendExit(output, 2);
            return;
        }

        PythonBridgeIO bridge = new PythonBridgeIO(
                input, output, interactive, terminalColumns, terminalRows);
        try {
            synchronized (MainActivity.PYTHON_RUN_LOCK) {
                Python python = Python.getInstance();
                PyObject os = python.getModule("os");
                os.get("environ").callAttr("__setitem__", "HOME", context.getFilesDir().getAbsolutePath());
                os.get("environ").callAttr("__setitem__", "COLABTERM_ANDROID", "1");
                PyObject runner = python.getModule("colab_runner");
                PyObject exitCode = runner.callAttr("run_colab", normalized, bridge);
                sendExit(output, Integer.parseInt(exitCode.toString()));
            }
        } catch (Throwable error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            sendOutput(output, "\n[ColabTerm] " + message + "\n");
            sendExit(output, 1);
        }
    }

    private static void sendOutput(DataOutputStream output, String value) throws IOException {
        sendOutputBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendOutputBytes(DataOutputStream output, byte[] bytes) throws IOException {
        for (int offset = 0; offset < bytes.length; offset += OUTPUT_CHUNK_BYTES) {
            int length = Math.min(OUTPUT_CHUNK_BYTES, bytes.length - offset);
            output.writeByte('O');
            output.writeInt(length);
            output.write(bytes, offset, length);
            output.flush();
        }
    }

    private static void sendExit(DataOutputStream output, int exitCode) throws IOException {
        output.writeByte('E');
        output.writeInt(exitCode);
        output.flush();
    }

    private static String createToken() {
        byte[] random = new byte[TOKEN_LENGTH / 2];
        new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder(TOKEN_LENGTH);
        for (byte item : random) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            serverSocket.close();
        } catch (IOException ignored) { }
        for (Socket socket : activeSockets) {
            try {
                socket.close();
            } catch (IOException ignored) { }
        }
        requestExecutor.shutdownNow();
    }

    public static final class PythonBridgeIO {
        private final DataInputStream input;
        private final DataOutputStream output;
        private final boolean interactive;
        private final Object outputLock = new Object();
        private volatile int terminalColumns;
        private volatile int terminalRows;
        private byte[] lastInputEventData = new byte[0];
        private byte[] pendingInput = new byte[0];
        private int pendingInputOffset;
        private boolean skipLineFeedAfterCarriageReturn;

        PythonBridgeIO(DataInputStream input, DataOutputStream output,
                       boolean interactive, int terminalColumns, int terminalRows) {
            this.input = input;
            this.output = output;
            this.interactive = interactive;
            this.terminalColumns = terminalColumns;
            this.terminalRows = terminalRows;
        }

        public void appendTerminalText(String value) {
            if (value == null || value.isEmpty()) return;
            synchronized (outputLock) {
                try {
                    sendOutput(output, value);
                } catch (IOException error) {
                    throw new IllegalStateException("Could not send Colab output to the terminal.", error);
                }
            }
        }

        public void appendTerminalBytes(byte[] value) {
            if (value == null || value.length == 0) return;
            synchronized (outputLock) {
                try {
                    sendOutputBytes(output, value);
                } catch (IOException error) {
                    throw new IllegalStateException("Could not send Colab output to the terminal.", error);
                }
            }
        }

        public String readLineForPython(String prompt) {
            if (prompt != null && !prompt.isEmpty()) appendTerminalText(prompt);
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            try {
                while (line.size() < MAX_INPUT_LINE_BYTES) {
                    int value = readBridgeByte();
                    if (value == -1) {
                        return line.size() == 0
                                ? null
                                : new String(line.toByteArray(), StandardCharsets.UTF_8);
                    }
                    if (value == '\n') break;
                    if (value == '\r') {
                        skipLineFeedAfterCarriageReturn = true;
                        break;
                    }
                    line.write(value);
                }
                return new String(line.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException error) {
                throw new IllegalStateException("Could not read input from the terminal.", error);
            }
        }

        public byte[] readTerminalBytesForPython(int maximumBytes) {
            if (maximumBytes == 0) return new byte[0];
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                while (maximumBytes < 0 || bytes.size() < maximumBytes) {
                    int value = readBridgeByte();
                    if (value == -1) break;
                    bytes.write(value);
                    if (maximumBytes >= 0 && bytes.size() >= maximumBytes) break;
                    if (maximumBytes >= 0 && input.available() == 0
                            && (!interactive || pendingInputOffset >= pendingInput.length)) {
                        break;
                    }
                }
                return bytes.toByteArray();
            } catch (IOException error) {
                throw new IllegalStateException("Could not read input from the terminal.", error);
            }
        }

        public String readTerminalEventTypeForPython() {
            if (!interactive) throw new IllegalStateException("Interactive bridge is not enabled.");
            try {
                int type = input.read();
                if (type == -1) return "C";
                switch (type) {
                    case 'I': {
                        int length = input.readInt();
                        if (length < 0 || length > MAX_INPUT_FRAME_BYTES) {
                            throw new IOException("Invalid terminal input frame length");
                        }
                        lastInputEventData = new byte[length];
                        input.readFully(lastInputEventData);
                        return "I";
                    }
                    case 'R':
                        updateTerminalSize(input.readUnsignedShort(), input.readUnsignedShort());
                        return "R";
                    case 'C':
                        lastInputEventData = new byte[0];
                        return "C";
                    default:
                        throw new IOException("Unknown terminal input frame");
                }
            } catch (IOException error) {
                throw new IllegalStateException("Could not read the terminal event.", error);
            }
        }

        public byte[] readTerminalInputBytesForPython() {
            byte[] result = lastInputEventData;
            lastInputEventData = new byte[0];
            return result;
        }

        public int[] getTerminalSizeForPython() {
            return new int[]{terminalColumns, terminalRows};
        }

        private int readBridgeByte() throws IOException {
            int value = interactive ? readInteractiveByte() : input.read();
            if (skipLineFeedAfterCarriageReturn) {
                skipLineFeedAfterCarriageReturn = false;
                if (value == '\n') {
                    value = interactive ? readInteractiveByte() : input.read();
                }
            }
            return value;
        }

        private int readInteractiveByte() throws IOException {
            while (true) {
                if (pendingInputOffset < pendingInput.length) {
                    return pendingInput[pendingInputOffset++] & 0xff;
                }
                pendingInput = new byte[0];
                pendingInputOffset = 0;
                int type = input.read();
                if (type == -1 || type == 'C') return -1;
                if (type == 'I') {
                    int length = input.readInt();
                    if (length < 0 || length > MAX_INPUT_FRAME_BYTES) {
                        throw new IOException("Invalid terminal input frame length");
                    }
                    pendingInput = new byte[length];
                    input.readFully(pendingInput);
                    continue;
                }
                if (type == 'R') {
                    updateTerminalSize(input.readUnsignedShort(), input.readUnsignedShort());
                    continue;
                }
                throw new IOException("Unknown terminal input frame");
            }
        }

        private void updateTerminalSize(int columns, int rows) throws IOException {
            if (columns <= 0 || rows <= 0) throw new IOException("Invalid terminal size frame");
            terminalColumns = columns;
            terminalRows = rows;
        }
    }
}
