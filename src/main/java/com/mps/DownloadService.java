

package com.mps;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadService {

    private static Logger logger;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ExecutorService downloadExecutor = Executors.newCachedThreadPool();
    private static final Pattern progressPattern = Pattern.compile("\\[download\\]\\s+([0-9.]+)%\\s+of\\s+.*?\\s+at\\s+(.*?\\/s)");
    private static final Pattern playlistProgressPattern = Pattern.compile("\\[download\\] Downloading item (\\d+) of (\\d+)");

    private static Logger getLogger() {
        if (logger == null) {
            logger = LoggerFactory.getLogger(DownloadService.class);
        }
        return logger;
    }

    public static void startDownload(String youtubeUrl, boolean isPlaylist, String formatId, Session session) {
        downloadExecutor.submit(() -> runDownloadFlow(youtubeUrl, isPlaylist, formatId, session));
    }

    private static void runDownloadFlow(String youtubeUrl, boolean isPlaylist, String formatId, Session session) {
        sendMessage(session, DownloadMessage.starting());
        DownloadResult result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, session);

        if (!result.isSuccess() && result.getErrorMessage().contains("Requested format is not available")) {
            getLogger().warn("Download failed with a potential version issue. Attempting update.");
            boolean updateSucceeded = performUpdate(session);

            if (updateSucceeded) {
                getLogger().info("Update seems successful. Retrying download.");
                sendMessage(session, DownloadMessage.starting());
                result = performDownloadAttempt(youtubeUrl, isPlaylist, formatId, session);
            }
        }
        
        if (result.isSuccess()) {
            sendMessage(session, DownloadMessage.success());
        } else {
            sendMessage(session, DownloadMessage.error(result.getErrorMessage()));
        }
    }

    private static DownloadResult performDownloadAttempt(String youtubeUrl, boolean isPlaylist, String formatId, Session session) {
        List<String> command = buildDownloadCommand(youtubeUrl, isPlaylist, formatId);
        getLogger().info("Executing command: {}", String.join(" ", command));
        
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(PathUtils.getApplicationDirectory().toFile());
            Process process = processBuilder.start();
            
            StringBuilder errorOutput = new StringBuilder();
            final AtomicBoolean processingMessageSent = new AtomicBoolean(false);
            final AtomicBoolean mergingMessageSent = new AtomicBoolean(false);
            
            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), line -> {
                getLogger().info("yt-dlp: {}", line);
                
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
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), line -> {
                getLogger().error("yt-dlp-error: {}", line);
                errorOutput.append(line).append("\n");
            });
            
            downloadExecutor.submit(outputGobbler);
            downloadExecutor.submit(errorGobbler);

            int exitCode = process.waitFor();

            if (exitCode == 0 && isPlaylist && formatId == null && !processingMessageSent.get()) {
                 sendMessage(session, DownloadMessage.processing());
            }
            
            if (exitCode == 0) {
                getLogger().info("Process finished successfully.");
                return new DownloadResult(true, null);
            } else {
                return new DownloadResult(false, errorOutput.toString());
            }

        } catch (Exception e) {
            getLogger().error("An exception occurred during download attempt", e);
            return new DownloadResult(false, "An internal application error occurred: " + e.getMessage());
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
            
            downloadExecutor.submit(outputGobbler);
            downloadExecutor.submit(errorGobbler);
            int exitCode = process.waitFor();
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

    private static List<String> buildDownloadCommand(String youtubeUrl, boolean isPlaylist, String formatId) {
        List<String> command = new ArrayList<>();
        command.add(PathUtils.getBinDirectory().resolve("yt-dlp.exe").toString());
        command.add("--no-check-certificates");
        command.add("--progress");

        
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
        command.add(Paths.get(System.getProperty("user.home"), "Downloads").toString());
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

    private static class StreamGobbler implements Runnable {
        private final BufferedReader reader;
        private final Consumer<String> consumer;
        public StreamGobbler(java.io.InputStream inputStream, Consumer<String> consumer) {
            this.reader = new BufferedReader(new InputStreamReader(inputStream));
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
        public DownloadResult(boolean success, String errorMessage) {
            this.success = success;
            this.errorMessage = errorMessage == null ? "" : errorMessage;
        }
        public boolean isSuccess() {
            return success;
        }
        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
