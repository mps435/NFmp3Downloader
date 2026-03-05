package com.mps;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

public class DownloadService {

    private static final Logger logger = LoggerFactory.getLogger(DownloadService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private static final Pattern progressPattern = Pattern
            .compile("\\[download\\]\\s+([0-9.]+)%\\s+of\\s+.*?\\s+at\\s+(.*?\\/s)");
    private static final Pattern playlistProgressPattern = Pattern
            .compile("\\[download\\] Downloading item (\\d+) of (\\d+)");
    private static final Pattern destinationFilePattern = Pattern
            .compile("\\[(?:download|ExtractAudio|Merger|ffmpeg)\\] Destination: (.*)");

    private static volatile Process currentProcess = null;
    private static final AtomicBoolean cancellationRequested = new AtomicBoolean(false);
    private static final AtomicReference<Path> currentTempDirRef = new AtomicReference<>(null);

    public static void cancelCurrentDownload() {
        cancellationRequested.set(true);
        Process processToKill = currentProcess;
        if (processToKill != null && processToKill.isAlive()) {
            logger.info("Attempting to cancel current download process tree...");
            killProcessTree(processToKill);
        } else {
            logger.warn("Cancellation requested, but no active process found.");
        }
    }

    private static void killProcessTree(Process process) {
        try {
            ProcessHandle handle = process.toHandle();
            logger.info("Killing process tree for PID {}", handle.pid());
            handle.descendants().forEach(child -> {
                logger.info("Killing child process PID {}", child.pid());
                child.destroyForcibly();
            });
            handle.destroyForcibly();
        } catch (Exception e) {
            logger.error("Error while trying to kill process tree", e);
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Path path = currentTempDirRef.get();
            if (path != null && Files.exists(path)) {
                System.out.println("App is shutting down. Force cleaning temp dir: " + path);
                deleteDirectoryRecursively(path);
            }
        }));
    }

    private static void waitForUpdateIfNeeded(Session session) {
        if (App.isYtDlpUpdating) {
            logger.info("Download requested while background update is running. Waiting...");
            sendMessage(session, DownloadMessage.updating());

            int waitCounter = 0;
            while (App.isYtDlpUpdating && waitCounter < 120) {
                if (cancellationRequested.get()) {
                    return;
                }
                try {
                    Thread.sleep(500);
                    waitCounter++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (waitCounter >= 120) {
                logger.warn("Waited too long for update (60s). Forcing download start.");
            } else {
                logger.info("Background update finished. Proceeding with download.");
            }

            sendMessage(session, DownloadMessage.starting());
        }
    }

    public static void startDownload(String youtubeUrl, boolean isPlaylist, String formatId, String destinationPath,
            boolean isNetfree, Session session) {
        downloadExecutor
                .submit(() -> runDownloadFlow(youtubeUrl, isPlaylist, formatId, destinationPath, isNetfree, session));
    }

    public static void startDownloadQueue(List<String> urls, String formatId, String destinationPath, boolean isNetfree,
            Session session, String playlistTitle, String language) {
        downloadExecutor.submit(() -> {
            cancellationRequested.set(false);
            waitForUpdateIfNeeded(session);
            if (cancellationRequested.get()) {
                sendMessage(session, DownloadMessage.cancelled());
                return;
            }
            Path queueFinalDir;
            Path basePath;
            if (destinationPath != null && !destinationPath.isEmpty()) {
                try {
                    basePath = Paths.get(destinationPath);
                } catch (InvalidPathException e) {
                    basePath = Paths.get(System.getProperty("user.home"), "Downloads");
                }
            } else {
                basePath = Paths.get(System.getProperty("user.home"), "Downloads");
            }
            if (playlistTitle != null) {
                String lang = (language != null) ? language : "en";
                String safeTitle = playlistTitle.replaceAll("[\\\\/:*?\"<>|]", "_");
                String prefix = "en".equals(lang) ? "Playlist" : "פלייליסט";
                String folderName = prefix + " - " + safeTitle;
                queueFinalDir = basePath.resolve(folderName);
                logger.info("Playlist download detected. Creating dedicated folder: {}", queueFinalDir);
            } else {

                queueFinalDir = basePath;
                logger.info("Multi-link download detected. No dedicated folder will be created.");
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            Path tempDir = Paths.get(System.getProperty("user.home"), "Downloads", ".NFDownloader_Temp_" + timeStamp);
            currentTempDirRef.set(tempDir);

            try {
                Files.createDirectories(tempDir);
                Files.createDirectories(queueFinalDir);

                logger.info("Starting queue download. Final Dir: {}", queueFinalDir);

                int successCount = 0;
                int failureCount = 0;
                List<String> successfulFiles = new ArrayList<>();

                for (int i = 0; i < urls.size(); i++) {
                    if (cancellationRequested.get()) {
                        break;
                    }
                    String url = urls.get(i);
                    sendMessage(session, DownloadMessage.playlistProgress(String.valueOf(i + 1), String.valueOf(urls.size())));

                    DownloadResult result = runDownloadFlowInternal(url, false, formatId, isNetfree, session, true);

                    if (result.isSuccess()) {
                        successCount++;
                        if (result.getFinalFileName() != null) {
                            successfulFiles.add(result.getFinalFileName());
                        }
                    } else {
                        failureCount++;
                        logger.error("Item failed: {}", url);
                    }
                }

                if (cancellationRequested.get()) {
                    sendMessage(session, DownloadMessage.cancelled());
                    return;
                }

                if (successCount > 0) {
                    moveFinalFiles(tempDir, queueFinalDir.toString());
                }

                sendMessage(session, DownloadMessage.queueComplete(successCount, failureCount, successfulFiles, queueFinalDir.toString()));

            } catch (Exception e) {
                logger.error("Error during queue processing", e);
                sendMessage(session, DownloadMessage.error("Critical error in queue processing."));
            } finally {
                deleteDirectoryRecursively(currentTempDirRef.get());
                currentTempDirRef.set(null);
                cancellationRequested.set(false);
            }
        });
    }

    private static DownloadResult runDownloadFlowInternal(String youtubeUrl, boolean isPlaylist, String formatId,
            boolean isNetfree, Session session, boolean isQueueItem) {
        String proxyUrl = isNetfree ? "http://8.8.8.8:80" : null;
        DownloadResult result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, proxyUrl, session);
        if (isNetfree && !result.isSuccess() && !cancellationRequested.get()
                && !result.getErrorMessage().contains("Requested format is not available")) {
            logger.warn("Download failed with proxy 8.8.8.8. Retrying with proxy 1.1.1.1...");
            proxyUrl = "http://1.1.1.1:80";
            if (!isQueueItem) {
                sendMessage(session, DownloadMessage.starting());
            }
            result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, proxyUrl, session);
        }
        if (!result.isSuccess() && !cancellationRequested.get()
                && result.getErrorMessage().contains("Requested format is not available")) {
            logger.warn("Download failed with a potential version issue. Attempting update.");
            boolean updateSucceeded = performUpdate(session);

            if (updateSucceeded) {
                logger.info("Update seems successful. Retrying download.");
                if (!isQueueItem) {
                    sendMessage(session, DownloadMessage.starting());
                }
                result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, proxyUrl, session);
            }
        }
        return result;
    }

    private static void runDownloadFlow(String youtubeUrl, boolean isPlaylist, String formatId, String destinationPath,
            boolean isNetfree, Session session) {
        cancellationRequested.set(false);
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path tempDir = Paths.get(System.getProperty("user.home"), "Downloads", ".NFDownloader_Single_" + timeStamp);
        currentTempDirRef.set(tempDir);

        try {
            Files.createDirectories(tempDir);
            sendMessage(session, DownloadMessage.starting());
            DownloadResult result = runDownloadFlowInternal(youtubeUrl, isPlaylist, formatId, isNetfree, session,
                    false);

            if (cancellationRequested.get()) {
                sendMessage(session, DownloadMessage.cancelled());
            } else if (result.isSuccess()) {
                moveFinalFiles(tempDir, destinationPath);

                Path finalDest;
                if (destinationPath != null && !destinationPath.isEmpty()) {
                    finalDest = Paths.get(destinationPath);
                } else {
                    finalDest = Paths.get(System.getProperty("user.home"), "Downloads");
                }

                moveFinalFiles(tempDir, destinationPath);

                sendMessage(session, DownloadMessage.success(finalDest.toString()));
            } else {
                sendMessage(session, DownloadMessage.error(result.getErrorMessage()));
            }
        } catch (IOException e) {
            logger.error("Error during single download flow", e);
            sendMessage(session, DownloadMessage.error("A critical error occurred while managing the download."));
        } finally {
            deleteDirectoryRecursively(currentTempDirRef.get());
            currentTempDirRef.set(null);
            cancellationRequested.set(false);
        }
    }

    private static void moveFinalFiles(Path sourceDir, String destinationPath) {
        Path targetDir;
        try {
            if (destinationPath != null && !destinationPath.isEmpty()) {
                targetDir = Paths.get(destinationPath);
            } else {
                targetDir = Paths.get(System.getProperty("user.home"), "Downloads");
            }
            Files.createDirectories(targetDir);
        } catch (InvalidPathException | IOException e) {
            logger.error("Invalid or inaccessible destination path '{}'. Falling back to default Downloads folder.",
                    destinationPath, e);
            targetDir = Paths.get(System.getProperty("user.home"), "Downloads");
        }

        final Path finalTargetDir = targetDir;

        try (Stream<Path> stream = Files.list(sourceDir)) {
            stream.forEach(path -> {
                try {
                    Path target = finalTargetDir.resolve(path.getFileName());
                    Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                    logger.info("Moved final file {} to {}", path, target);
                } catch (IOException e) {
                    logger.error("Failed to move final file {}", path, e);
                }
            });
        } catch (IOException e) {
            logger.error("Could not list files in temp dir {} to move them.", sourceDir, e);
        }
    }

    private static DownloadResult performDownloadAttempt(String youtubeUrl, boolean isPlaylist, String formatId,
            String proxyUrl, Session session) {
        List<String> command = buildDownloadCommand(youtubeUrl, isPlaylist, formatId, currentTempDirRef.get(),
                proxyUrl);

        int attempts = 0;
        while (attempts < 3) {
            attempts++;

            sendMessage(session, DownloadMessage.starting());

            waitForUpdateIfNeeded(session);
            if (cancellationRequested.get()) {
                return new DownloadResult(false, "Cancelled", null);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = null;

            try {
                process = processBuilder.start();
                currentProcess = process;

                StringBuilder errorOutput = new StringBuilder();
                final AtomicReference<String> finalFileName = new AtomicReference<>();
                final AtomicBoolean isNetfreeBlocked = new AtomicBoolean(false);
                final AtomicBoolean isCorruptedBinary = new AtomicBoolean(false);

                StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), line -> {

                    if (line.startsWith("[debug] ")) {
                        return;
                    }
                    if (line.startsWith("MPS_METADATA:")) {
                        try {
                            String data = line.substring("MPS_METADATA:".length());
                            int splitIndex = data.lastIndexOf("|");
                            if (splitIndex != -1) {
                                String title = data.substring(0, splitIndex).trim();
                                String thumbnail = data.substring(splitIndex + 1).trim();
                                logger.info("Found metadata - Title: {}, Thumb: {}", title, thumbnail);
                                sendMessage(session, DownloadMessage.metadata(title, thumbnail));
                            }
                        } catch (Exception e) {
                            logger.error("Failed to parse metadata line: {}", line, e);
                        }
                        return;
                    }

                    logger.info("yt-dlp: {}", line);

                    Matcher destinationMatcher = destinationFilePattern.matcher(line);
                    if (destinationMatcher.find()) {
                        finalFileName.set(Paths.get(destinationMatcher.group(1).trim()).getFileName().toString());
                    }

                    Matcher progressMatcher = progressPattern.matcher(line);
                    if (progressMatcher.find()) {
                        sendMessage(session,
                                DownloadMessage.progress(progressMatcher.group(1), progressMatcher.group(2).trim()));
                    }

                    if (isPlaylist) {
                        Matcher playlistMatcher = playlistProgressPattern.matcher(line);
                        if (playlistMatcher.find()) {
                            sendMessage(session, DownloadMessage.playlistProgress(playlistMatcher.group(1),
                                    playlistMatcher.group(2)));
                        }
                    }
                    if (line.contains("[Merger] Merging formats")) {
                        sendMessage(session, DownloadMessage.merging());
                    } else if (line.startsWith("[ExtractAudio]")
                            || line.startsWith("[ffmpeg]")
                            || line.startsWith("[Metadata]")
                            || line.startsWith("[ThumbnailsConvertor]")) {
                        sendMessage(session, DownloadMessage.processing());
                    }
                });
                StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), line -> {

                    if (line.startsWith("[debug] ")) {
                        return;
                    }

                    boolean isStatusLine = line.startsWith("[ExtractAudio]")
                            || line.startsWith("[ffmpeg]")
                            || line.startsWith("[Metadata]")
                            || line.startsWith("[ThumbnailsConvertor]")
                            || line.startsWith("[EmbedThumbnail]")
                            || line.startsWith("[Merger]")
                            || line.startsWith("Deleting");

                    boolean isErrorLine = line.contains("ERROR:")
                            || line.contains("WARNING:")
                            || line.contains("NetFree")
                            || line.contains("418")
                            || line.contains("zlib.error")
                            || line.contains("Permission denied");
                    if (isStatusLine) {

                        logger.info("yt-dlp-status: {}", line);
                    } else if (isErrorLine) {

                        logger.error("yt-dlp-error: {}", line);
                        errorOutput.append(line).append("\n");
                    }
                    if (line.contains("[Merger] Merging formats")) {
                        sendMessage(session, DownloadMessage.merging());
                    } else if (line.startsWith("[ExtractAudio]")
                            || line.startsWith("[ffmpeg]")
                            || line.startsWith("[Metadata]")
                            || line.startsWith("[ThumbnailsConvertor]")) {
                        sendMessage(session, DownloadMessage.processing());
                    }
                    if (line.contains("418") || (line.contains("NetFree") && line.contains("Blocked"))) {
                        if (!isNetfreeBlocked.get()) {
                            isNetfreeBlocked.set(true);
                            sendMessage(session, DownloadMessage.netfreeBlocked());
                        }
                    }

                    if (line.contains("zlib.error") || line.contains("Failed to execute script")
                            || line.contains("Permission denied")) {
                        isCorruptedBinary.set(true);
                    }
                });
                ExecutorService gobblerExecutor = Executors.newFixedThreadPool(2);
                gobblerExecutor.submit(outputGobbler);
                gobblerExecutor.submit(errorGobbler);

                int exitCode = process.waitFor();
                gobblerExecutor.shutdown();
                gobblerExecutor.awaitTermination(10, TimeUnit.SECONDS);
                if (cancellationRequested.get()) {
                    return new DownloadResult(false, "Cancelled", null);
                }
                if (exitCode != 0 && (isCorruptedBinary.get() || App.isYtDlpUpdating)) {
                    logger.warn("Download failed likely due to active update (Exit: {}, Corrupted: {}). Retrying...",
                            exitCode, isCorruptedBinary.get());
                    waitForUpdateIfNeeded(session);
                    continue;
                }

                if (isNetfreeBlocked.get()) {
                    return new DownloadResult(false, "Blocked by NetFree", null);
                }

                if (exitCode == 0) {
                    return new DownloadResult(true, null, finalFileName.get());
                }

                return new DownloadResult(false, errorOutput.toString(), null);

            } catch (Exception e) {
                if (App.isYtDlpUpdating || e.getMessage().contains("Access is denied")) {
                    logger.warn("Failed to start process (locked file). Waiting for update...");
                    waitForUpdateIfNeeded(session);
                    continue;
                }

                if (cancellationRequested.get()) {
                    return new DownloadResult(false, "Cancelled", null);
                }
                return new DownloadResult(false, e.getMessage(), null);
            } finally {
                currentProcess = null;
            }
        }

        return new DownloadResult(false, "Failed to complete download after retries.", null);
    }

    private static boolean performUpdate(Session session) {
        sendMessage(session, DownloadMessage.updateCheck());
        List<String> command = buildUpdateCommand();
        logger.info("Executing update command: {}", String.join(" ", command));
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            StringBuilder commandOutput = new StringBuilder();

            ExecutorService updateGobblerExecutor = Executors.newFixedThreadPool(2);

            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), line -> {
                logger.info("yt-dlp-update: {}", line);
                if (line.contains("Updating to")) {
                    sendMessage(session, DownloadMessage.updating());
                }
                commandOutput.append(line).append("\n");
            });
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), line -> {
                logger.error("yt-dlp-update-error: {}", line);
                commandOutput.append(line).append("\n");
            });

            updateGobblerExecutor.submit(outputGobbler);
            updateGobblerExecutor.submit(errorGobbler);
            int exitCode = process.waitFor();
            updateGobblerExecutor.shutdown();
            String output = commandOutput.toString();

            if (exitCode != 0 || output.contains("is up to date")) {
                logger.warn("yt-dlp is already up to date or update failed.");
                return false;
            }
            logger.info("yt-dlp update completed.");
            return true;
        } catch (Exception e) {
            logger.error("An exception occurred during update process", e);
            return false;
        }
    }

    private static List<String> buildDownloadCommand(String youtubeUrl, boolean isPlaylist, String formatId,
            Path tempOutputDir, String proxyUrl) {
        List<String> command = new ArrayList<>();
        command.add(PathUtils.getBinDirectory().resolve("yt-dlp.exe").toString());
        command.add("--verbose");

        command.add("--encoding");
        command.add("utf-8");

        if (proxyUrl != null && !proxyUrl.isEmpty()) {
            logger.info("Proxy detected. Applying specific settings: {}", proxyUrl);
            command.add("--proxy");
            command.add(proxyUrl);
        }

        command.add("--no-check-certificates");
        command.add("--progress");

        command.add("--ffmpeg-location");
        command.add(PathUtils.getBinDirectory().resolve("ffmpeg.exe").toString());
        command.add("--print");
        command.add("before_dl:MPS_METADATA:%(title)s|%(thumbnail)s");
        command.add("--output");
        command.add(isPlaylist ? "%(playlist)s/%(playlist_index)s - %(title)s.%(ext)s" : "%(title)s.%(ext)s");

        command.add(isPlaylist ? "--yes-playlist" : "--no-playlist");

        if (formatId != null && !formatId.isEmpty()) {
            command.add("-f");
            final String[] fullMetadataFlags = {"--embed-thumbnail", "--embed-metadata", "--add-metadata"};
            final String[] textOnlyMetadataFlags = {"--embed-metadata", "--add-metadata"};
            switch (formatId) {
                case "mp3_high":
                case "mp3_medium":
                case "mp3_low":
                    command.add("bestaudio");
                    command.add("--extract-audio");
                    command.add("--audio-format");
                    command.add("mp3");
                    command.add("--audio-quality");

                    if (formatId.equals("mp3_high")) {
                        command.add("0");
                    } else if (formatId.equals("mp3_medium")) {
                        command.add("5");
                    } else {
                        command.add("9");
                    }
                    command.addAll(List.of(fullMetadataFlags));
                    break;
                case "raw_audio":
                    command.add("bestaudio");
                    command.addAll(List.of(textOnlyMetadataFlags));
                    break;
                default:

                    command.add(formatId);
                    command.addAll(List.of(fullMetadataFlags));
                    break;
            }
        } else {

            logger.warn("No formatId provided, falling back to default high quality MP3.");
            command.add("-f");
            command.add("bestaudio");
            command.add("--extract-audio");
            command.add("--audio-format");
            command.add("mp3");
            command.add("--audio-quality");
            command.add("0");
            command.addAll(List.of("--embed-thumbnail", "--embed-metadata", "--add-metadata"));
        }

        command.add("-P");
        command.add(tempOutputDir.toString());
        command.add(youtubeUrl);
        return command;
    }

    private static List<String> buildUpdateCommand() {
        List<String> command = new ArrayList<>();
        command.add(PathUtils.getBinDirectory().resolve("yt-dlp.exe").toString());
        command.add("-U");
        command.add("--no-check-certificates");
        return command;
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

    private static void deleteDirectoryRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        logger.info("Starting robust deletion for: {}", path);
        for (int i = 1; i <= 10; i++) {
            try {

                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(file -> {

                                if (!file.delete() && file.exists()) {

                                    throw new RuntimeException("Failed to delete " + file.getAbsolutePath());
                                }
                            });
                }
                if (!Files.exists(path)) {
                    logger.info("Directory deleted successfully on attempt {}", i);
                    return;
                }
            } catch (Exception e) {

                logger.warn("Attempt {} to delete temp dir failed (File likely locked). Retrying...", i);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        logger.error("Gave up deleting directory after multiple attempts: {}", path);
    }

    private static class StreamGobbler implements Runnable {

        private final BufferedReader reader;
        private final Consumer<String> consumer;

        public StreamGobbler(java.io.InputStream inputStream, Consumer<String> consumer) {
            this.reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            this.consumer = consumer;
        }

        @Override
        public void run() {
            try {
                reader.lines().forEach(consumer);
            } catch (Exception e) {
                logger.warn("StreamGobbler interrupted, likely due to process cancellation.");
            }
        }
    }

    private static class DownloadResult {

        private final boolean success;
        private final String errorMessage;
        private final String finalFileName;

        public DownloadResult(boolean success, String errorMessage, String finalFileName) {
            this.success = success;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
            this.finalFileName = finalFileName;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getFinalFileName() {
            return finalFileName;
        }
    }
}