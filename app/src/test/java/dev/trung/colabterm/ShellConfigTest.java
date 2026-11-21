package dev.trung.colabterm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellConfigTest {
    @Test
    public void launchesInteractiveAndroidSystemShell() {
        assertArrayEquals(new String[]{"/system/bin/sh", "-i"}, ShellConfig.arguments());
    }

    @Test
    public void exposesColabHelperAndAuthenticatedBridgeToPrivateShell() {
        String home = "/data/user/0/dev.trung.colabterm/files";
        String token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String[] environment = ShellConfig.environment(home, home + "/tmp", home + "/bin", 43123, token);

        assertTrue(contains(environment, "HOME=" + home));
        assertTrue(contains(environment, "TMPDIR=" + home + "/tmp"));
        assertTrue(contains(environment, "PATH=" + home + "/bin:/system/bin:/system/xbin"));
        assertTrue(contains(environment, "TERM=xterm-256color"));
        assertTrue(contains(environment, "ENV=" + ShellConfig.startupFilePath(home + "/tmp")));
        assertTrue(contains(environment, "COLABTERM_ANDROID=1"));
        assertTrue(contains(environment, "COLABTERM_BRIDGE_PORT=43123"));
        assertTrue(contains(environment, "COLABTERM_BRIDGE_TOKEN=" + token));
    }

    @Test
    public void startupFilePreservesAndroidAndUserShellSetupBeforeCompactPrompt() {
        String startupFile = ShellConfig.startupFileContent();

        int androidRc = startupFile.indexOf("/system/etc/mkshrc");
        int userRc = startupFile.indexOf("$HOME/.mkshrc");
        int compactPrompt = startupFile.indexOf("PS1='colabterm$ '");

        assertTrue(androidRc >= 0);
        assertTrue(userRc > androidRc);
        assertTrue(compactPrompt > userRc);
        assertTrue(startupFile.contains("export PS1"));
    }

    @Test
    public void resolvesHelperFromAndroidInstalledNativeLibraryDirectory() {
        assertEquals("/data/app/dev.trung.colabterm/lib/arm64/libcolab_cli.so",
                ShellConfig.colabHelperPath("/data/app/dev.trung.colabterm/lib/arm64"));
    }

    private static boolean contains(String[] values, String expected) {
        for (String value : values) {
            if (expected.equals(value)) return true;
        }
        return false;
    }
}
