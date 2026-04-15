package utils.data.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import utils.run.RunContext;

/**
 * Checkout test row: user, payment hints, country, product, and expected confirmation message.
 * {@link #fromHashMap(HashMap)} updates {@link RunContext} for parameterized triage labels.
 */
public class CheckOutData {
	/** Optional Excel column {@code tc_id} (e.g. TC001) for reports and failure payloads. */
	private String tcId;
	private String focus;
	private String firstName;
	private String lastName;
	private String email;
	private String password;
	private String cardNumber;
	private String cvv;
	private String coupon;
	private String country;
	private String status;
	private String invalidReason;
	private String expectedMsg;
	private String product;

	/**
	 * @param data Excel row (keys: focus, first_name, last_name, email, password, credit_card_number, cvv, coupon, country, status, invalid_reason, expected_message, product)
	 * @return populated checkout data with {@link RunContext} parameter/invalid-reason set
	 */
	public static CheckOutData fromHashMap(HashMap<String, String> data) {
		CheckOutData checkout = new CheckOutData();
		checkout.tcId = data.get("tc_id");
		checkout.focus = data.get("focus");
		checkout.firstName = data.get("first_name");
		checkout.lastName = data.get("last_name");
		checkout.email = data.get("email");
		checkout.password = data.get("password");
		checkout.cardNumber = data.get("credit_card_number");
		checkout.cvv = data.get("cvv");
		checkout.coupon = data.get("coupon");
		checkout.country = data.get("country");
		checkout.status = data.get("status");
		checkout.invalidReason = data.get("invalid_reason");
		checkout.expectedMsg = data.get("expected_message");
		checkout.product = data.get("product");
		
		String paramDescription = (checkout.status + "-" + checkout.focus).toUpperCase();
		RunContext.setParameterDescription(paramDescription);
		
		if (checkout.status.toLowerCase().contains("invalid") && checkout.invalidReason != null && !checkout.invalidReason.isEmpty()) {
			RunContext.setInvalidReason(checkout.invalidReason.toUpperCase());
		} else {
			RunContext.setInvalidReason(null);
		}
		
		return checkout;
	}

	public String getTcId() {
		return tcId;
	}

	public String getFocus() {
		return focus;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public String getEmail() {
		return email;
	}

	public String getPassword() {
		return password;
	}

	public String getCardNumber() {
		return cardNumber;
	}

	public String getCvv() {
		return cvv;
	}

	public String getCoupon() {
		return coupon;
	}

	public String getCountry() {
		return country;
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

	public String getFullName() {
		return firstName + " " + lastName;
	}

	public String getProduct() {
		return product;
	}

	public String getOrderInfo() {
		return String.format("Card: %s | Name: %s | Coupon: %s | Country: %s", cardNumber, getFullName(), coupon,
				country);
	}

	/** Ordered map for failure bundles and AI payloads. */
	public Map<String, String> toPayloadMap() {
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("tc_id", tcId != null ? tcId : "");
		payload.put("focus", focus);
		payload.put("first_name", firstName);
		payload.put("last_name", lastName);
		payload.put("email", email);
		payload.put("password", password);
		payload.put("card_number", cardNumber);
		payload.put("cvv", cvv);
		payload.put("coupon", coupon);
		payload.put("country", country);
		payload.put("status", status);
		payload.put("invalid_reason", invalidReason);
		payload.put("expected_message", expectedMsg);
		payload.put("product", product);
		return payload;
	}
}