package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TimingInfo {

	@JsonProperty("started_at")
	private String startedAt;

	@JsonProperty("ended_at")
	private String endedAt;

	@JsonProperty("started_at_epoch_ms")
	private long startedAtEpochMs;

	@JsonProperty("ended_at_epoch_ms")
	private long endedAtEpochMs;

	@JsonProperty("duration_ms")
	private long durationMs;

	@JsonProperty("invocation_count")
	private Integer invocationCount;

	public TimingInfo() {
	}

	public TimingInfo(String startedAt, String endedAt, long startedAtEpochMs, long endedAtEpochMs, long durationMs) {
		this.startedAt = startedAt;
		this.endedAt = endedAt;
		this.startedAtEpochMs = startedAtEpochMs;
		this.endedAtEpochMs = endedAtEpochMs;
		this.durationMs = durationMs;
	}

	public String getStartedAt() {
		return startedAt;
	}

	public void setStartedAt(String startedAt) {
		this.startedAt = startedAt;
	}

	public String getEndedAt() {
		return endedAt;
	}

	public void setEndedAt(String endedAt) {
		this.endedAt = endedAt;
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

	public Integer getInvocationCount() {
		return invocationCount;
	}

	public void setInvocationCount(Integer invocationCount) {
		this.invocationCount = invocationCount;
	}
}
