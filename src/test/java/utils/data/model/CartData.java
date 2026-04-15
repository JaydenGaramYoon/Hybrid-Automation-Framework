package utils.data.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import utils.run.RunContext;

/**
 * Cart test row: login identity, product, valid/invalid product scenarios.
 * {@link #fromHashMap(HashMap)} sets {@link RunContext} parameter description and invalid-reason when applicable.
 */
public class CartData {

	/** Optional Excel column {@code tc_id} (e.g. TC001) for reports and failure payloads. */
	private String tcId;
	private String focus;
	private String firstName;
	private String lastName;
	private String email;
	private String password;
	private String product;
	private String status;
	private String invalidProduct;
	private String invalidReason;
	/** Optional Excel column {@code expected} — human-readable expectation for reports / Jira (failure bundle). */
	private String expected;

	public static CartData fromHashMap(HashMap<String, String> data) {
		CartData cartData = new CartData();
		cartData.tcId = data.get("tc_id");
		cartData.focus = data.get("focus");
		cartData.firstName = data.get("first_name");
		cartData.lastName = data.get("last_name");
		cartData.email = data.get("email");
		cartData.password = data.get("password");
		cartData.product = data.get("product");
		cartData.status = data.get("status");
		cartData.invalidProduct = data.get("invalid_product");
		cartData.invalidReason = data.get("invalid_reason");
		cartData.expected = data.get("expected");

		String paramDescription = (cartData.status + "-" + cartData.focus).toUpperCase();
		RunContext.setParameterDescription(paramDescription);
		
		if (cartData.status.toLowerCase().contains("invalid")) {
			String invalidReason = null;
			if (cartData.invalidProduct != null && !cartData.invalidProduct.isEmpty()) {
				invalidReason = cartData.invalidProduct.toUpperCase();
			} else if (cartData.invalidReason != null && !cartData.invalidReason.isEmpty()) {
				invalidReason = cartData.invalidReason.toUpperCase();
			}
			if (invalidReason != null) {
				RunContext.setInvalidReason(invalidReason);
			}
		} else {
			RunContext.setInvalidReason(null);
		}
		
		return cartData;
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

	public String getProduct() {
		return product;
	}

	public String getStatus() {
		return status;
	}

	public String getInvalidProduct() {
		return invalidProduct;
	}

	public String getInvalidReason() {
		return invalidReason;
	}

	public String getExpected() {
		return expected;
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
		payload.put("product", product);
		payload.put("status", status);
		payload.put("invalid_product", invalidProduct);
		payload.put("invalid_reason", invalidReason);
		payload.put("expected", expected != null ? expected : "");
		return payload;
	}

}
