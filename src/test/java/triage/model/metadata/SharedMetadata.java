package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SharedMetadata {

	@JsonProperty("exception_type")
	private String exceptionType;

	@JsonProperty("exception_message")
	private String exceptionMessage;

	@JsonProperty("error_fingerprint")
	private String errorFingerprint;

	@JsonProperty("root_cause_class")
	private String rootCauseClass;

	@JsonProperty("root_cause_method")
	private String rootCauseMethod;

	@JsonProperty("environment")
	private EnvironmentSnapshot environment;

	public SharedMetadata() {
	}

	public SharedMetadata(String exceptionType, String errorFingerprint, EnvironmentSnapshot environment) {
		this.exceptionType = exceptionType;
		this.errorFingerprint = errorFingerprint;
		this.environment = environment;
	}

	public String getExceptionType() {
		return exceptionType;
	}

	public void setExceptionType(String exceptionType) {
		this.exceptionType = exceptionType;
	}

	public String getExceptionMessage() {
		return exceptionMessage;
	}

	public void setExceptionMessage(String exceptionMessage) {
		this.exceptionMessage = exceptionMessage;
	}

	public String getErrorFingerprint() {
		return errorFingerprint;
	}

	public void setErrorFingerprint(String errorFingerprint) {
		this.errorFingerprint = errorFingerprint;
	}

	public String getRootCauseClass() {
		return rootCauseClass;
	}

	public void setRootCauseClass(String rootCauseClass) {
		this.rootCauseClass = rootCauseClass;
	}

	public String getRootCauseMethod() {
		return rootCauseMethod;
	}

	public void setRootCauseMethod(String rootCauseMethod) {
		this.rootCauseMethod = rootCauseMethod;
	}

	public EnvironmentSnapshot getEnvironment() {
		return environment;
	}

	public void setEnvironment(EnvironmentSnapshot environment) {
		this.environment = environment;
	}
}
