package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SummaryMetadata {

	@JsonProperty("total_methods_with_failures")
	private int totalMethodsWithFailures;

	@JsonProperty("total_failure_records")
	private int totalFailureRecords;

	@JsonProperty("bug_count")
	private int bugCount;

	@JsonProperty("flaky_count")
	private int flakyCount;

	@JsonProperty("env_issue_count")
	private int envIssueCount;

	@JsonProperty("needs_review_count")
	private int needsReviewCount;

	public SummaryMetadata() {
	}

	public int getTotalMethodsWithFailures() {
		return totalMethodsWithFailures;
	}

	public void setTotalMethodsWithFailures(int totalMethodsWithFailures) {
		this.totalMethodsWithFailures = totalMethodsWithFailures;
	}

	public int getTotalFailureRecords() {
		return totalFailureRecords;
	}

	public void setTotalFailureRecords(int totalFailureRecords) {
		this.totalFailureRecords = totalFailureRecords;
	}

	public int getBugCount() {
		return bugCount;
	}

	public void setBugCount(int bugCount) {
		this.bugCount = bugCount;
	}

	public int getFlakyCount() {
		return flakyCount;
	}

	public void setFlakyCount(int flakyCount) {
		this.flakyCount = flakyCount;
	}

	public int getEnvIssueCount() {
		return envIssueCount;
	}

	public void setEnvIssueCount(int envIssueCount) {
		this.envIssueCount = envIssueCount;
	}

	public int getNeedsReviewCount() {
		return needsReviewCount;
	}

	public void setNeedsReviewCount(int needsReviewCount) {
		this.needsReviewCount = needsReviewCount;
	}
}
