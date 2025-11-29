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
            File codeSourceFile = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            
            if (codeSourceFile.isFile()) {
                applicationDirectory = codeSourceFile.getParentFile().toPath();
            } else {
                applicationDirectory = codeSourceFile.toPath();
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
            return Paths.get(System.getProperty("user.home"), ".config");
        }
        return Paths.get(appdata);
    }
    
    public static Path getBinDirectory() {
        return getAppDataDirectory().resolve(APP_NAME).resolve("bin");
    }
}