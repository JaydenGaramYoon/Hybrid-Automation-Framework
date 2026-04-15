package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SkipMetaInfo {

	@JsonProperty("skip_id")
	private String skipId;

	@JsonProperty("run_id")
	private String runId;

	@JsonProperty("test_id")
	private String testId;

	@JsonProperty("tc_id")
	private String tcId;

	@JsonProperty("status")
	private String status;

	public SkipMetaInfo() {
	}

	public SkipMetaInfo(String skipId, String runId, String testId, String status) {
		this.skipId = skipId;
		this.runId = runId;
		this.testId = testId;
		this.status = status;
	}

	public String getSkipId() {
		return skipId;
	}

	public void setSkipId(String skipId) {
		this.skipId = skipId;
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

	public String getTcId() {
		return tcId;
	}

	public void setTcId(String tcId) {
		this.tcId = tcId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	@Override
	public String toString() {
		return "SkipMetaInfo [skipId=" + skipId + ", runId=" + runId + ", testId=" + testId + ", status=" + status + "]";
	}
}
