package utils.data.util;

import org.apache.commons.lang3.RandomStringUtils;

/** Delegates to {@link RandomStringUtils} for alphanumeric, numeric, and alphabetic strings. */
public class RandomUtils {
	public static String getRandomAlphaNum(int totalCount) {		return RandomStringUtils.randomAlphanumeric(totalCount);	}
	public static String getRandomNum(int totalCount) {		String randomString = RandomStringUtils.randomNumeric(totalCount);		return randomString;	}
	public static String getRandomAlpha(int totalCount) {		String randomString = RandomStringUtils.randomAlphabetic(totalCount);		return randomString;	}}
