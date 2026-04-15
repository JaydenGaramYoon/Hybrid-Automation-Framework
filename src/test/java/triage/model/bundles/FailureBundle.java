package triage.model.bundles;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import triage.model.history.IndividualFailure;

public class FailureBundle {

	@JsonProperty("run_id")
	private String runId;

	@JsonProperty("generated_at")
	private String generatedAt;

	@JsonProperty("failures")
	private List<IndividualFailure> failures;

	public FailureBundle() {
	}

	public FailureBundle(String runId, String generatedAt, List<IndividualFailure> failures) {
		this.runId = runId;
		this.generatedAt = generatedAt;
		this.failures = failures;
	}

	public String getRunId() {
		return runId;
	}

	public void setRunId(String runId) {
		this.runId = runId;
	}

	public String getGeneratedAt() {
		return generatedAt;
	}

	public void setGeneratedAt(String generatedAt) {
		this.generatedAt = generatedAt;
	}

	public List<IndividualFailure> getFailures() {
		return failures;
	}

	public void setFailures(List<IndividualFailure> failures) {
		this.failures = failures;
	}

	@Override
	public String toString() {
		return "FailureBundle [runId=" + runId + ", generatedAt=" + generatedAt + ", failures="
				+ (failures != null ? failures.size() : 0) + "]";
	}
}
