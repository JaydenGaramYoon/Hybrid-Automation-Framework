package triage.model.history;

import com.fasterxml.jackson.annotation.JsonProperty;

import triage.model.metadata.CauseInfo;
import triage.model.metadata.EnvironmentSnapshot;
import triage.model.metadata.SkipMetaInfo;
import triage.model.metadata.TimingInfo;

public class IndividualSkip {

	@JsonProperty("meta")
	private SkipMetaInfo meta;

	@JsonProperty("timing")
	private TimingInfo timing;

	@JsonProperty("cause")
	private CauseInfo cause;

	@JsonProperty("environment")
	private EnvironmentSnapshot environment;

	public IndividualSkip() {
		this.meta = new SkipMetaInfo();
		this.meta.setStatus("SKIP");
		this.timing = new TimingInfo();
		this.cause = new CauseInfo();
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getSkipId() {
		return meta != null ? meta.getSkipId() : null;
	}

	public void setSkipId(String skipId) {
		if (meta == null) meta = new SkipMetaInfo();
		meta.setSkipId(skipId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getRunId() {
		return meta != null ? meta.getRunId() : null;
	}

	public void setRunId(String runId) {
		if (meta == null) meta = new SkipMetaInfo();
		meta.setRunId(runId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getTestId() {
		return meta != null ? meta.getTestId() : null;
	}

	public void setTestId(String testId) {
		if (meta == null) meta = new SkipMetaInfo();
		meta.setTestId(testId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getTcId() {
		return meta != null ? meta.getTcId() : null;
	}

	public void setTcId(String tcId) {
		if (meta == null) meta = new SkipMetaInfo();
		meta.setTcId(tcId);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getStatus() {
		return meta != null ? meta.getStatus() : "SKIP";
	}

	public void setStatus(String status) {
		if (meta == null) meta = new SkipMetaInfo();
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

	@com.fasterxml.jackson.annotation.JsonIgnore
	public Integer getInvocationCount() {
		return timing != null ? timing.getInvocationCount() : null;
	}

	public void setInvocationCount(Integer invocationCount) {
		if (timing == null) timing = new TimingInfo();
		timing.setInvocationCount(invocationCount);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getSkipReason() {
		return cause != null ? cause.getSkipReason() : null;
	}

	public void setSkipReason(String skipReason) {
		if (cause == null) cause = new CauseInfo();
		cause.setSkipReason(skipReason);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getExceptionType() {
		return cause != null ? cause.getExceptionType() : null;
	}

	public void setExceptionType(String exceptionType) {
		if (cause == null) cause = new CauseInfo();
		cause.setExceptionType(exceptionType);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getExceptionMessage() {
		return cause != null ? cause.getExceptionMessage() : null;
	}

	public void setExceptionMessage(String exceptionMessage) {
		if (cause == null) cause = new CauseInfo();
		cause.setExceptionMessage(exceptionMessage);
	}

	@com.fasterxml.jackson.annotation.JsonIgnore
	public String getStackTrace() {
		return cause != null ? cause.getStackTrace() : null;
	}

	public void setStackTrace(String stackTrace) {
		if (cause == null) cause = new CauseInfo();
		cause.setStackTrace(stackTrace);
	}

	public SkipMetaInfo getMeta() {
		return meta;
	}

	public void setMeta(SkipMetaInfo meta) {
		this.meta = meta;
	}

	public TimingInfo getTiming() {
		return timing;
	}

	public void setTiming(TimingInfo timing) {
		this.timing = timing;
	}

	public CauseInfo getCause() {
		return cause;
	}

	public void setCause(CauseInfo cause) {
		this.cause = cause;
	}

	public EnvironmentSnapshot getEnvironment() {
		return environment;
	}

	public void setEnvironment(EnvironmentSnapshot environment) {
		this.environment = environment;
	}
}

