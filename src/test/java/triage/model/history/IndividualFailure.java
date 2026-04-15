package triage.model.history;

import com.fasterxml.jackson.annotation.JsonProperty;

import triage.model.metadata.EnvironmentSnapshot;
import triage.model.metadata.ErrorInfo;
import triage.model.metadata.MetaInfo;
import triage.model.metadata.ScreenshotInfo;
import triage.model.metadata.TimingInfo;

public class IndividualFailure {

	@JsonProperty("meta")
	private MetaInfo meta;

	@JsonProperty("timing")
	private TimingInfo timing;

	@JsonProperty("error")
	private ErrorInfo error;

	@JsonProperty("screenshot")
	private ScreenshotInfo screenshot;

	@JsonProperty("environment")
	private EnvironmentSnapshot environment;

	@com.fasterxml.jackson.annotation.JsonIgnore
	@JsonProperty("recent_history")
	private RecentHistory recentHistory;

	@com.fasterxml.jackson.annotation.JsonIgnore
	@JsonProperty("classification")
	private String classification;

	public IndividualFailure() {
		this.meta = new MetaInfo();
		this.timing = new TimingInfo();
	}

	public IndividualFailure(String testId, String runId) {
		this();
		this.meta.setTestId(testId);
		this.meta.setRunId(runId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getTestId() {
		return meta != null ? meta.getTestId() : null;
	}

	public void setTestId(String testId) {
		if (meta == null) meta = new MetaInfo();
		meta.setTestId(testId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getRunId() {
		return meta != null ? meta.getRunId() : null;
	}

	public void setRunId(String runId) {
		if (meta == null) meta = new MetaInfo();
		meta.setRunId(runId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getFailureId() {
		return meta != null ? meta.getFailureId() : null;
	}

	public void setFailureId(String failureId) {
		if (meta == null) meta = new MetaInfo();
		meta.setFailureId(failureId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getStatus() {
		return meta != null ? meta.getStatus() : null;
	}

	public void setStatus(String status) {
		if (meta == null) meta = new MetaInfo();
		meta.setStatus(status);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getStartedAt() {
		return timing != null ? timing.getStartedAt() : null;
	}

	public void setStartedAt(String startedAt) {
		if (timing == null) timing = new TimingInfo();
		timing.setStartedAt(startedAt);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getEndedAt() {
		return timing != null ? timing.getEndedAt() : null;
	}

	public void setEndedAt(String endedAt) {
		if (timing == null) timing = new TimingInfo();
		timing.setEndedAt(endedAt);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public long getStartedAtEpochMs() {
		return timing != null ? timing.getStartedAtEpochMs() : 0;
	}

	public void setStartedAtEpochMs(long startedAtEpochMs) {
		if (timing == null) timing = new TimingInfo();
		timing.setStartedAtEpochMs(startedAtEpochMs);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public long getEndedAtEpochMs() {
		return timing != null ? timing.getEndedAtEpochMs() : 0;
	}

	public void setEndedAtEpochMs(long endedAtEpochMs) {
		if (timing == null) timing = new TimingInfo();
		timing.setEndedAtEpochMs(endedAtEpochMs);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public long getDurationMs() {
		return timing != null ? timing.getDurationMs() : 0;
	}

	public void setDurationMs(long durationMs) {
		if (timing == null) timing = new TimingInfo();
		timing.setDurationMs(durationMs);
	}

	public MetaInfo getMeta() {
		return meta;
	}

	public void setMeta(MetaInfo meta) {
		this.meta = meta;
	}

	public TimingInfo getTiming() {
		return timing;
	}

	public void setTiming(TimingInfo timing) {
		this.timing = timing;
	}

	public ErrorInfo getError() {
		return error;
	}

	public void setError(ErrorInfo error) {
		this.error = error;
	}

	public ScreenshotInfo getScreenshot() {
		return screenshot;
	}

	public void setScreenshot(ScreenshotInfo screenshot) {
		this.screenshot = screenshot;
	}

	public EnvironmentSnapshot getEnvironment() {
		return environment;
	}

	public void setEnvironment(EnvironmentSnapshot environment) {
		this.environment = environment;
	}

	public RecentHistory getRecentHistory() {
		return recentHistory;
	}

	public void setRecentHistory(RecentHistory recentHistory) {
		this.recentHistory = recentHistory;
	}

	public String getClassification() {
		return classification;
	}

	public void setClassification(String classification) {
		this.classification = classification;
	}

	@Override
	public String toString() {
		String testId = meta !=null ? meta.getTestId() : "null";
		return "IndividualFailure [testId=" + testId + "]";
	}
}
