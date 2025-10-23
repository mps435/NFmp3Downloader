package com.mps;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PathUtils {
    
    private static final String APP_NAME = "NFmp3Downloader";
    private static Path applicationDirectory = null;

    /**
     * Finds the base directory of the application.
     * This logic works for both running from a packaged JAR and from a jpackage installation.
     * @return The root path of the application.
     */
    public static Path getApplicationDirectory() {
        if (applicationDirectory != null) {
            return applicationDirectory;
        }
        try {
            File codeSourceFile = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            
            // If running from a JAR file (the standard for packaged apps and executable JARs),
            // the application directory is the directory containing the JAR.
            if (codeSourceFile.isFile()) {
                applicationDirectory = codeSourceFile.getParentFile().toPath();
            } else {
                // This is a fallback for development environments (e.g., running from an IDE)
                // where the code source is a directory like 'target/classes'.
                applicationDirectory = codeSourceFile.toPath();
            }
        } catch (URISyntaxException e) {
            // This would be a critical failure, indicating a problem with the environment.
            throw new IllegalStateException("Could not determine application path.", e);
        }
        
        // This log is helpful for debugging path issues.
        System.out.println("Application Directory resolved to: " + applicationDirectory);
        return applicationDirectory;
    }

    /**
     * Gets the user's AppData\Roaming directory.
     * @return Path to the APPDATA directory.
     */
    public static Path getAppDataDirectory() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isEmpty()) {
            // A reasonable fallback for non-Windows systems or unusual configurations.
            return Paths.get(System.getProperty("user.home"), ".config");
        }
        return Paths.get(appdata);
    }
    
    /**
     * Gets the directory where binaries (yt-dlp, ffmpeg) are stored within AppData.
     * This will be %APPDATA%\NFmp3Downloader\bin
     * @return Path to the binary storage directory.
     */
    public static Path getBinDirectory() {
        return getAppDataDirectory().resolve(APP_NAME).resolve("bin");
    }
}