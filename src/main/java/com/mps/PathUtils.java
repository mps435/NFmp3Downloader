package com.mps;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathUtils {
    
    private static final String APP_NAME = "NFmp3Downloader";

    public static Path getApplicationDirectory() {
        try {
            File jarFile = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jarFile.isFile()) {
                
                return jarFile.getParentFile().toPath();
            } else {
                
                return jarFile.toPath().getParent().getParent();
            }
        } catch (URISyntaxException e) {
            
            throw new IllegalStateException("Could not find application path.", e);
        }
    }

    public static Path getAppDataDirectory() {
        return Paths.get(System.getenv("APPDATA"));
    }
    
    public static Path getBinDirectory() {
        return getAppDataDirectory().resolve(APP_NAME).resolve("bin");
    }
}
