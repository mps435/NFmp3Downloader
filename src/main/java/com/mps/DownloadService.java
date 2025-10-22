package com.mps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DownloadService {

    private static final Logger logger = LoggerFactory.getLogger(DownloadService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private static final Pattern progressPattern = Pattern.compile("\\[download\\]\\s+([0-9.]+)%\\s+of\\s+.*?\\s+at\\s+(.*?\\/s)");
    private static final Pattern playlistProgressPattern = Pattern.compile("\\[download\\] Downloading item (\\d+) of (\\d+)");
    private static final Pattern destinationFilePattern = Pattern.compile("\\[(?:download|ExtractAudio|Merger|ffmpeg)\\] Destination: (.*)");


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

    public static void startDownload(String youtubeUrl, boolean isPlaylist, String formatId, String destinationPath, boolean isNetfree, Session session) {
        downloadExecutor.submit(() -> runDownloadFlow(youtubeUrl, isPlaylist, formatId, destinationPath, isNetfree, session));
    }

    public static void startDownloadQueue(List<String> urls, String formatId, String destinationPath, boolean isNetfree, Session session) {
        downloadExecutor.submit(() -> {
            cancellationRequested.set(false);
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

            Path queueFinalDir;
            if (destinationPath != null && !destinationPath.isEmpty()) {
                try {
                    queueFinalDir = Paths.get(destinationPath, "NFDownloader_Queue_" + timeStamp);
                } catch (InvalidPathException e) {
                    logger.error("Invalid custom destination path provided for queue: '{}'. Falling back to default.", destinationPath, e);
                    queueFinalDir = Paths.get(System.getProperty("user.home"), "Downloads", "NFDownloader_Queue_" + timeStamp);
                }
            } else {
                queueFinalDir = Paths.get(System.getProperty("user.home"), "Downloads", "NFDownloader_Queue_" + timeStamp);
            }

            Path tempDir = Paths.get(System.getProperty("user.home"), "Downloads", ".NFDownloader_Temp_" + timeStamp);
            currentTempDirRef.set(tempDir);

            try {
                Files.createDirectories(tempDir);
                Files.createDirectories(queueFinalDir);
                logger.info("Starting download queue with {} items. Temp dir: {}, Final dir: {}", urls.size(), tempDir, queueFinalDir);

                int successCount = 0;
                int failureCount = 0;
                List<String> successfulFiles = new ArrayList<>();

                for (int i = 0; i < urls.size(); i++) {
                    if (cancellationRequested.get()) {
                        logger.info("Download queue cancelled by user.");
                        break;
                    }
                    String url = urls.get(i);
                    logger.info("Downloading queue item {}/{}: {}", i + 1, urls.size(), url);

                    DownloadResult result = runDownloadFlowInternal(url, false, formatId, isNetfree, session, true);
                    if (result.isSuccess()) {
                        successCount++;
                        if (result.getFinalFileName() != null) {
                            successfulFiles.add(result.getFinalFileName());
                        }
                    } else {
                        if (cancellationRequested.get()) break;
                        failureCount++;
                        logger.error("Item {}/{} failed: {}. Error: {}", i + 1, urls.size(), url, result.getErrorMessage());
                    }
                }

                if (cancellationRequested.get()) {
                    sendMessage(session, DownloadMessage.cancelled());
                    return;
                }

                if (successCount > 0) {
                    moveFinalFiles(tempDir, queueFinalDir.toString());
                }
                
                sendMessage(session, DownloadMessage.queueComplete(successCount, failureCount, successfulFiles));

            } catch (Exception e) {
                logger.error("Error during queue processing", e);
                sendMessage(session, DownloadMessage.error("A critical error occurred while managing the download queue."));
            } finally {
                deleteDirectoryRecursively(currentTempDirRef.get());
                currentTempDirRef.set(null);
                cancellationRequested.set(false);
            }
        });
    }

    private static DownloadResult runDownloadFlowInternal(String youtubeUrl, boolean isPlaylist, String formatId, boolean isNetfree, Session session, boolean isQueueItem) {
        DownloadResult result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, isNetfree, session);

        if (!result.isSuccess() && !cancellationRequested.get() && result.getErrorMessage().contains("Requested format is not available")) {
            logger.warn("Download failed with a potential version issue. Attempting update.");
            boolean updateSucceeded = performUpdate(session);

            if (updateSucceeded) {
                logger.info("Update seems successful. Retrying download.");
                if (!isQueueItem) {
                    sendMessage(session, DownloadMessage.starting());
                }
                result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, isNetfree, session);
            }
        }
        return result;
    }

    private static void runDownloadFlow(String youtubeUrl, boolean isPlaylist, String formatId, String destinationPath, boolean isNetfree, Session session) {
        cancellationRequested.set(false);
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path tempDir = Paths.get(System.getProperty("user.home"), "Downloads", ".NFDownloader_Single_" + timeStamp);
        currentTempDirRef.set(tempDir);

        try {
            Files.createDirectories(tempDir);
            sendMessage(session, DownloadMessage.starting());
            DownloadResult result = runDownloadFlowInternal(youtubeUrl, isPlaylist, formatId, isNetfree, session, false);

            if (cancellationRequested.get()) {
                sendMessage(session, DownloadMessage.cancelled());
            } else if (result.isSuccess()) {
                moveFinalFiles(tempDir, destinationPath);
                sendMessage(session, DownloadMessage.success());
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
            logger.error("Invalid or inaccessible destination path '{}'. Falling back to default Downloads folder.", destinationPath, e);
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

    private static DownloadResult performDownloadAttempt(String youtubeUrl, boolean isPlaylist, String formatId, boolean isNetfree, Session session) {
        List<String> command = buildDownloadCommand(youtubeUrl, isPlaylist, formatId, currentTempDirRef.get(), isNetfree);
        logger.info("Executing command: {}", String.join(" ", command));

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            currentProcess = processBuilder.start();

            StringBuilder errorOutput = new StringBuilder();
            final AtomicBoolean processingMessageSent = new AtomicBoolean(false);
            final AtomicBoolean mergingMessageSent = new AtomicBoolean(false);
            final AtomicReference<String> finalFileName = new AtomicReference<>();

            StreamGobbler outputGobbler = new StreamGobbler(currentProcess.getInputStream(), line -> {
                logger.info("yt-dlp: {}", line);

                Matcher destinationMatcher = destinationFilePattern.matcher(line);
                if (destinationMatcher.find()) {
                    Path filePath = Paths.get(destinationMatcher.group(1).trim());
                    finalFileName.set(filePath.getFileName().toString());
                }
                
                Matcher progressMatcher = progressPattern.matcher(line);
                if (progressMatcher.find()) {
                    String percentStr = progressMatcher.group(1);
                    String speedStr = progressMatcher.group(2).trim();
                    sendMessage(session, DownloadMessage.progress(percentStr, speedStr));
                    return;
                }

                if (isPlaylist) {
                    Matcher playlistMatcher = playlistProgressPattern.matcher(line);
                    if (playlistMatcher.find()) {
                        String current = playlistMatcher.group(1);
                        String total = playlistMatcher.group(2);
                        sendMessage(session, DownloadMessage.playlistProgress(current, total));
                    }
                }

                if (line.contains("[Merger] Merging formats") && !mergingMessageSent.get()) {
                    logger.info("Merging detected. Sending 'merging' message.");
                    sendMessage(session, DownloadMessage.merging());
                    mergingMessageSent.set(true);
                    return;
                }
                
                if (formatId == null && (line.startsWith("[ExtractAudio] Destination:") || line.startsWith("[ffmpeg]")) && !processingMessageSent.get()) {
                    logger.info("Post-processing detected. Sending 'processing' message.");
                    sendMessage(session, DownloadMessage.processing());
                    processingMessageSent.set(true);
                }
            });
            StreamGobbler errorGobbler = new StreamGobbler(currentProcess.getErrorStream(), line -> {
                logger.error("yt-dlp-error: {}", line);
                errorOutput.append(line).append("\n");
            });
            
            ExecutorService gobblerExecutor = Executors.newFixedThreadPool(2);
            gobblerExecutor.submit(outputGobbler);
            gobblerExecutor.submit(errorGobbler);

            int exitCode = currentProcess.waitFor();
            gobblerExecutor.shutdown();

            if (cancellationRequested.get()) {
                return new DownloadResult(false, "Download was cancelled by the user.", null);
            }

            if (exitCode == 0) {
                logger.info("Process finished successfully. Final file: {}", finalFileName.get());
                return new DownloadResult(true, null, finalFileName.get());
            } else {
                String errorMsg = errorOutput.toString();
                if (errorMsg.isEmpty()) {
                    errorMsg = "The download process failed with exit code " + exitCode + " and no specific error message.";
                }
                return new DownloadResult(false, errorMsg, null);
            }

        } catch (Exception e) {
            if (cancellationRequested.get()) {
                return new DownloadResult(false, "Download was cancelled by the user.", null);
            }
            logger.error("An exception occurred during download attempt", e);
            return new DownloadResult(false, "An internal application error occurred: " + e.getMessage(), null);
        } finally {
            currentProcess = null;
        }
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

    private static List<String> buildDownloadCommand(String youtubeUrl, boolean isPlaylist, String formatId, Path tempOutputDir, boolean isNetfree) {
        List<String> command = new ArrayList<>();
        command.add(PathUtils.getBinDirectory().resolve("yt-dlp.exe").toString());
        
        command.add("--encoding");
        command.add("utf-8");

        if (isNetfree) {
            logger.info("NetFree user detected. Applying specific settings.");            
            command.add("--proxy");
            command.add("http://1.1.1.1:80");
        }
        
        command.add("--no-check-certificates");
        command.add("--progress");
        
        command.add("--ffmpeg-location");
        command.add(PathUtils.getBinDirectory().resolve("ffmpeg.exe").toString());
        
        command.add("--output");
        if (isPlaylist) {
            command.add("%(playlist)s/%(playlist_index)s - %(title)s.%(ext)s");
        } else {
            command.add("%(title)s.%(ext)s");
        }

        if (isPlaylist) {
            command.add("--yes-playlist");
        } else {
            command.add("--no-playlist");
        }

        if (formatId != null && !formatId.isEmpty()) {
            command.add("-f");
            command.add(formatId);
        } else {
            command.add("-f");
            command.add("bestaudio");
            command.add("--extract-audio");
            command.add("--audio-format");
            command.add("mp3");
            command.add("--audio-quality");
            command.add("0");
            command.add("--embed-thumbnail");
            command.add("--embed-metadata");
            command.add("--add-metadata");
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
        logger.info("Attempting to delete temporary directory: {}", path);
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        logger.warn("Failed to delete file: {}", file.getAbsolutePath());
                    }
                });
            logger.info("Successfully deleted directory: {}", path);
        } catch (IOException e) {
            logger.error("Failed to delete directory: {}", path, e);
        }
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