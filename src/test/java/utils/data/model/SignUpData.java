package utils.data.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import utils.data.util.DataFakerUtils;
import utils.run.RunContext;

/**
 * Sign-up test row: focus, identity fields, validation scenario (valid/invalid), and expected UI message.
 * Built from Excel maps via {@link #fromHashMap(HashMap)}; updates {@link RunContext} for triage and reporting.
 */
public class SignUpData {
	/** Optional Excel column {@code tc_id} (e.g. TC001) for reports and failure payloads. */
	private String tcId;
	private String focus;
	private String fName;
	private String lName;
	private String email;
	private String phone;
	private String password;
	private String occupation;
	private String gender;
	private String status;
	private String invalidReason;
	private String expectedMsg;

	/**
	 * @param data Excel row (keys: focus, first_name, last_name, occupation, gender, status, invalid_reason, expected_message, etc.)
	 * @return instance with faker email/phone/password and {@link RunContext} parameter/invalid-reason set
	 */
	public static SignUpData fromHashMap(HashMap<String, String> data) {
		SignUpData signUpData = new SignUpData();
		signUpData.tcId = data.get("tc_id");
		signUpData.focus = data.get("focus").toLowerCase();
		signUpData.fName = data.get("first_name");
		signUpData.lName = data.get("last_name");
		signUpData.email = DataFakerUtils.getRandomEmail();
		signUpData.phone = DataFakerUtils.getRandomPhoneNumber();
		signUpData.occupation = data.get("occupation");
		signUpData.gender = data.get("gender");
		signUpData.password = DataFakerUtils.getRandomPwd();
		signUpData.status = data.get("status").toLowerCase();
		signUpData.invalidReason = data.get("invalid_reason");
		signUpData.expectedMsg = data.get("expected_message");
		
		// IMPORTANT: Set to RunContext immediately at the time of data loading
		String paramDescription = (signUpData.status + "-" + signUpData.focus).toUpperCase();
		RunContext.setParameterDescription(paramDescription);
		
		// Set invalid_reason only when INVALID status, clear when VALID
		if (signUpData.status.contains("invalid") && signUpData.invalidReason != null && !signUpData.invalidReason.isEmpty()) {
			RunContext.setInvalidReason(signUpData.invalidReason.toUpperCase());
		} else {
			// VALID status: Clear invalidReason from previous test
			RunContext.setInvalidReason(null);
		}
		
		return signUpData;
	}

	public String getTcId() {
		return tcId;
	}

	public String getFocus() {
		return focus;
	}

	public String getFName() {
		return fName;
	}

	public String getLName() {
		return lName;
	}

	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public String getPassword() {
		return password;
	}

	public String getOccupation() {
		return occupation;
	}

	public String getGender() {
		return gender;
	}

	public String getStatus() {
		return status;
	}

	public String getInvalidReason() {
		return invalidReason;
	}

	public String getExpectedMsg() {
		return expectedMsg;
	}

	/** One-line summary for logs (names, email, phone, occupation, gender, password). */
	public String getSignUpInfo() {
		return String.format(
				"first_name: %s | last_name: %s | email: %s | phone: %s | occupation: %s | gender: %s | password: %s",
				fName, lName, email, phone, occupation, gender, password);
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	/** Ordered map for failure bundles and AI payloads (matches triage keys). */
	public Map<String, String> toPayloadMap() {
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("tc_id", tcId != null ? tcId : "");
		payload.put("focus", focus);
		payload.put("first_name", fName);
		payload.put("last_name", lName);
		payload.put("email", email);
		payload.put("phone", phone);
		payload.put("occupation", occupation);
		payload.put("gender", gender);
		payload.put("password", password);
		payload.put("status", status);
		payload.put("invalid_reason", invalidReason);
		payload.put("expected_message", expectedMsg);
		return payload;
	}
}
