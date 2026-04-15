package triage.generator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.testng.ITestResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import com.fasterxml.jackson.databind.ObjectMapper;

import triage.model.history.HistoryRecord;
import triage.model.history.IndividualFailure;
import triage.model.history.RecentHistory;
import triage.model.metadata.EnvironmentSnapshot;
import triage.model.metadata.ErrorInfo;
import triage.model.metadata.ScreenshotInfo;
import utils.run.RunContext;
import utils.config.ConfigUtils;

/**
 * Builds {@link IndividualFailure} from {@link ITestResult}, DOM, driver, and
 * history.
 */
public class FailureBundleGenerator {

	private static final Logger logger = LogManager.getLogger(FailureBundleGenerator.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneId.of("UTC"));
	@SuppressWarnings("unused")
	private static final String HISTORY_FILE = "reports/history/test_run_history.jsonl";

	@SuppressWarnings("unused")
	private static int getMaxRecentRuns() {
		try {
			String configValue = utils.config.ConfigUtils.get("total_run_history");
			return Integer.parseInt(configValue);
		} catch (Exception e) {
			logger.warn("Failed to read total_run_history from config, using default 10", e);
			return 10;
		}
	}

	public static IndividualFailure createFromTestResult(ITestResult result, String screenshotPath, WebDriver driver) {
		Objects.requireNonNull(result, "result");

		IndividualFailure failure = new IndividualFailure();

		String testId = RunContext.testId(result);
		String runId = RunContext.getRunId();

		long startedAtEpochMs = result.getStartMillis();
		long endedAtEpochMs = result.getEndMillis() > 0 ? result.getEndMillis() : Instant.now().toEpochMilli();
		long durationMs = Math.max(0, endedAtEpochMs - startedAtEpochMs);

		String failureId = generateFailureId(runId, String.valueOf(endedAtEpochMs));

		failure.setTestId(testId);
		failure.setRunId(runId);
		failure.setFailureId(failureId);

		failure.setStartedAt(epochToIso(startedAtEpochMs));
		failure.setEndedAt(epochToIso(endedAtEpochMs));
		failure.setStartedAtEpochMs(startedAtEpochMs);
		failure.setEndedAtEpochMs(endedAtEpochMs);
		failure.setDurationMs(durationMs);

		failure.setError(createErrorInfo(result));
		if (failure.getError() != null && failure.getError().getTcId() != null
				&& !failure.getError().getTcId().isBlank()) {
			failure.getMeta().setTcId(failure.getError().getTcId().trim());
		}

		if (screenshotPath != null) {
			failure.setScreenshot(createScreenshotInfo(screenshotPath));
		}

		failure.setEnvironment(createEnvironmentSnapshot(driver));
		failure.setStatus("FAIL");
		failure.setRecentHistory(loadRecentHistory(testId, result));

		return failure;
	}

	private static ErrorInfo createErrorInfo(ITestResult result) {
		Throwable t = result.getThrowable();
		if (t == null) {
			return null;
		}

		String exceptionType = t.getClass().getName();
		String exceptionMessage = safeSingleLine(t.getMessage());
		String stackTrace = getStackTraceString(t);
		String fingerprint = fingerprint(t);
		String rootCauseClass = null;
		String rootCauseMethod = null;

		StackTraceElement[] stack = t.getStackTrace();
		if (stack != null && stack.length > 0) {
			rootCauseClass = stack[0].getClassName();
			rootCauseMethod = stack[0].getMethodName();
		}

		ErrorInfo error = new ErrorInfo(exceptionType, stackTrace, fingerprint);
		error.setExceptionMessage(exceptionMessage);
		error.setRootCauseClass(rootCauseClass);
		error.setRootCauseMethod(rootCauseMethod);

		error.setTestMethod(result.getMethod().getMethodName());

		Object[] params = result.getParameters();
		String testParameters = extractParameterDescription(params);
		error.setTestParameters(testParameters);

		if (params != null && params.length > 0) {
			Object firstParam = params[0];
			if (firstParam instanceof java.util.Map) {
				java.util.Map<?, ?> paramMap = (java.util.Map<?, ?>) firstParam;
				String status = mapGetString(paramMap, "status", "STATUS", "st");
				error.setDataStatus(status);

				String focus = mapGetString(paramMap, "focus", "FOCUS", "fc");
				error.setTestFocus(focus);

				String tcId = mapGetString(paramMap, "tc_id");
				if (tcId != null && !tcId.isBlank()) {
					error.setTcId(tcId.trim());
				}

				String testData = extractTestDataFromMap(paramMap);
				error.setTestData(testData);
			}
		}

		if (exceptionMessage != null) {
			String[] values = parseAssertionMessage(exceptionMessage);
			error.setExpected(values[0]);
			error.setActual(values[1]);
		}

		// Selenium timeouts and other non-assert failures: expected from data row; actual from exception line
		if (params != null && params.length > 0 && params[0] instanceof java.util.Map) {
			java.util.Map<?, ?> paramMap = (java.util.Map<?, ?>) params[0];
			if (error.getExpected() == null || error.getExpected().isEmpty()) {
				String fromData = mapGetString(paramMap, "expected_message", "expected");
				if (fromData != null && !fromData.trim().isEmpty()) {
					error.setExpected(fromData.trim());
				}
			}
			if (error.getActual() == null || error.getActual().isEmpty()) {
				String firstLine = exceptionMessage != null ? exceptionMessage.split("\\r?\\n", 2)[0].trim() : null;
				if (firstLine != null && !firstLine.isEmpty()) {
					error.setActual(firstLine.length() > 500 ? firstLine.substring(0, 500) + "..." : firstLine);
				}
			}
		}

		return error;
	}

	private static String extractParameterDescription(Object[] params) {
		if (params == null || params.length == 0) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		Object firstParam = params[0];
		if (firstParam instanceof java.util.Map) {
			java.util.Map<?, ?> map = (java.util.Map<?, ?>) firstParam;
			String[] keys = { "focus", "status", "invalid_reason" };
			boolean first = true;
			for (String key : keys) {
				Object val = mapGetString(map, key);
				if (val != null) {
					if (!first) {
						sb.append(", ");
					}
					sb.append(key).append("=").append(val);
					first = false;
				}
			}
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	private static String extractTestDataFromMap(java.util.Map<?, ?> paramMap) {
		if (paramMap == null || paramMap.isEmpty()) {
			return null;
		}

		StringBuilder sb = new StringBuilder();
		String[] excludeKeys = { "focus", "status", "invalid_reason", "tc_id" };
		boolean first = true;

		for (Object keyObj : paramMap.keySet()) {
			String key = keyObj.toString();
			boolean isControlField = false;
			for (String excludeKey : excludeKeys) {
				if (key.equals(excludeKey)) {
					isControlField = true;
					break;
				}
			}

			if (!isControlField) {
				Object valObj = paramMap.get(keyObj);
				String val = valObj != null ? valObj.toString() : "";
				if (!val.isEmpty()) {
					if (!first) {
						sb.append(", ");
					}
					sb.append(key).append("=").append(val);
					first = false;
				}
			}
		}
		return sb.length() > 0 ? sb.toString() : null;
	}

	private static String[] parseAssertionMessage(String message) {
		String[] result = new String[2];
		result[0] = null;
		result[1] = null;

		if (message == null || !message.contains("expected")) {
			return result;
		}

		try {
			if (message.contains("expected [") && message.contains("] but found [")) {
				int expectedStart = message.indexOf("expected [") + 10;
				int expectedEnd = message.indexOf("]", expectedStart);
				if (expectedEnd > expectedStart) {
					result[0] = message.substring(expectedStart, expectedEnd).trim();
				}

				int actualStart = message.indexOf("but found [") + 11;
				int actualEnd = message.indexOf("]", actualStart);
				if (actualEnd >= actualStart) {
					result[1] = message.substring(actualStart, actualEnd).trim();
					if (result[1].isEmpty()) {
						result[1] = "";
					}
				}
			} else if (message.contains("expected") && message.contains("but found")) {
				String[] parts = message.split("but found");
				if (parts.length == 2) {
					result[0] = parts[0].replace("expected", "").trim();
					result[1] = parts[1].trim();
				}
			}
		} catch (Exception e) {
			logger.debug("Failed to parse assertion message", e);
		}

		return result;
	}

	private static ScreenshotInfo createScreenshotInfo(String screenshotPath) {
		if (screenshotPath == null) {
			return null;
		}

		try {
			Path path = Paths.get(screenshotPath);
			boolean exists = Files.exists(path);
			String relativePath = Paths.get(System.getProperty("user.dir")).relativize(path).toString();

			ScreenshotInfo screenshot = new ScreenshotInfo(exists, screenshotPath, relativePath,
					epochToIso(System.currentTimeMillis()));
			return screenshot;
		} catch (Exception e) {
			logger.warn("Failed to create screenshot info for path: {}", screenshotPath, e);
			return null;
		}
	}

	private static EnvironmentSnapshot createEnvironmentSnapshot(WebDriver driver) {
		EnvironmentSnapshot env = new EnvironmentSnapshot();

		env.setBrowser(ConfigUtils.get("browser"));
		env.setOs(ConfigUtils.get("os"));
		env.setExecutionEnv(ConfigUtils.get("execution_env"));
		env.setGridUrl(ConfigUtils.get("grid_url"));
		env.setBaseUrl(ConfigUtils.get("base_url"));
		env.setJavaVersion(System.getProperty("java.version", "unknown"));

		if (driver != null) {
			try {
				JavascriptExecutor js = (JavascriptExecutor) driver;
				String userAgent = (String) js.executeScript("return navigator.userAgent;");
				if (userAgent != null) {
					if (userAgent.contains("Chrome/")) {
						int idx = userAgent.indexOf("Chrome/");
						env.setBrowserVersion(userAgent.substring(idx + 7, Math.min(idx + 15, userAgent.length())));
					} else if (userAgent.contains("Firefox/")) {
						int idx = userAgent.indexOf("Firefox/");
						env.setBrowserVersion(userAgent.substring(idx + 8, Math.min(idx + 15, userAgent.length())));
					}
				}
			} catch (Exception e) {
				logger.debug("Could not retrieve browser details from driver", e);
			}
		}

		env.setSeleniumVersion(getPomVersion("selenium-java"));
		env.setTestngVersion(getPomVersion("testng"));

		return env;
	}

	private static RecentHistory loadRecentHistory(String testId, ITestResult currentResult) {
		RecentHistory history = new RecentHistory();
		List<HistoryRecord> records = new ArrayList<>();

		if (currentResult != null) {
			HistoryRecord current = createHistoryRecordFromResult(currentResult);
			records.add(current);
		}

		history.setRuns(records);
		return history;
	}

	private static HistoryRecord createHistoryRecordFromResult(ITestResult result) {
		HistoryRecord record = new HistoryRecord();
		record.setRunId(RunContext.getRunId());

		String testId = RunContext.testId(result);
		record.setTestId(testId);
		record.setStatus("FAIL");

		long startedAtEpochMs = result.getStartMillis();
		long endedAtEpochMs = result.getEndMillis() > 0 ? result.getEndMillis() : Instant.now().toEpochMilli();
		record.setStartedAtEpochMs(startedAtEpochMs);
		record.setEndedAtEpochMs(endedAtEpochMs);
		record.setDurationMs(Math.max(0, endedAtEpochMs - startedAtEpochMs));

		Throwable t = result.getThrowable();
		if (t != null) {
			record.setExceptionType(t.getClass().getName());
			record.setExceptionMessage(t.getMessage());
			record.setErrorFingerprint(fingerprint(t));
		}

		String tc = extractTcIdFromParameters(result);
		if (tc != null) {
			record.setTcId(tc);
		}

		return record;
	}

	private static String extractTcIdFromParameters(ITestResult result) {
		Object[] params = result.getParameters();
		if (params == null || params.length == 0 || !(params[0] instanceof java.util.Map)) {
			return null;
		}
		java.util.Map<?, ?> map = (java.util.Map<?, ?>) params[0];
		Object v = map.get("tc_id");
		if (v == null) {
			v = map.get("TC_ID");
		}
		if (v == null) {
			return null;
		}
		String s = v.toString().trim();
		return s.isEmpty() ? null : s;
	}

	@SuppressWarnings("unused")
	private static String extractTestIdWithParameter(ITestResult result) {
		String baseTestId = RunContext.testId(result.getMethod().getRealClass(), result.getMethod().getMethodName());

		Object[] params = result.getParameters();
		if (params == null || params.length == 0) {
			return baseTestId;
		}

		Object firstParam = params[0];
		if (firstParam instanceof java.util.Map) {
			try {
				java.util.Map<?, ?> paramMap = (java.util.Map<?, ?>) firstParam;
				String status = mapGetString(paramMap, "status", "STATUS", "st");
				String focus = mapGetString(paramMap, "focus", "FOCUS", "fc");

				if (status != null && !status.isEmpty()) {
					String paramDesc = (status + "-" + (focus != null ? focus : "")).toUpperCase();
					return baseTestId + "[" + paramDesc + "]";
				}
			} catch (Exception e) {
				logger.debug("Failed to extract parameter from map", e);
			}
		}

		return baseTestId;
	}

	private static String mapGetString(java.util.Map<?, ?> map, String... keys) {
		if (map == null)
			return null;
		for (String key : keys) {
			for (Object mapKey : map.keySet()) {
				if (key.equalsIgnoreCase(mapKey.toString())) {
					Object value = map.get(mapKey);
					return value != null ? value.toString() : null;
				}
			}
		}
		return null;
	}

	@SuppressWarnings("unused")
	private static HistoryRecord parseHistoryLine(String line) {
		try {
			return objectMapper.readValue(line, HistoryRecord.class);
		} catch (Exception e) {
			logger.debug("Failed to parse history line: {}", line, e);
			return null;
		}
	}

	private static String fingerprint(Throwable t) {
		if (t == null) {
			return null;
		}
		StackTraceElement[] st = t.getStackTrace();
		String top = (st != null && st.length > 0) ? st[0].toString() : "";
		String msg = t.getMessage() != null ? t.getMessage() : "";
		String raw = t.getClass().getName() + "|" + top + "|" + msg;
		return Integer.toHexString(raw.hashCode());
	}

	private static String getStackTraceString(Throwable t) {
		if (t == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append(t.getClass().getName());
		if (t.getMessage() != null) {
			sb.append(": ").append(t.getMessage());
		}
		sb.append("\n");

		StackTraceElement[] stack = t.getStackTrace();
		for (StackTraceElement elem : stack) {
			sb.append("\tat ").append(elem.toString()).append("\n");
		}

		return sb.toString();
	}

	private static String safeSingleLine(String value) {
		if (value == null) {
			return null;
		}
		return value.replace("\r", " ").replace("\n", " ").trim();
	}

	private static String epochToIso(long epochMs) {
		return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMs));
	}

	private static String generateFailureId(String runId, String epochMs) {
		String cleanRunId = runId.replaceAll("^run-", "");
		String shortHash = String.format("%08x", Math.abs(System.nanoTime() % 0x100000000L));
		return String.format("fail-%s-%s-%s", cleanRunId, epochMs, shortHash);
	}

	private static String getPomVersion(String artifactId) {
		try {
			Path pomPath = Paths.get("pom.xml");
			if (!Files.exists(pomPath)) {
				logger.warn("pom.xml not found at {}", pomPath.toAbsolutePath());
				return "unknown";
			}

			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(pomPath.toFile());

			NodeList dependencies = doc.getElementsByTagName("dependency");
			for (int i = 0; i < dependencies.getLength(); i++) {
				Element dependency = (Element) dependencies.item(i);
				NodeList artifactIds = dependency.getElementsByTagName("artifactId");
				if (artifactIds.getLength() > 0) {
					String depArtifactId = artifactIds.item(0).getTextContent();
					if (artifactId.equals(depArtifactId)) {
						NodeList versions = dependency.getElementsByTagName("version");
						if (versions.getLength() > 0) {
							return versions.item(0).getTextContent();
						}
					}
				}
			}

			logger.warn("Artifact '{}' not found in pom.xml", artifactId);
			return "unknown";
		} catch (Exception e) {
			logger.warn("Failed to read version for '{}' from pom.xml", artifactId, e);
			return "unknown";
		}
	}
}
