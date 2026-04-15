package utils.reports;

import com.aventstack.extentreports.ExtentTest;

/**
 * Writes formatted lines to the current {@link ExtentTest}. All methods no-op if no test is bound.
 */
public class ExtentLogger {

	private static ExtentTest currentTest() {
		return ExtentManager.getTest();
	}

	public static void info(String message) {
		if (currentTest() != null) {
			currentTest().info(message);
		}
	}

	public static void error(String message) {
		if (currentTest() != null) {
			currentTest().fail("[ERROR] " + message);
		}
	}

	public static void testInfo(String focus, String status, String expected) {
		String formatted = String.format("[TEST INFO] TEST FOCUS: %s | DATA STATUS: %s | EXPECTED: %s", focus, status,
				expected);
		if (currentTest() != null) {
			currentTest().info(formatted);
		}
	}

	public static void testInfo(String focus, String status, String invalidReason, String expected) {
		String formatted = String.format("[TEST INFO] TEST FOCUS: %s | DATA STATUS: %s | INVALID_REASON: %s | EXPECTED: %s", 
				focus, status, invalidReason, expected);
		if (currentTest() != null) {
			currentTest().info(formatted);
		}
	}

	public static void testData(String data) {
		String formatted = String.format("[TEST DATA] %s", data);
		if (currentTest() != null) {
			currentTest().info(formatted);
		}
	}

	/** Logs optional Excel {@code tc_id} when present (e.g. TC001). */
	public static void tcId(String tcId) {
		if (tcId == null || tcId.isBlank()) {
			return;
		}
		if (currentTest() != null) {
			currentTest().info("[TC ID] " + tcId.trim());
		}
	}

	public static void logInInfo(String email, String pwd) {

		String formatted = String.format("[INPUT] email: %s | pwd: %s", email, pwd);

		if (currentTest() != null) {
			currentTest().info(formatted);
		}
	}

	public static void expected(String expected) {
		if (currentTest() != null) {
			currentTest().info("[EXPECTED] " + expected);
		}
	}

	public static void actual(String actual) {
		if (currentTest() != null) {
			currentTest().info("[ACTUAL] " + actual);
		}
	}
}
