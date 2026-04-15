package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class ErrorInfo {

	@JsonProperty("exception_type")
	private String exceptionType;

	@JsonProperty("exception_message")
	private String exceptionMessage;

	@JsonProperty("stack_trace")
	private String stackTrace;

	@JsonProperty("error_fingerprint")
	private String errorFingerprint;

	@JsonProperty("root_cause_class")
	private String rootCauseClass;

	@JsonProperty("root_cause_method")
	private String rootCauseMethod;

	@JsonProperty("test_method")
	private String testMethod;

	@JsonProperty("test_parameters")
	private String testParameters;

	@JsonProperty("data_status")
	private String dataStatus;

	@JsonProperty("test_focus")
	private String testFocus;

	@JsonProperty("tc_id")
	private String tcId;

	@JsonProperty("test_data")
	private String testData;

	@JsonProperty("expected")
	private String expected;

	@JsonProperty("actual")
	private String actual;

	public ErrorInfo() {
	}

	public ErrorInfo(String exceptionType, String stackTrace, String errorFingerprint) {
		this.exceptionType = exceptionType;
		this.stackTrace = stackTrace;
		this.errorFingerprint = errorFingerprint;
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

	public String getStackTrace() {
		return stackTrace;
	}

	public void setStackTrace(String stackTrace) {
		this.stackTrace = stackTrace;
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

	public String getTestMethod() {
		return testMethod;
	}

	public void setTestMethod(String testMethod) {
		this.testMethod = testMethod;
	}

	public String getTestParameters() {
		return testParameters;
	}

	public void setTestParameters(String testParameters) {
		this.testParameters = testParameters;
	}

	public String getDataStatus() {
		return dataStatus;
	}

	public void setDataStatus(String dataStatus) {
		this.dataStatus = dataStatus;
	}

	public String getTestFocus() {
		return testFocus;
	}

	public void setTestFocus(String testFocus) {
		this.testFocus = testFocus;
	}

	public String getTcId() {
		return tcId;
	}

	public void setTcId(String tcId) {
		this.tcId = tcId;
	}

	public String getTestData() {
		return testData;
	}

	public void setTestData(String testData) {
		this.testData = testData;
	}

	public String getExpected() {
		return expected;
	}

	public void setExpected(String expected) {
		this.expected = expected;
	}

	public String getActual() {
		return actual;
	}

	public void setActual(String actual) {
		this.actual = actual;
	}

	@Override
	public String toString() {
		return "ErrorInfo [exceptionType=" + exceptionType + ", errorFingerprint=" + errorFingerprint + "]";
	}
}
