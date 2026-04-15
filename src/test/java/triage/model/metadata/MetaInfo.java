package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class MetaInfo {

	@JsonProperty("failure_id")
	private String failureId;

	@JsonProperty("run_id")
	private String runId;

	@JsonProperty("test_id")
	private String testId;

	@JsonProperty("status")
	private String status;

	@JsonProperty("tc_id")
	private String tcId;

	public MetaInfo() {
	}

	public MetaInfo(String failureId, String runId, String testId, String status) {
		this.failureId = failureId;
		this.runId = runId;
		this.testId = testId;
		this.status = status;
	}

	public String getFailureId() {
		return failureId;
	}

	public void setFailureId(String failureId) {
		this.failureId = failureId;
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

	public String getTcId() {
		return tcId;
	}

	public void setTcId(String tcId) {
		this.tcId = tcId;
	}
}
