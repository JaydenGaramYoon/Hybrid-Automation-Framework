package utils.data.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import utils.config.ConfigUtils;

/**
 * Random emails, phones, passwords, CVV, coupons; lengths and charset rules from {@code config.properties}
 * ({@code email_length}, {@code password_min_upper_case_count}, etc.).
 */
public class DataFakerUtils {

	private static final Logger logger = LogManager.getLogger(DataFakerUtils.class);
	static int emailLength = ConfigUtils.getInt("email_length");
	static int phoneLength = ConfigUtils.getInt("phone_number_length");
	static int pwdLength = ConfigUtils.getInt("password_length");
	static int upper = ConfigUtils.getInt("password_min_upper_case_count");
	static int lower = ConfigUtils.getInt("password_min_lower_case_count");
	static int number = ConfigUtils.getInt("password_min_number_count");
	static String special = ConfigUtils.get("password_special_character");

	/** Random local part ({@code email_length} alphanumeric chars) + {@code @example.com} (RFC 2606 example domain). */
	public static String getRandomEmail() {
		String randomEmail = RandomUtils.getRandomAlphaNum(emailLength) + "@example.com";

		logger.info("Created email: {}", randomEmail);
		return randomEmail;
	}

	/** Digits only, length {@code phone_number_length}; first digit is not zero when length is greater than 1. */
	public static String getRandomPhoneNumber() {
		String randomPhone;
		if (phoneLength <= 1) {
			randomPhone = RandomUtils.getRandomNum(phoneLength);
		} else {
			String firstDigit;
			do {
				firstDigit = RandomUtils.getRandomNum(1);
			} while ("0".equals(firstDigit));
			randomPhone = firstDigit + RandomUtils.getRandomNum(phoneLength - 1);
		}

		logger.info("Created phone number: {}", randomPhone);
		return randomPhone;
	}

	/**
	 * Builds a password satisfying configured counts of upper, lower, numeric, and special characters,
	 * padded with alphanumeric to {@code password_length}.
	 */
	public static String getRandomPwd() {
		int rest = pwdLength - (upper + lower + number + special.length());

		String pwd = RandomUtils.getRandomAlpha(upper).toUpperCase() + RandomUtils.getRandomAlpha(lower).toLowerCase()
				+ RandomUtils.getRandomNum(number) + special + RandomUtils.getRandomAlphaNum(rest);

		logger.info("Created password: {}", pwd);
		return pwd;
	}

	public static String getRandomCvv() {
		String randomCVV = RandomUtils.getRandomNum(3);

		logger.info("Created cvv: {}", randomCVV);
		return randomCVV;
	}

	public static String getRandomCreditCardNum() {
		String randomCreditCardNum = RandomUtils.getRandomNum(16);

		logger.info("Created credit card number: {}", randomCreditCardNum);
		return randomCreditCardNum;
	}

	public static String getRandomCoupon() {
		String coupon = RandomUtils.getRandomAlpha(3).toUpperCase() + RandomUtils.getRandomNum(4);

		logger.info("Created coupon: {}", coupon);
		return coupon;
	}

}