package dev.trung.colabterm;

final class ShellConfig {
    static final String SHELL_PATH = "/system/bin/sh";

    private ShellConfig() { }

    static String[] arguments() {
        return new String[]{SHELL_PATH, "-i"};
    }

    static String[] environment(String homeDirectory, String tempDirectory, String binaryDirectory,
                                int bridgePort, String bridgeToken) {
        return new String[]{
                "HOME=" + homeDirectory,
                "TMPDIR=" + tempDirectory,
                "ENV=" + startupFilePath(tempDirectory),
                "PATH=" + binaryDirectory + ":/system/bin:/system/xbin",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=C.UTF-8",
                "PS1=colabterm\\$ ",
                "COLABTERM_ANDROID=1",
                "COLABTERM_BRIDGE_PORT=" + bridgePort,
                "COLABTERM_BRIDGE_TOKEN=" + bridgeToken
        };
    }

    static String startupFilePath(String tempDirectory) {
        return tempDirectory + "/colabterm.mkshrc";
    }

    static String startupFileContent() {
        return "if [ -r /system/etc/mkshrc ]; then\n"
                + "    . /system/etc/mkshrc\n"
                + "fi\n"
                + "if [ -r \"$HOME/.mkshrc\" ]; then\n"
                + "    . \"$HOME/.mkshrc\"\n"
                + "fi\n"
                + "PS1='colabterm$ '\n"
                + "export PS1\n";
    }

    static String colabHelperPath(String nativeLibraryDirectory) {
        return nativeLibraryDirectory + "/libcolab_cli.so";
    }
}
