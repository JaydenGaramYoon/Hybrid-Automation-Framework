package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CauseInfo {

	@JsonProperty("skip_reason")
	private String skipReason;

	@JsonProperty("exception_type")
	private String exceptionType;

	@JsonProperty("exception_message")
	private String exceptionMessage;

	@JsonProperty("stack_trace")
	private String stackTrace;

	public CauseInfo() {
	}

	public CauseInfo(String skipReason, String exceptionType, String exceptionMessage, String stackTrace) {
		this.skipReason = skipReason;
		this.exceptionType = exceptionType;
		this.exceptionMessage = exceptionMessage;
		this.stackTrace = stackTrace;
	}

	public String getSkipReason() {
		return skipReason;
	}

	public void setSkipReason(String skipReason) {
		this.skipReason = skipReason;
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
}
