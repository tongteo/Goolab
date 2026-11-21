package dev.trung.colabterm;

/** Tracks the on-screen control modifier, which applies to one terminal key at a time. */
final class TerminalModifierState {
    private boolean controlKeyDown;

    void toggleControlKey() {
        controlKeyDown = !controlKeyDown;
    }

    boolean isControlKeyDown() {
        return controlKeyDown;
    }

    boolean consumeControlKey() {
        boolean wasDown = controlKeyDown;
        controlKeyDown = false;
        return wasDown;
    }
}
