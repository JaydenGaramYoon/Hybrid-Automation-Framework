package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ScreenshotInfo {

	@JsonProperty("captured")
	private boolean captured;

	@JsonProperty("file_path")
	private String filePath;

	@JsonProperty("relative_path")
	private String relativePath;

	@JsonProperty("timestamp")
	private String timestamp;

	public ScreenshotInfo() {
	}

	public ScreenshotInfo(boolean captured, String filePath, String relativePath, String timestamp) {
		this.captured = captured;
		this.filePath = filePath;
		this.relativePath = relativePath;
		this.timestamp = timestamp;
	}

	public boolean isCaptured() {
		return captured;
	}

	public void setCaptured(boolean captured) {
		this.captured = captured;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public String getRelativePath() {
		return relativePath;
	}

	public void setRelativePath(String relativePath) {
		this.relativePath = relativePath;
	}

	public String getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}

	@Override
	public String toString() {
		return "ScreenshotInfo [captured=" + captured + ", relativePath=" + relativePath + "]";
	}
}
