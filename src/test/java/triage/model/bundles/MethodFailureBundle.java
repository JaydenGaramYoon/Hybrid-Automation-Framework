package triage.model.bundles;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonProperty;

import triage.model.history.MinimalFailureRecord;
import triage.model.metadata.AnalysisMetadata;

public class MethodFailureBundle {

	@JsonProperty("method")
	private String method;

	@JsonProperty("class")
	private String className;

	@JsonProperty("parameters")
	private List<String> parameters;

	@JsonProperty("analysis")
	private AnalysisMetadata analysis;

	@JsonProperty("failures")
	private List<MinimalFailureRecord> failures;

	public MethodFailureBundle() {
	}

	public MethodFailureBundle(String method, String className) {
		this.method = method;
		this.className = className;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getClassName() {
		return className;
	}

	public void setClassName(String className) {
		this.className = className;
	}

	public List<String> getParameters() {
		return parameters;
	}

	public void setParameters(List<String> parameters) {
		this.parameters = parameters;
	}

	public AnalysisMetadata getAnalysis() {
		return analysis;
	}

	public void setAnalysis(AnalysisMetadata analysis) {
		this.analysis = analysis;
	}

	public List<MinimalFailureRecord> getFailures() {
		return failures;
	}

	public void setFailures(List<MinimalFailureRecord> failures) {
		this.failures = failures;
	}

	@Override
	public String toString() {
		return "MethodFailureBundle [method=" + method + ", class=" + className + ", parameters=" + parameters.size()
				+ ", failures=" + (failures != null ? failures.size() : 0) + "]";
	}
}
