package triage.model.bundles;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ConsolidatedFailureBundle {

	@JsonProperty("generated_at")
	private String generatedAt;

	@JsonProperty("total_runs_analyzed")
	private int totalRunsAnalyzed;

	@JsonProperty("methods_with_failures")
	private Map<String, MethodFailureBundle> methodsWithFailures;

	public ConsolidatedFailureBundle() {
	}

	public ConsolidatedFailureBundle(String generatedAt, int totalRunsAnalyzed) {
		this.generatedAt = generatedAt;
		this.totalRunsAnalyzed = totalRunsAnalyzed;
	}

	public String getGeneratedAt() {
		return generatedAt;
	}

	public void setGeneratedAt(String generatedAt) {
		this.generatedAt = generatedAt;
	}

	public int getTotalRunsAnalyzed() {
		return totalRunsAnalyzed;
	}

	public void setTotalRunsAnalyzed(int totalRunsAnalyzed) {
		this.totalRunsAnalyzed = totalRunsAnalyzed;
	}

	public Map<String, MethodFailureBundle> getMethodsWithFailures() {
		return methodsWithFailures;
	}

	public void setMethodsWithFailures(Map<String, MethodFailureBundle> methodsWithFailures) {
		this.methodsWithFailures = methodsWithFailures;
	}

	@Override
	public String toString() {
		return "ConsolidatedFailureBundle [generatedAt=" + generatedAt + ", totalRunsAnalyzed=" + totalRunsAnalyzed
				+ ", methodsCount=" + (methodsWithFailures != null ? methodsWithFailures.size() : 0) + "]";
	}
}
