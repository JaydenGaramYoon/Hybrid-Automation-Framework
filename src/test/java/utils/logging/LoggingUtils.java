package utils.logging;

import org.apache.logging.log4j.Logger;

import utils.data.model.CartData;
import utils.data.model.CheckOutData;
import utils.data.model.LogInData;
import utils.reports.ExtentLogger;
import utils.reports.ExtentManager;

/**
 * Bridges Log4j2 and Extent: test metadata, expected/actual, and lifecycle log lines.
 * Extent calls are no-ops when no current {@link ExtentManager} test exists.
 */
public class LoggingUtils {
	private static final Logger logger = LoggerFactory.getLogger(LoggingUtils.class);

	public static void testInfo(utils.data.model.SignUpData data) {
		if (data != null) {
			String focus = data.getFocus() != null ? data.getFocus() : "unknown";
			String status = data.getStatus() != null ? data.getStatus() : "unknown";
			String expected = data.getExpectedMsg() != null ? data.getExpectedMsg() : "N/A";
			String invalidReason = data.getInvalidReason() != null ? data.getInvalidReason() : "N/A";
			testInfo(focus, status, invalidReason, expected);
		}
	}

	public static void testData(utils.data.model.SignUpData data) {
		if (data != null) {
			logTcIdIfPresent(data.getTcId());
			String dataInfo = data.getSignUpInfo();
			testData(dataInfo);
		}
	}

	public static void testData(LogInData data) {
		if (data != null) {
			logTcIdIfPresent(data.getTcId());
			testData(data.getLogInData());
		}
	}

	/** Cart / product line: logs {@code tc_id} then the data line. */
	public static void testData(CartData cartData, String dataLine) {
		if (cartData != null) {
			logTcIdIfPresent(cartData.getTcId());
		}
		testData(dataLine);
	}

	public static void testData(CheckOutData checkout) {
		if (checkout == null) {
			return;
		}
		logTcIdIfPresent(checkout.getTcId());
		testData(checkout.getOrderInfo());
	}

	public static void testData(String data) {
		logger.info("[TEST DATA] " + data);
		if (ExtentManager.getTest() != null) {
			ExtentLogger.testData(data);
		}
	}

	public static void info(String message) {
		logger.info(message);
	}

	public static void testInfo(String focus, String status, String expected) {
		String msg = "[TEST INFO] TEST FOCUS: " + focus + " | DATA STATUS: " + status + " | EXPECTED: " + expected;
		logger.info(msg);
		if (ExtentManager.getTest() != null) {
			ExtentLogger.testInfo(focus, status, expected);
		}
	}

	public static void testInfo(String focus, String status, String invalidReason, String expected) {
		String msg = "[TEST INFO] TEST FOCUS: " + focus + " | DATA STATUS: " + status + " | INVALID_REASON: " + invalidReason + " | EXPECTED: " + expected;
		logger.info(msg);
		if (ExtentManager.getTest() != null) {
			ExtentLogger.testInfo(focus, status, invalidReason, expected);
		}
	}

	public static void error(String message, Throwable t) {
		logger.error(message, t);
		if (ExtentManager.getTest() != null) {
			ExtentLogger.error(message);
		}
	}

	public static void expected(String expected) {
		String msg = "[EXPECTED] " + expected;
		logger.info(msg);
		if (ExtentManager.getTest() != null) {
			ExtentManager.getTest().info(msg);
		}
	}

	public static void actual(String actual) {
		String displayValue = (actual == null || actual.trim().isEmpty()) ? "(empty)" : actual;
		String msg = "[ACTUAL] " + displayValue;
		logger.info(msg);
		if (ExtentManager.getTest() != null) {
			ExtentManager.getTest().info(msg);
		}
	}

	public static void logTestStart(String testName) {
		String msg = "============ STARTING TEST: " + testName + " ============";
		logger.info(msg);
	}

	public static void logBeforeMethodStart(String methodName) {
		String msg = "============ STARTING BEFORE METHOD: " + methodName + " ============";
		logger.info(msg);
	}

	public static void logLoginAttempt(String email, String pwd) {
		String msg = "Logged in using: " + email + " | " + pwd;
		logger.info(msg);
	}

	public static void logClassStart(String testClass) {
		String msg = "============ STARTING CLASS: " + testClass + " ============";
		logger.info(msg);
	}

	private static void logTcIdIfPresent(String tcId) {
		if (tcId == null || tcId.isBlank()) {
			return;
		}
		String trimmed = tcId.trim();
		String msg = "[TC ID] " + trimmed;
		logger.info(msg);
		if (ExtentManager.getTest() != null) {
			ExtentLogger.tcId(trimmed);
		}
	}
}