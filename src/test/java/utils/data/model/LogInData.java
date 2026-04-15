package utils.data.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import utils.run.RunContext;

/**
 * Log-in test row: credentials, valid/invalid scenario, and optional invalid email/password/reason fields.
 * {@link #fromHashMap(HashMap)} sets {@link RunContext} parameter description and invalid-reason for triage.
 */
public class LogInData {
	/** Optional Excel column {@code tc_id} (e.g. TC001) for reports and failure payloads. */
	private String tcId;
	private String focus;
	private String email;
	private String password;
	private String status;
	private String invalidEmail;
	private String invalidPassword;
	private String invalidReason;
	private String expectedMsg;

	public static LogInData fromHashMap(HashMap<String, String> data) {
		LogInData logInData = new LogInData();
		logInData.tcId = data.get("tc_id");
		logInData.focus = data.get("focus");
		logInData.email = data.get("email");
		logInData.password = data.get("password");
		logInData.status = data.get("status");
		logInData.invalidEmail = data.get("invalid_email");
		logInData.invalidPassword = data.get("invalid_password");
		logInData.invalidReason = data.get("invalid_reason");
		logInData.expectedMsg = data.get("expected_message");
		
		String paramDescription = (logInData.status + "-" + logInData.focus).toUpperCase();
		RunContext.setParameterDescription(paramDescription);
		
		if (logInData.status.toLowerCase().contains("invalid")) {
			String invalidReasonToSet = null;
			if (logInData.invalidEmail != null && !logInData.invalidEmail.isEmpty()) {
				invalidReasonToSet = logInData.invalidEmail.toUpperCase();
			} else if (logInData.invalidPassword != null && !logInData.invalidPassword.isEmpty()) {
				invalidReasonToSet = logInData.invalidPassword.toUpperCase();
			} else if (logInData.invalidReason != null && !logInData.invalidReason.isEmpty()) {
				invalidReasonToSet = logInData.invalidReason.toUpperCase();
			}
			if (invalidReasonToSet != null) {
				RunContext.setInvalidReason(invalidReasonToSet);
			}
		} else {
			RunContext.setInvalidReason(null);
		}
		
		return logInData;
	}

	public String getTcId() {
		return tcId;
	}

	public String getFocus() {
		return focus;
	}

	public String getEmail() {
		return email;
	}

	public String getPassword() {
		return password;
	}

	public String getStatus() {
		return status;
	}

	public String getInvalidEmail() {
		return invalidEmail;
	}

	public String getInvalidPassword() {
		return invalidPassword;
	}

	public String getInvalidReason() {
		return invalidReason;
	}

	public String getExpectedMsg() {
		return expectedMsg;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	/** Short string for logs: email and password. */
	public String getLogInData() {
		return String.format("Email: %s | Password: %s", email, password);
	}

	/** Ordered map for failure/skip bundles and AI payloads. */
	public Map<String, String> toPayloadMap() {
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("tc_id", tcId != null ? tcId : "");
		payload.put("focus", focus);
		payload.put("email", email);
		payload.put("password", password);
		payload.put("status", status);
		payload.put("invalid_email", invalidEmail);
		payload.put("invalid_password", invalidPassword);
		payload.put("invalid_reason", invalidReason);
		payload.put("expected_message", expectedMsg);
		return payload;
	}

}
