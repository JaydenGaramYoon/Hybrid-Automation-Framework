package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class AnalysisMetadata {

	@JsonProperty("total_runs")
	private int totalRuns;

	@JsonProperty("pass_count")
	private int passCount;

	@JsonProperty("fail_count")
	private int failCount;

	@JsonIgnore
	@JsonProperty("skip_count")
	private int skipCount;

	@JsonProperty("failure_rate")
	private double failureRate;

	@JsonProperty("status_change_count")
	private int statusChangeCount;

	@JsonProperty("first_fail_run_id")
	private String firstFailRunId;

	@JsonProperty("last_updated_run_id")
	private String lastUpdatedRunId;

	public AnalysisMetadata() {
	}

	public int getTotalRuns() {
		return totalRuns;
	}

	public void setTotalRuns(int totalRuns) {
		this.totalRuns = totalRuns;
	}

	public int getPassCount() {
		return passCount;
	}

	public void setPassCount(int passCount) {
		this.passCount = passCount;
	}

	public int getFailCount() {
		return failCount;
	}

	public void setFailCount(int failCount) {
		this.failCount = failCount;
	}

	public int getSkipCount() {
		return skipCount;
	}

	public void setSkipCount(int skipCount) {
		this.skipCount = skipCount;
	}

	public double getFailureRate() {
		return failureRate;
	}

	public void setFailureRate(double failureRate) {
		this.failureRate = failureRate;
	}

	public int getStatusChangeCount() {
		return statusChangeCount;
	}

	public void setStatusChangeCount(int statusChangeCount) {
		this.statusChangeCount = statusChangeCount;
	}

	public String getFirstFailRunId() {
		return firstFailRunId;
	}

	public void setFirstFailRunId(String firstFailRunId) {
		this.firstFailRunId = firstFailRunId;
	}

	public String getLastUpdatedRunId() {
		return lastUpdatedRunId;
	}

	public void setLastUpdatedRunId(String lastUpdatedRunId) {
		this.lastUpdatedRunId = lastUpdatedRunId;
	}
}
