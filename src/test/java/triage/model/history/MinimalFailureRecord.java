package triage.model.history;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MinimalFailureRecord {

	@JsonProperty("run_id")
	private String runId;

	@JsonProperty("parameter")
	private String parameter;

	@JsonProperty("status")
	private String status;

	@JsonProperty("started_at_epoch_ms")
	private long startedAtEpochMs;

	@JsonProperty("duration_ms")
	private long durationMs;

	@JsonProperty("screenshot")
	private String screenshotPath;

	@JsonProperty("exception_type")
	private String exceptionType;

	@JsonProperty("exception_message")
	private String exceptionMessage;

	@JsonProperty("error_fingerprint")
	private String errorFingerprint;

	public MinimalFailureRecord() {
	}

	public MinimalFailureRecord(String runId, String parameter, String status, long durationMs) {
		this.runId = runId;
		this.parameter = parameter;
		this.status = status;
		this.durationMs = durationMs;
	}

	public String getRunId() {
		return runId;
	}

	public void setRunId(String runId) {
		this.runId = runId;
	}

	public String getParameter() {
		return parameter;
	}

	public void setParameter(String parameter) {
		this.parameter = parameter;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public long getStartedAtEpochMs() {
		return startedAtEpochMs;
	}

	public void setStartedAtEpochMs(long startedAtEpochMs) {
		this.startedAtEpochMs = startedAtEpochMs;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
	}

	public String getScreenshotPath() {
		return screenshotPath;
	}

	public void setScreenshotPath(String screenshotPath) {
		this.screenshotPath = screenshotPath;
	}

	public String getExceptionType() {
		return exceptionType;
	}

	public void setExceptionType(String exceptionType) {
		this.exceptionType = exceptionType;
	}

	public String getExceptionMessage() {
		return exceptionMessage;
	}

	public void setExceptionMessage(String exceptionMessage) {
		this.exceptionMessage = exceptionMessage;
	}

	public String getErrorFingerprint() {
		return errorFingerprint;
	}

	public void setErrorFingerprint(String errorFingerprint) {
		this.errorFingerprint = errorFingerprint;
	}
}
