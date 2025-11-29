package com.mps;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.UIManager;

import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import io.javalin.Javalin;

public class App {

    private static final int PORT = 9595;
    private static final String APP_NAME = "NFmp3Downloader";
    private static final String CURRENT_VERSION = "2.1.0";
    private static final String GITHUB_API_URL = "https://api.github.com/repos/mps435/NFmp3Downloader/releases/latest";
    private static Logger logger;
    private static final ConcurrentHashMap<String, Session> activeSessions = new ConcurrentHashMap<>();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    public static volatile boolean isYtDlpUpdating = false;
    public static void main(String[] args) {
        setupLogging();
        setupBinaries();
        runUpdaterInBackground();

        System.setProperty("java.awt.headless", "false");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.warn("Could not set system look and feel.", e);
        }

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

        app.get("/local-image", ctx -> {
            try {
                String pathParam = ctx.queryParam("path");

                if (pathParam != null && !pathParam.isEmpty()) {
                    Path path = Paths.get(pathParam);

                    logger.info("Request for image: {}", path.toString());

                    if (Files.exists(path) && Files.isReadable(path)) {
                        String mimeType = Files.probeContentType(path);
                        if (mimeType == null) {
                            String lowerPath = path.toString().toLowerCase();
                            if (lowerPath.endsWith(".png")) {
                                mimeType = "image/png"; 
                            }else if (lowerPath.endsWith(".gif")) {
                                mimeType = "image/gif"; 
                            }else if (lowerPath.endsWith(".webp")) {
                                mimeType = "image/webp"; 
                            }else {
                                mimeType = "image/jpeg";

                                                    }}

                        ctx.contentType(mimeType);

                        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate");

                        ctx.result(Files.newInputStream(path));
                    } else {
                        logger.error("File not found or not readable: {}", path);
                        ctx.status(404).result("File not found");
                    }
                } else {
                    ctx.status(400).result("Missing path parameter");
                }
            } catch (Exception e) {
                logger.error("Critical error serving image", e);
                ctx.status(500).result("Server Error");
            }
        });
        logger.info("Server listening for WebSocket connections on port {}", PORT);

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                logger.info("WebSocket client connected: {}", ctx.getSessionId());
                activeSessions.put(ctx.getSessionId(), ctx.session);
                checkForUpdatesInBackground(ctx.session);
            });

            ws.onMessage(ctx -> {
                try {
                    String message = ctx.message();
                    JsonNode jsonNode = objectMapper.readTree(message);
                    String type = jsonNode.get("type").asText();
                    String destinationPath = jsonNode.has("destinationPath") ? jsonNode.get("destinationPath").asText(null) : null;
                    boolean isNetfree = jsonNode.has("isNetfreeUser") && jsonNode.get("isNetfreeUser").asBoolean(false);

                    if ("select_destination".equals(type)) {
                        String dialogTitle = jsonNode.has("title") ? jsonNode.get("title").asText("Select download folder") : "Select download folder";
                        logger.info("Client requested to select a destination folder with title: '{}'", dialogTitle);
                        handleSelectDestination(ctx.session, dialogTitle);
                    } else if ("cancel_download".equals(type)) {
                        logger.info("Client requested to cancel the download.");
                        DownloadService.cancelCurrentDownload();
                    } else if ("download_queue".equals(type)) {
                        List<String> urls = objectMapper.convertValue(jsonNode.get("urls"), new TypeReference<>() {
                        });
                        String formatId = jsonNode.has("formatId") ? jsonNode.get("formatId").asText(null) : null;
                        logger.info("Received download queue request with {} URLs, formatId: {}, destination: {}, isNetfree: {}", urls.size(), formatId, destinationPath, isNetfree);
                        DownloadService.startDownloadQueue(urls, formatId, destinationPath, isNetfree, ctx.session);
                    } else if ("download".equals(type)) {
                        String youtubeUrl = jsonNode.get("url").asText();
                        boolean isPlaylist = jsonNode.has("playlist") && jsonNode.get("playlist").asBoolean();
                        logger.info("Received MP3 download request for URL: {}, isPlaylist: {}, destination: {}, isNetfree: {}", youtubeUrl, isPlaylist, destinationPath, isNetfree);
                        DownloadService.startDownload(youtubeUrl, isPlaylist, null, destinationPath, isNetfree, ctx.session);

                    } else if ("select_background_image".equals(type)) {
                        new Thread(() -> {
                            String path = NativeFolderDialog.chooseFile("Select Background Image");

                            String pathJsonValue = (path == null) ? "null" : "\"" + path.replace("\\", "\\\\") + "\"";
                            String json = "{\"type\": \"background_selected\", \"path\": " + pathJsonValue + "}";

                            try {
                                ctx.session.getRemote().sendString(json);
                            } catch (Exception e) {
                                logger.error("Failed to send bg path", e);
                            }
                        }).start();
                    } else if ("download_video".equals(type)) {
                        String youtubeUrl = jsonNode.get("url").asText();
                        String formatId = jsonNode.get("formatId").asText();
                        boolean isPlaylist = jsonNode.has("playlist") && jsonNode.get("playlist").asBoolean();
                        logger.info("Received video download request for URL: {}, formatId: {}, isPlaylist: {}, isNetfree: {}", youtubeUrl, formatId, isPlaylist, isNetfree);
                        DownloadService.startDownload(youtubeUrl, isPlaylist, formatId, destinationPath, isNetfree, ctx.session);
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
                    logger.info("Last client disconnected. Attempting graceful shutdown...");
                    new Thread(() -> {
                        try {
                            Thread.sleep(2000);
                            if (activeSessions.isEmpty()) {
                                logger.info("No new clients. Shutting down server.");
                                app.stop();
                                logger.info("Server stopped. Exiting application.");
                                System.exit(0);
                            } else {
                                logger.info("A new client reconnected. Aborting shutdown.");
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

    private static void runUpdaterInBackground() {
        isYtDlpUpdating = true;

        Thread updaterThread = new Thread(() -> {
            try {
                logger.info("Starting background check for yt-dlp updates...");
                List<String> command = new ArrayList<>();
                command.add(PathUtils.getBinDirectory().resolve("yt-dlp.exe").toString());
                command.add("-U");
                command.add("--no-check-certificates");

                ProcessBuilder processBuilder = new ProcessBuilder(command);
                Process process = processBuilder.start();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        logger.info("[yt-dlp-update]: {}", line);
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    logger.info("yt-dlp update check finished successfully.");
                } else {
                    logger.warn("yt-dlp update check finished with exit code: {}", exitCode);
                }
            } catch (IOException | InterruptedException e) {
                logger.error("Failed to run background updater for yt-dlp.", e);
            } finally {
                isYtDlpUpdating = false;
                logger.info("Background update flag cleared (Update Process Ended).");
            }
        });
        updaterThread.setName("yt-dlp-updater");
        updaterThread.setDaemon(true);
        updaterThread.start();
    }

    private static void handleSelectDestination(Session session, String dialogTitle) {
        new Thread(() -> {
            logger.info("Attempting to open native folder chooser using JNA...");
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                logger.warn("Native folder dialog is only supported on Windows.");
                sendMessage(session, DownloadMessage.destinationSelected(null));
                return;
            }
            try {
                String selectedPath = NativeFolderDialog.show(dialogTitle);

                if (selectedPath != null) {
                    logger.info("User selected destination path: {}", selectedPath);
                } else {
                    logger.info("User cancelled selection or closed the dialog.");
                }

                sendMessage(session, DownloadMessage.destinationSelected(selectedPath));

            } catch (Throwable t) {
                logger.error("Error in native dialog.", t);
                sendMessage(session, DownloadMessage.destinationSelected(null));
            } finally {
                isYtDlpUpdating = false;
                logger.info("Background update process finished (Flag cleared).");
            }
        }).start();
    }

    private static void sendMessage(Session session, DownloadMessage message) {
        try {
            if (session != null && session.isOpen()) {
                String jsonMessage = objectMapper.writeValueAsString(message);
                session.getRemote().sendString(jsonMessage);
            }
        } catch (Exception e) {
            logger.warn("Failed to send WebSocket message", e);
        }
    }

    private static void setupBinaries() {
        String[] binaries = {"yt-dlp.exe", "ffmpeg.exe"};
        try {
            Path binDir = PathUtils.getBinDirectory();
            if (!Files.exists(binDir)) {
                Files.createDirectories(binDir);
            }
            for (String binaryName : binaries) {
                Path targetPath = binDir.resolve(binaryName);
                if (!Files.exists(targetPath)) {
                    logger.info("Binary '{}' not found in AppData. Copying from resources...", binaryName);
                    try (InputStream source = App.class.getResourceAsStream("/bin/" + binaryName)) {
                        if (source == null) {
                            throw new IOException("Binary file not found in resources: /bin/" + binaryName);
                        }
                        Files.copy(source, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        logger.info("Successfully copied '{}' to {}", binaryName, targetPath);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("CRITICAL: Failed to set up binary files. " + e.getMessage());
            e.printStackTrace();
            if (logger != null) {
                logger.error("CRITICAL: Failed to set up binary files.", e);
            }
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
                if (configStream == null) {
                    throw new IOException("Could not find logback.xml in resources.");
                }
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

    private static void checkForUpdatesInBackground(Session session) {
        Thread updateCheckerThread = new Thread(() -> {
            try {
                logger.info("Checking for application updates from GitHub...");

                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(GITHUB_API_URL))
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                java.net.http.HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode releaseInfo = objectMapper.readTree(response.body());
                    String latestVersionTag = releaseInfo.get("tag_name").asText();
                    String releaseNotes = releaseInfo.get("body").asText();

                    String latestVersion = latestVersionTag.startsWith("v") ? latestVersionTag.substring(1) : latestVersionTag;

                    logger.info("Current version: {}, Latest version on GitHub: {}", CURRENT_VERSION, latestVersion);

                    if (isNewerVersion(latestVersion, CURRENT_VERSION)) {
                        String directDownloadUrl = "";
                        JsonNode assets = releaseInfo.get("assets");
                        if (assets != null && assets.isArray()) {
                            for (JsonNode asset : assets) {
                                String name = asset.get("name").asText();
                                if (name.toLowerCase().endsWith(".exe")) {
                                    directDownloadUrl = asset.get("browser_download_url").asText();
                                    break;
                                }

                            }

                            if (directDownloadUrl.isEmpty()) {
                                directDownloadUrl = releaseInfo.get("html_url").asText();
                            }

                            sendMessage(session, DownloadMessage.updateAvailable(latestVersionTag, releaseNotes, directDownloadUrl));
                        }
                    } else {
                        logger.info("Application is up to date.");
                    }
                } else {
                    logger.warn("Failed to check for updates. GitHub API returned status code: {}", response.statusCode());
                }
            } catch (Exception e) {
                logger.error("Error while checking for updates.", e);
            }
        }
        );
        updateCheckerThread.setName(
                "App-Update-Checker");
        updateCheckerThread.setDaemon(
                true);
        updateCheckerThread.start();
    }

    private static boolean isNewerVersion(String newVersion, String currentVersion) {
        String[] newParts = newVersion.split("\\.");
        String[] currentParts = currentVersion.split("\\.");
        int length = Math.max(newParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int newPart = i < newParts.length ? Integer.parseInt(newParts[i]) : 0;
            int currentPart = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            if (newPart > currentPart) {
                return true;
            }
            if (newPart < currentPart) {
                return false;
            }
        }
        return false;
    }
}
