

package com.mps;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

public class App {

    private static final int PORT = 9595;
    private static final String APP_NAME = "NFmp3Downloader";
    private static Logger logger;
    private static final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void main(String[] args) {
        setupLogging();
        setupBinaries();
        
        boolean launchedByProtocol = false;
        for (String arg : args) {
            if ("--protocol-launch".equalsIgnoreCase(arg)) {
                launchedByProtocol = true;
                break;
            }
        }
        
        logger.info("{} starting...", APP_NAME);

        Javalin app = Javalin.create(config -> {
            config.jetty.wsFactoryConfig(factory -> {
                factory.setIdleTimeout(Duration.ofHours(1));
            });
        }).start(PORT);

        logger.info("Server listening for WebSocket connections on port {}", PORT);

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                logger.info("WebSocket client connected: {}", ctx.getSessionId());
                activeSessions.put(ctx.getSessionId(), ctx.session);
            });

            ws.onMessage(ctx -> {
                try {
                    String message = ctx.message();
                    JsonNode jsonNode = objectMapper.readTree(message);
                    String type = jsonNode.get("type").asText();
                    String youtubeUrl;

                    if ("download".equals(type)) { 
                        youtubeUrl = jsonNode.get("url").asText();
                        boolean isPlaylist = jsonNode.has("playlist") && jsonNode.get("playlist").asBoolean();
                        logger.info("Received MP3 download request for URL: {}, isPlaylist: {}", youtubeUrl, isPlaylist);
                        DownloadService.startDownload(youtubeUrl, isPlaylist, null, ctx.session);

                    } else if ("download_video".equals(type)) {
                        youtubeUrl = jsonNode.get("url").asText();
                        String formatId = jsonNode.get("formatId").asText();
                        
                        boolean isPlaylist = jsonNode.has("playlist") && jsonNode.get("playlist").asBoolean();
                        logger.info("Received video download request for URL: {}, formatId: {}, isPlaylist: {}", youtubeUrl, formatId, isPlaylist);
                        
                        DownloadService.startDownload(youtubeUrl, isPlaylist, formatId, ctx.session);

                    } else if ("open_log".equals(type)) {
                        logger.info("Client requested to open log file.");
                        openLogFile();
                    }
                } catch (IOException e) {
                    logger.error("Error processing WebSocket message", e);
                }
            });

            ws.onClose(ctx -> {
                logger.info("WebSocket client disconnected: {} - Reason: {}", ctx.getSessionId(), ctx.reason());
                activeSessions.remove(ctx.getSessionId());
                if (activeSessions.isEmpty()) {
                    logger.info("Last client disconnected. Shutting down server...");
                    new Thread(() -> {
                        try {
                            Thread.sleep(1500);
                            if (activeSessions.isEmpty()) {
                                app.stop();
                                logger.info("Server stopped. Exiting.");
                                System.exit(0);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                }
            });

            ws.onError(ctx -> {
                logger.error("WebSocket error for client {}:", ctx.getSessionId(), ctx.error());
            });
        });

        if (!launchedByProtocol) {
            logger.info("Application started manually. Launching browser.");
            launchBrowser();
        } else {
            logger.info("Application started via protocol. Browser should already be open.");
        }
    }
    
    
    private static void setupBinaries() {
        String[] binaries = {"yt-dlp.exe", "ffmpeg.exe"};
        try {
            Path binDir = PathUtils.getBinDirectory();
            if (!Files.exists(binDir)) { Files.createDirectories(binDir); }
            for (String binaryName : binaries) {
                Path targetPath = binDir.resolve(binaryName);
                if (!Files.exists(targetPath)) {
                    logger.info("Binary '{}' not found in AppData. Copying from resources...", binaryName);
                    try (InputStream source = App.class.getResourceAsStream("/bin/" + binaryName)) {
                        if (source == null) throw new IOException("Binary file not found in resources: /bin/" + binaryName);
                        Files.copy(source, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Successfully copied '{}' to {}", binaryName, targetPath);
                    }
                }
            }
        } catch (IOException e) {
            if (logger != null) logger.error("CRITICAL: Failed to set up binary files.", e);
            else e.printStackTrace();
            System.exit(1);
        }
    }
    private static void setupLogging() {
        try {
            Path appDataPath = PathUtils.getAppDataDirectory();
            Path logDir = appDataPath.resolve(APP_NAME);
            Files.createDirectories(logDir);
            Path logFile = logDir.resolve("app.log");
            System.setProperty("appdata.log.file", logFile.toString());
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            context.reset();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            try (InputStream configStream = App.class.getResourceAsStream("/logback.xml")) {
                if (configStream == null) throw new IOException("Could not find logback.xml in resources.");
                configurator.doConfigure(configStream);
            }
            logger = LoggerFactory.getLogger(App.class);
            logger.info("-------------------- Application Start --------------------");
            logger.info("Logging configured. Log file path: {}", logFile);
        } catch (IOException | JoranException e) {
            System.err.println("CRITICAL: Failed to configure Logback. Logging to console only.");
            e.printStackTrace();
            logger = LoggerFactory.getLogger(App.class);
        }
    }
    private static void launchBrowser() {
        try {
            Path indexPath = PathUtils.getApplicationDirectory().resolve("web").resolve("index.html");
            if (!Files.exists(indexPath)) {
                logger.error("Could not find index.html at: {}. Please reinstall the application.", indexPath);
                return;
            }
            URI indexUri = indexPath.toUri();
            logger.info("Attempting to open browser at: {}", indexUri);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(indexUri);
                logger.info("Successfully launched default browser.");
            } else {
                logger.error("Cannot launch browser automatically on this system. Please open {} manually.", indexUri);
            }
        } catch (Exception e) {
            logger.error("Failed to launch browser.", e);
        }
    }
    private static void openLogFile() {
        try {
            Path logFilePath = Paths.get(System.getProperty("appdata.log.file"));
            if (Files.exists(logFilePath)) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(logFilePath.toFile());
                    logger.info("Opened log file: {}", logFilePath);
                } else {
                    logger.warn("Cannot open log file automatically. Path is: {}", logFilePath);
                }
            } else {
                logger.warn("Log file does not exist yet.");
            }
        } catch (Exception e) {
            logger.error("Failed to open log file.", e);
        }
    }
}
