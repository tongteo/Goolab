package dev.trung.colabterm;

final class TerminalFontSize {
    static final int DEFAULT_SP = 16;
    static final int MIN_SP = 8;
    static final int MAX_SP = 32;

    private TerminalFontSize() { }

    static int clamp(int fontSizeSp) {
        return Math.max(MIN_SP, Math.min(MAX_SP, fontSizeSp));
    }

    static int adjust(int currentSp, int changeSp) {
        long adjusted = (long) currentSp + changeSp;
        return (int) Math.max(MIN_SP, Math.min(MAX_SP, adjusted));
    }

    static int scale(int currentSp, float scaleFactor) {
        int current = clamp(currentSp);
        if (!Float.isFinite(scaleFactor) || scaleFactor <= 0f) return current;
        return clamp(Math.round(current * scaleFactor));
    }

    static int toPixels(int fontSizeSp, float scaledDensity) {
        if (!Float.isFinite(scaledDensity) || scaledDensity <= 0f) {
            throw new IllegalArgumentException("scaledDensity must be a positive finite value");
        }
        return Math.max(1, Math.round(clamp(fontSizeSp) * scaledDensity));
    }
}
