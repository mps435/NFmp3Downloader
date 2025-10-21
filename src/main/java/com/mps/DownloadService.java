package com.mps;

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
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DownloadService {

    private static Logger logger;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();
    private static final Pattern progressPattern = Pattern.compile("\\[download\\]\\s+([0-9.]+)%\\s+of\\s+.*?\\s+at\\s+(.*?\\/s)");
    private static final Pattern playlistProgressPattern = Pattern.compile("\\[download\\] Downloading item (\\d+) of (\\d+)");
    private static final Pattern destinationFilePattern = Pattern.compile("\\[(?:download|ExtractAudio|Merger|ffmpeg)\\] Destination: (.*)");


    private static volatile Process currentProcess = null;
    private static volatile boolean cancellationRequested = false;
    private static volatile Path currentTempDir = null;

    private static Logger getLogger() {
        if (logger == null) {
            logger = LoggerFactory.getLogger(DownloadService.class);
        }
        return logger;
    }

    public static void cancelCurrentDownload() {
        cancellationRequested = true;
        Process processToKill = currentProcess;
        if (processToKill != null && processToKill.isAlive()) {
            getLogger().info("Attempting to cancel current download process tree...");
            killProcessTree(processToKill);
        } else {
            getLogger().warn("Cancellation requested, but no active process found.");
        }
        
        if (currentTempDir != null) {
            getLogger().info("Cancellation: Deleting temporary directory {}", currentTempDir);
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            deleteDirectoryRecursively(currentTempDir);
        }
    }
    
    private static void killProcessTree(Process process) {
        try {
            ProcessHandle handle = process.toHandle();
            getLogger().info("Killing process tree for PID {}", handle.pid());
            handle.descendants().forEach(child -> {
                getLogger().info("Killing child process PID {}", child.pid());
                child.destroyForcibly();
            });
            handle.destroyForcibly();
        } catch (Exception e) {
            getLogger().error("Error while trying to kill process tree", e);
        }
    }

    public static void startDownload(String youtubeUrl, boolean isPlaylist, String formatId, String destinationPath, Session session) {
        downloadExecutor.submit(() -> runDownloadFlow(youtubeUrl, isPlaylist, formatId, destinationPath, session));
    }

    public static void startDownloadQueue(List<String> urls, String formatId, String destinationPath, Session session) {
        downloadExecutor.submit(() -> {
            boolean useCustomDest = destinationPath != null && !destinationPath.isEmpty();
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            
            Path queueFinalDir;
            if (useCustomDest) {
                // For custom destinations, we create a subfolder inside it to keep things organized.
                try {
                    queueFinalDir = Paths.get(destinationPath, "NFDownloader_Queue_" + timeStamp);
                } catch (InvalidPathException e) {
                    getLogger().error("Invalid custom destination path provided for queue: '{}'. Falling back to default.", destinationPath, e);
                    queueFinalDir = Paths.get(System.getProperty("user.home"), "Downloads", "NFDownloader_Queue_" + timeStamp);
                }
            } else {
                queueFinalDir = Paths.get(System.getProperty("user.home"), "Downloads", "NFDownloader_Queue_" + timeStamp);
            }
            
            // The temp dir is always in the default downloads folder to avoid cluttering user's selection
            Path tempDir = Paths.get(System.getProperty("user.home"), "Downloads", ".NFDownloader_Temp_" + timeStamp);
            currentTempDir = tempDir;

            try {
                Files.createDirectories(tempDir);
                Files.createDirectories(queueFinalDir);
                getLogger().info("Starting download queue with {} items. Temp dir: {}, Final dir: {}", urls.size(), tempDir, queueFinalDir);
                
                int successCount = 0;
                int failureCount = 0;
                List<String> successfulFiles = new ArrayList<>();

                for (int i = 0; i < urls.size(); i++) {
                    if (cancellationRequested) {
                        getLogger().info("Download queue cancelled by user.");
                        break;
                    }
                    String url = urls.get(i);
                    
                    DownloadResult result = runDownloadFlowInternal(url, false, formatId, session, true);
                    if (result.isSuccess()) {
                        successCount++;
                        if (result.getFinalFileName() != null) {
                            successfulFiles.add(result.getFinalFileName());
                        }
                    } else {
                        if (cancellationRequested) break;
                        failureCount++;
                        getLogger().error("Item {}/{} failed: {}. Error: {}", i + 1, urls.size(), url, result.getErrorMessage());
                    }
                }
                
                if (cancellationRequested) {
                    sendMessage(session, DownloadMessage.cancelled());
                    return;
                }

                if (successCount > 0) {
                    moveFinalFiles(tempDir, queueFinalDir.toString());
                }
                
                sendMessage(session, DownloadMessage.queueComplete(successCount, failureCount, successfulFiles));

            } catch (Exception e) {
                getLogger().error("Error during queue processing", e);
                sendMessage(session, DownloadMessage.error("A critical error occurred while managing the download queue."));
            } finally {
                deleteDirectoryRecursively(currentTempDir);
                cancellationRequested = false;
                currentTempDir = null;
            }
        });
    }
    
    private static DownloadResult runDownloadFlowInternal(String youtubeUrl, boolean isPlaylist, String formatId, Session session, boolean isQueueItem) {
        DownloadResult result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, session);

        if (!result.isSuccess() && !cancellationRequested && result.getErrorMessage().contains("Requested format is not available")) {
            getLogger().warn("Download failed with a potential version issue. Attempting update.");
            boolean updateSucceeded = performUpdate(session);

            if (updateSucceeded) {
                getLogger().info("Update seems successful. Retrying download.");
                if (!isQueueItem) { 
                    sendMessage(session, DownloadMessage.starting());
                }
                result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, session);
            }
        }
        return result;
    }
    
    private static void runDownloadFlow(String youtubeUrl, boolean isPlaylist, String formatId, String destinationPath, Session session) {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        Path tempDir = Paths.get(System.getProperty("user.home"), "Downloads", ".NFDownloader_Single_" + timeStamp);
        currentTempDir = tempDir;

        try {
            Files.createDirectories(tempDir);
            sendMessage(session, DownloadMessage.starting());
            DownloadResult result = runDownloadFlowInternal(youtubeUrl, isPlaylist, formatId, session, false);
            
            if (cancellationRequested) {
                sendMessage(session, DownloadMessage.cancelled());
            } else if (result.isSuccess()) {
                moveFinalFiles(tempDir, destinationPath);
                sendMessage(session, DownloadMessage.success());
            } else {
                sendMessage(session, DownloadMessage.error(result.getErrorMessage()));
            }
        } catch (IOException e) {
            getLogger().error("Error during single download flow", e);
            sendMessage(session, DownloadMessage.error("A critical error occurred while managing the download."));
        } finally {
            deleteDirectoryRecursively(tempDir);
            cancellationRequested = false;
            currentTempDir = null;
        }
    }
    
    private static void moveFinalFiles(Path sourceDir, String destinationPath) {
        Path targetDir; // Regular, non-final variable
        try {
            if (destinationPath != null && !destinationPath.isEmpty()) {
                targetDir = Paths.get(destinationPath);
            } else {
                targetDir = Paths.get(System.getProperty("user.home"), "Downloads");
            }
            Files.createDirectories(targetDir);
        } catch (InvalidPathException | IOException e) {
            getLogger().error("Invalid or inaccessible destination path '{}'. Falling back to default Downloads folder.", destinationPath, e);
            targetDir = Paths.get(System.getProperty("user.home"), "Downloads");
        }

        // Assign the result to a new 'effectively final' variable for the lambda
        final Path finalTargetDir = targetDir;

        try (Stream<Path> stream = Files.list(sourceDir)) {
            stream.forEach(path -> {
                try {
                    Path target = finalTargetDir.resolve(path.getFileName());
                    Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
                    getLogger().info("Moved final file {} to {}", path, target);
                } catch (IOException e) {
                    getLogger().error("Failed to move final file {}", path, e);
                }
            });
        } catch (IOException e) {
            getLogger().error("Could not list files in temp dir {} to move them.", sourceDir, e);
        }
    }

    private static DownloadResult performDownloadAttempt(String youtubeUrl, boolean isPlaylist, String formatId, Session session) {
        cancellationRequested = false;
        List<String> command = buildDownloadCommand(youtubeUrl, isPlaylist, formatId, currentTempDir);
        getLogger().info("Executing command: {}", String.join(" ", command));
        
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            currentProcess = processBuilder.start();
            
            StringBuilder errorOutput = new StringBuilder();
            final AtomicBoolean processingMessageSent = new AtomicBoolean(false);
            final AtomicBoolean mergingMessageSent = new AtomicBoolean(false);
            final AtomicReference<String> finalFileName = new AtomicReference<>();
            
            StreamGobbler outputGobbler = new StreamGobbler(currentProcess.getInputStream(), line -> {
                getLogger().info("yt-dlp: {}", line);

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
                    getLogger().info("Merging detected. Sending 'merging' message.");
                    sendMessage(session, DownloadMessage.merging());
                    mergingMessageSent.set(true);
                    return;
                }
                
                if (formatId == null && (line.startsWith("[ExtractAudio] Destination:") || line.startsWith("[ffmpeg]")) && !processingMessageSent.get()) {
                    getLogger().info("Post-processing detected. Sending 'processing' message.");
                    sendMessage(session, DownloadMessage.processing());
                    processingMessageSent.set(true);
                }
            });
            StreamGobbler errorGobbler = new StreamGobbler(currentProcess.getErrorStream(), line -> {
                getLogger().error("yt-dlp-error: {}", line);
                errorOutput.append(line).append("\n");
            });
            
            ExecutorService gobblerExecutor = Executors.newFixedThreadPool(2);
            gobblerExecutor.submit(outputGobbler);
            gobblerExecutor.submit(errorGobbler);

            int exitCode = currentProcess.waitFor();
            gobblerExecutor.shutdown();

            if (cancellationRequested) {
                return new DownloadResult(false, "Download was cancelled by the user.", null);
            }

            if (exitCode == 0) {
                getLogger().info("Process finished successfully. Final file: {}", finalFileName.get());
                return new DownloadResult(true, null, finalFileName.get());
            } else {
                return new DownloadResult(false, errorOutput.toString(), null);
            }

        } catch (Exception e) {
            if (cancellationRequested) {
                return new DownloadResult(false, "Download was cancelled by the user.", null);
            }
            getLogger().error("An exception occurred during download attempt", e);
            return new DownloadResult(false, "An internal application error occurred: " + e.getMessage(), null);
        } finally {
            currentProcess = null;
        }
    }

    private static boolean performUpdate(Session session) {
        sendMessage(session, DownloadMessage.updateCheck());
        List<String> command = buildUpdateCommand();
        getLogger().info("Executing update command: {}", String.join(" ", command));
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            Process process = processBuilder.start();
            StringBuilder commandOutput = new StringBuilder();
            
            ExecutorService updateGobblerExecutor = Executors.newFixedThreadPool(2);

            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), line -> {
                getLogger().info("yt-dlp-update: {}", line);
                if (line.contains("Updating to")) {
                    sendMessage(session, DownloadMessage.updating());
                }
                commandOutput.append(line).append("\n");
            });
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), line -> {
                getLogger().error("yt-dlp-update-error: {}", line);
                commandOutput.append(line).append("\n");
            });
            
            updateGobblerExecutor.submit(outputGobbler);
            updateGobblerExecutor.submit(errorGobbler);
            int exitCode = process.waitFor();
            updateGobblerExecutor.shutdown();
            String output = commandOutput.toString();

            if (exitCode != 0 || output.contains("is up to date")) {
                getLogger().warn("yt-dlp is already up to date or update failed.");
                return false;
            }
            getLogger().info("yt-dlp update completed.");
            return true;
        } catch (Exception e) {
            getLogger().error("An exception occurred during update process", e);
            return false;
        }
    }

    private static List<String> buildDownloadCommand(String youtubeUrl, boolean isPlaylist, String formatId, Path tempOutputDir) {
        List<String> command = new ArrayList<>();
        command.add(PathUtils.getBinDirectory().resolve("yt-dlp.exe").toString());
        
        command.add("--encoding");
        command.add("utf-8");

        command.add("--user-agent");
        command.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36");
        
        command.add("--proxy");
        command.add("http://1.1.1.1:80");
        
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
        command.add(tempOutputDir.toString()); // Always download to the temporary directory
        
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
            if (session.isOpen()) {
                String jsonMessage = objectMapper.writeValueAsString(message);
                session.getRemote().sendString(jsonMessage);
            }
        } catch (Exception e) {
            getLogger().warn("Failed to send WebSocket message", e);
        }
    }
    
    private static void deleteDirectoryRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
            getLogger().info("Successfully deleted directory: {}", path);
        } catch (IOException e) {
            getLogger().error("Failed to delete directory: {}", path, e);
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
            reader.lines().forEach(consumer);
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