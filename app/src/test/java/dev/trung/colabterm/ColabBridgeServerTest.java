package dev.trung.colabterm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

public class ColabBridgeServerTest {
    @Test
    public void interactiveBridgeReadsBinaryInputAndResizeEvents() {
        byte[] events = new byte[]{
                'I', 0, 0, 0, 4, 0, 0x1b, 0x7f, (byte) 0xff,
                'R', 0, 100, 0, 30,
                'C'
        };
        ColabBridgeServer.PythonBridgeIO bridge = new ColabBridgeServer.PythonBridgeIO(
                new DataInputStream(new ByteArrayInputStream(events)),
                new DataOutputStream(new ByteArrayOutputStream()),
                true, 80, 24);

        assertArrayEquals(new int[]{80, 24}, bridge.getTerminalSizeForPython());
        assertEquals("I", bridge.readTerminalEventTypeForPython());
        assertArrayEquals(new byte[]{0, 0x1b, 0x7f, (byte) 0xff},
                bridge.readTerminalInputBytesForPython());
        assertEquals("R", bridge.readTerminalEventTypeForPython());
        assertArrayEquals(new int[]{100, 30}, bridge.getTerminalSizeForPython());
        assertEquals("C", bridge.readTerminalEventTypeForPython());
    }

    @Test
    public void interactiveReadLineRetainsBytesAfterFirstNewline() {
        byte[] events = new byte[]{'I', 0, 0, 0, 5, 'a', 'b', '\n', 'c', '\n'};
        ColabBridgeServer.PythonBridgeIO bridge = new ColabBridgeServer.PythonBridgeIO(
                new DataInputStream(new ByteArrayInputStream(events)),
                new DataOutputStream(new ByteArrayOutputStream()),
                true, 80, 24);

        assertEquals("ab", bridge.readLineForPython(""));
        assertEquals("c", bridge.readLineForPython(""));
    }

    @Test
    public void interactiveReadLineAcceptsCarriageReturnAndDistinguishesEof() {
        byte[] events = new byte[]{
                'I', 0, 0, 0, 2, 'a', '\r',
                'I', 0, 0, 0, 2, 'b', '\r',
                'C'
        };
        ColabBridgeServer.PythonBridgeIO bridge = new ColabBridgeServer.PythonBridgeIO(
                new DataInputStream(new ByteArrayInputStream(events)),
                new DataOutputStream(new ByteArrayOutputStream()),
                true, 80, 24);

        assertEquals("a", bridge.readLineForPython(""));
        assertEquals("b", bridge.readLineForPython(""));
        assertNull(bridge.readLineForPython(""));
    }

    @Test
    public void stdinStreamReadsMultipleLinesAndPreservesBoundedRemainder() {
        byte[] input = "alpha\nbeta\n".getBytes(StandardCharsets.UTF_8);
        ColabBridgeServer.PythonBridgeIO bridge = new ColabBridgeServer.PythonBridgeIO(
                new DataInputStream(new ByteArrayInputStream(input)),
                new DataOutputStream(new ByteArrayOutputStream()),
                false, 80, 24);

        assertArrayEquals("alpha".getBytes(StandardCharsets.UTF_8),
                bridge.readTerminalBytesForPython(5));
        assertArrayEquals("\nbeta\n".getBytes(StandardCharsets.UTF_8),
                bridge.readTerminalBytesForPython(-1));
        assertNull(bridge.readLineForPython(""));
    }

    @Test
    public void rawOutputIsForwardedWithoutTextConversion() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ColabBridgeServer.PythonBridgeIO bridge = new ColabBridgeServer.PythonBridgeIO(
                new DataInputStream(new ByteArrayInputStream(new byte[0])),
                new DataOutputStream(bytes), true, 80, 24);

        bridge.appendTerminalBytes(new byte[]{0, 0x1b, (byte) 0xff});

        assertArrayEquals(new byte[]{'O', 0, 0, 0, 3, 0, 0x1b, (byte) 0xff}, bytes.toByteArray());
    }
}
