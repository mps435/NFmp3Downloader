package com.mps;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathUtils {

    private static final String APP_NAME = "NFmp3Downloader";
    private static Path applicationDirectory = null;

    public static Path getApplicationDirectory() {
        if (applicationDirectory != null) {
            return applicationDirectory;
        }

        try {
            File jarOrClassPath = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            String javaHome = System.getProperty("java.home");
            if (javaHome != null && new File(javaHome).getName().equals("runtime")) {
                applicationDirectory = Paths.get(javaHome).getParent();
            } else if (jarOrClassPath.isFile()) {
                applicationDirectory = jarOrClassPath.getParentFile().toPath();
            } else {
                applicationDirectory = jarOrClassPath.toPath().getParent().getParent();
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Could not determine application path.", e);
        }
        
        System.out.println("Application Directory resolved to: " + applicationDirectory);
        return applicationDirectory;
    }

    public static Path getAppDataDirectory() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) {
            return Paths.get(System.getProperty("user.home"), "AppData", "Roaming");
        }
        return Paths.get(appdata);
    }

    public static Path getBinDirectory() {
        return getAppDataDirectory().resolve(APP_NAME).resolve("bin");
    }
}