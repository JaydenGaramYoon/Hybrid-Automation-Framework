package triage.model.history;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class RecentHistory {

	@JsonIgnore
	@JsonProperty("total_runs")
	private int totalRuns;

	@JsonIgnore
	@JsonProperty("pass_count")
	private int passCount;

	@JsonIgnore
	@JsonProperty("fail_count")
	private int failCount;

	@JsonIgnore
	@JsonProperty("skip_count")
	private int skipCount;

	@JsonIgnore
	@JsonProperty("failure_rate")
	private double failureRate;

	@JsonIgnore
	@JsonProperty("status_change_count")
	private int statusChangeCount;

	@JsonProperty("runs")
	private List<HistoryRecord> runs;

	public RecentHistory() {
	}

	public RecentHistory(int totalRuns, int passCount, int failCount, int skipCount, double failureRate,
			List<HistoryRecord> runs) {
		this.totalRuns = totalRuns;
		this.passCount = passCount;
		this.failCount = failCount;
		this.skipCount = skipCount;
		this.failureRate = failureRate;
		this.runs = runs;
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

	public List<HistoryRecord> getRuns() {
		return runs;
	}

	public void setRuns(List<HistoryRecord> runs) {
		this.runs = runs;
	}

	@Override
	public String toString() {
		return "RecentHistory [totalRuns=" + totalRuns + ", passCount=" + passCount + ", failCount=" + failCount
				+ ", failureRate=" + failureRate + ", statusChangeCount=" + statusChangeCount + "]";
	}
}
