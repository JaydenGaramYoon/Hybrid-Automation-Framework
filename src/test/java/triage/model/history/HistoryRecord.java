package triage.model.history;

import com.fasterxml.jackson.annotation.JsonProperty;

public class HistoryRecord {

	@JsonProperty("run_id")
	private String runId;

	@JsonProperty("test_id")
	private String testId;

	@JsonProperty("status")
	private String status;

	@JsonProperty("started_at_epoch_ms")
	private long startedAtEpochMs;

	@JsonProperty("ended_at_epoch_ms")
	private long endedAtEpochMs;

	@JsonProperty("duration_ms")
	private long durationMs;

	@JsonProperty("exception_type")
	private String exceptionType;

	@JsonProperty("exception_message")
	private String exceptionMessage;

	@JsonProperty("error_fingerprint")
	private String errorFingerprint;

	@JsonProperty("tc_id")
	private String tcId;

	public HistoryRecord() {
	}

	public HistoryRecord(String runId, String testId, String status, long startedAtEpochMs, long endedAtEpochMs,
			long durationMs, String exceptionType, String exceptionMessage, String errorFingerprint) {
		this.runId = runId;
		this.testId = testId;
		this.status = status;
		this.startedAtEpochMs = startedAtEpochMs;
		this.endedAtEpochMs = endedAtEpochMs;
		this.durationMs = durationMs;
		this.exceptionType = exceptionType;
		this.exceptionMessage = exceptionMessage;
		this.errorFingerprint = errorFingerprint;
	}

	public String getRunId() {
		return runId;
	}

	public void setRunId(String runId) {
		this.runId = runId;
	}

	public String getTestId() {
		return testId;
	}

	public void setTestId(String testId) {
		this.testId = testId;
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

	public long getEndedAtEpochMs() {
		return endedAtEpochMs;
	}

	public void setEndedAtEpochMs(long endedAtEpochMs) {
		this.endedAtEpochMs = endedAtEpochMs;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
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

	public String getTcId() {
		return tcId;
	}

	public void setTcId(String tcId) {
		this.tcId = tcId;
	}

	@Override
	public String toString() {
		return "HistoryRecord [runId=" + runId + ", testId=" + testId + ", status=" + status + "]";
	}
}
