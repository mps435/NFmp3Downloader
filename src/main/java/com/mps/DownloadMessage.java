package com.mps;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DownloadMessage {

    private String type;
    private String percent;
    private String speed;
    private String error;
    private String path; 
    
    private String current;
    private String total;

    private Integer successCount;
    private Integer failureCount;
    private List<String> successfulFiles;

    
    public DownloadMessage(String type) {
        this.type = type;
    }

    public DownloadMessage(String type, String error) {
        this.type = type;
        this.error = error;
    }

    public static DownloadMessage starting() {
        return new DownloadMessage("starting");
    }

    public static DownloadMessage progress(String percent, String speed) {
        DownloadMessage msg = new DownloadMessage("progress");
        msg.setPercent(percent);
        msg.setSpeed(speed);
        return msg;
    }
    
    public static DownloadMessage playlistProgress(String current, String total) {
        DownloadMessage msg = new DownloadMessage("playlist_progress");
        msg.setCurrent(current);
        msg.setTotal(total);
        return msg;
    }

    public static DownloadMessage success() {
        return new DownloadMessage("success");
    }

    public static DownloadMessage error(String errorMessage) {
        return new DownloadMessage("error", errorMessage);
    }
    
    public static DownloadMessage updateCheck() {
        return new DownloadMessage("update_check");
    }

    public static DownloadMessage updating() {
        return new DownloadMessage("updating");
    }
    
    public static DownloadMessage processing() {
        return new DownloadMessage("processing");
    }

    public static DownloadMessage merging() {
        return new DownloadMessage("merging");
    }
    
    public static DownloadMessage cancelled() {
        return new DownloadMessage("cancelled");
    }

    public static DownloadMessage destinationSelected(String path) {
        DownloadMessage msg = new DownloadMessage("destination_selected");
        msg.setPath(path);
        return msg;
    }

    public static DownloadMessage queueComplete(int successCount, int failureCount, List<String> successfulFiles) {
        DownloadMessage msg = new DownloadMessage("queue_complete");
        msg.setSuccessCount(successCount);
        msg.setFailureCount(failureCount);
        msg.setSuccessfulFiles(successfulFiles);
        return msg;
    }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPercent() { return percent; }
    public void setPercent(String percent) { this.percent = percent; }
    public String getSpeed() { return speed; }
    public void setSpeed(String speed) { this.speed = speed; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getCurrent() { return current; }
    public void setCurrent(String current) { this.current = current; }
    public String getTotal() { return total; }
    public void setTotal(String total) { this.total = total; }
    public Integer getSuccessCount() { return successCount; }
    public void setSuccessCount(Integer successCount) { this.successCount = successCount; }
    public Integer getFailureCount() { return failureCount; }
    public void setFailureCount(Integer failureCount) { this.failureCount = failureCount; }
    public List<String> getSuccessfulFiles() { return successfulFiles; }
    public void setSuccessfulFiles(List<String> successfulFiles) { this.successfulFiles = successfulFiles; }
}