package utils.listeners;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

import triage.history.TestRunHistoryListener;
import utils.config.ConfigUtils;
import utils.reports.ExtentLogger;
import utils.reports.ExtentManager;
import utils.run.RunContext;

/**
 * TestNG {@link IRetryAnalyzer}: honors {@link RunContext#getSkipReason()} (e.g. skips {@code data_not_found});
 * caps attempts at {@code retry_count}.
 */
public class Retry implements IRetryAnalyzer {
	private static final Logger logger = LogManager.getLogger(Retry.class);

	private static final Map<String, Integer> testExecutionCounts = new HashMap<>();
	private int maxRetries = Integer.parseInt(ConfigUtils.get("retry_count"));

	public boolean retry(ITestResult result) {
		String skipReason = RunContext.getSkipReason();
		if ("data_not_found".equals(skipReason) || "setup_failed".equals(skipReason) || "unknown".equals(skipReason)
				|| skipReason == null || skipReason.isEmpty()) {
			RunContext.setSkipReason("retry_exhausted");
			return false;
		}

		String testKey = generateTestKey(result);
		int testExecutionCount = testExecutionCounts.getOrDefault(testKey, 0);

		if (testExecutionCount < maxRetries) {
			testExecutionCount++;
			testExecutionCounts.put(testKey, testExecutionCount);

			if ("env_not_ready".equals(skipReason)) {
				long waitMillis = testExecutionCount * 1000;
				try {
					Thread.sleep(waitMillis);
				} catch (InterruptedException e) {
					logger.warn("Retry backoff interrupted: {}", e.getMessage());
					Thread.currentThread().interrupt();
				}
			}

			String retryMessage = "[RETRY] Retrying Test: " + result.getMethod().getMethodName() + " (Retry "
					+ testExecutionCount + "/" + maxRetries + ")";
			logger.info(retryMessage);

			if (ExtentManager.getTest() != null) {
				ExtentLogger.info(retryMessage);
			}

			TestRunHistoryListener.extractAndSetParameterDescription(result);

			return true;
		}

		RunContext.setSkipReason("retry_exhausted");
		testExecutionCounts.remove(testKey);

		return false;
	}

	private String generateTestKey(ITestResult result) {
		String methodName = result.getMethod().getMethodName();
		String className = result.getMethod().getRealClass().getSimpleName();
		String paramDesc = RunContext.getParameterDescription();
		if (paramDesc != null && !paramDesc.isEmpty()) {
			return className + "#" + methodName + "[" + paramDesc + "]";
		}
		return className + "#" + methodName;
	}
}
