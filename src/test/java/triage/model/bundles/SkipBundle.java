package triage.model.bundles;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import triage.model.history.IndividualSkip;

public class SkipBundle {

	@JsonProperty("run_id")
	private String runId;

	@JsonProperty("generated_at")
	private String generatedAt;

	@JsonProperty("skips")
	private List<IndividualSkip> skips;

	public SkipBundle() {
	}

	public SkipBundle(String runId, String generatedAt, List<IndividualSkip> skips) {
		this.runId = runId;
		this.generatedAt = generatedAt;
		this.skips = skips;
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

	public List<IndividualSkip> getSkips() {
		return skips;
	}

	public void setSkips(List<IndividualSkip> skips) {
		this.skips = skips;
	}

	@Override
	public String toString() {
		return "SkipBundle [runId=" + runId + ", generatedAt=" + generatedAt + ", skips="
				+ (skips != null ? skips.size() : 0) + "]";
	}
}
