

package com.mps;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DownloadMessage {

    private String type;
    private String percent;
    private String speed;
    private String error;
    
    private String current;
    private String total;

    
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
    
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getPercent() { return percent; }
    public void setPercent(String percent) { this.percent = percent; }
    public String getSpeed() { return speed; }
    public void setSpeed(String speed) { this.speed = speed; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getCurrent() { return current; }
    public void setCurrent(String current) { this.current = current; }
    public String getTotal() { return total; }
    public void setTotal(String total) { this.total = total; }
}
