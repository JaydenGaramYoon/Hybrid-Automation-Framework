package triage.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.testng.IRetryAnalyzer;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import utils.db.RunSummaryDb;
import utils.run.RunContext;

/** Appends one JSON line per test to {@code reports/history/test_run_history.jsonl}. */
public class TestRunHistoryListener implements ITestListener {

	private static final Logger logger = LogManager.getLogger(TestRunHistoryListener.class);
	private static final Object FILE_LOCK = new Object();

	private static final String HISTORY_DIR = "reports/history";
	private static final String HISTORY_FILE = "test_run_history.jsonl";
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneId.of("UTC"));

	@Override
	public void onStart(ITestContext context) {
		ensureHistoryDirectory();
	}

	@Override
	public void onTestSuccess(ITestResult result) {
		extractAndSetParameterDescription(result);
		writeHistoryLine(result, "PASS");
	}

	@Override
	public void onTestFailure(ITestResult result) {
		extractAndSetParameterDescription(result);
		writeHistoryLine(result, "FAIL");
	}

	@Override
	public void onTestSkipped(ITestResult result) {
		String skipReason = RunContext.getSkipReason();
		IRetryAnalyzer retryAnalyzer = result.getMethod().getRetryAnalyzer(result);
		boolean hasRetryAnalyzer = (retryAnalyzer != null);
		Throwable throwable = result.getThrowable();

		if (skipReason == null || skipReason.isEmpty()) {
			if (throwable != null) {
				String exceptionMessage = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
				String exceptionType = throwable.getClass().getSimpleName().toLowerCase();
				
				if (exceptionMessage.contains("filenotfound") || exceptionMessage.contains("file not found")
						|| exceptionType.contains("filenotfound")) {
					skipReason = "data_not_found";
					RunContext.setSkipReason(skipReason);
				}
				else if (exceptionType.contains("sessionnotcreated") || 
					exceptionMessage.contains("session not created") ||
					exceptionMessage.contains("could not start a new session") ||
					exceptionMessage.contains("before_method") ||
					(exceptionMessage.contains("driver") && exceptionMessage.contains("null")) ||
					(exceptionMessage.contains("landingpage") && exceptionMessage.contains("null"))) {
					
					skipReason = "env_not_ready";
					RunContext.setSkipReason(skipReason);
				}
			}
			
			if (skipReason == null || skipReason.isEmpty()) {
				if (hasRetryAnalyzer) {
					return;
				}
			}
		}
		
		extractAndSetParameterDescription(result);
		
		if (skipReason != null && !skipReason.isEmpty()) {
			RunContext.setSkipReason(skipReason);
		}
		
		writeHistoryLine(result, "SKIP");
	}

	private void ensureHistoryDirectory() {
		try {
			Files.createDirectories(historyDir());
		} catch (IOException e) {
			logger.warn("Failed to create history dir: {}", historyDir().toAbsolutePath(), e);
		}
	}

	private void writeHistoryLine(ITestResult result, String status) {
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(status, "status");

		ensureHistoryDirectory();

		String testId = RunContext.testId(result);
		String runId = RunContext.getRunId();
		long startedAtEpochMs = result.getStartMillis();
		long endedAtEpochMs = result.getEndMillis() > 0 ? result.getEndMillis() : Instant.now().toEpochMilli();
		long durationMs = Math.max(0, endedAtEpochMs - startedAtEpochMs);

		String startedAtIso = epochToIso(startedAtEpochMs);
		String endedAtIso = epochToIso(endedAtEpochMs);

		Throwable t = result.getThrowable();
		String exceptionType = t != null ? t.getClass().getName() : null;
		String exceptionMessage = t != null ? safeSingleLine(t.getMessage()) : null;
		String errorFingerprint = fingerprint(t);

		String skipReason = null;
		if ("SKIP".equals(status)) {
			skipReason = RunContext.getSkipReason();
		} else if ("FAIL".equals(status)) {
			skipReason = RunContext.getSkipReason();
		}

		StringBuilder json = new StringBuilder();
		json.append("{\"run_id\":\"").append(escape(runId)).append("\"");
		json.append(",\"test_id\":\"").append(escape(testId)).append("\"");
		json.append(",\"status\":\"").append(escape(status)).append("\"");
		String tcId = RunContext.getTcId();
		if (tcId != null && !tcId.isEmpty()) {
			json.append(",\"tc_id\":\"").append(escape(tcId)).append("\"");
		}

		if ("SKIP".equals(status)) {
			int invocationCount = result.getMethod().getCurrentInvocationCount();
			json.append(",\"invocation_count\":").append(invocationCount);
		}
		
		json.append(",\"started_at\":\"").append(startedAtIso).append("\"");
		json.append(",\"started_at_epoch_ms\":").append(startedAtEpochMs);
		json.append(",\"ended_at\":\"").append(endedAtIso).append("\"");
		json.append(",\"ended_at_epoch_ms\":").append(endedAtEpochMs);
		json.append(",\"duration_ms\":").append(durationMs);

		if (skipReason != null && !skipReason.isEmpty()) {
			json.append(",\"skip_reason\":").append(toJsonStringOrNull(skipReason));
		}

		String testFocus = RunContext.getTestFocus();
		String dataStatus = RunContext.getDataStatus();
		String invalidReason = RunContext.getInvalidReason();
		
		if (testFocus != null || dataStatus != null || invalidReason != null) {
			json.append(",\"test_meta\":{");
			boolean first = true;
			if (testFocus != null) {
				json.append("\"focus\":\"").append(escape(testFocus)).append("\"");
				first = false;
			}
			if (dataStatus != null) {
				if (!first) json.append(",");
				json.append("\"data_status\":\"").append(escape(dataStatus)).append("\"");
				first = false;
			}
			if (invalidReason != null) {
				if (!first) json.append(",");
				json.append("\"invalid_reason\":\"").append(escape(invalidReason)).append("\"");
			}
			json.append("}");
		}

		if (!"PASS".equals(status)) {
			json.append(",\"exception_type\":").append(toJsonStringOrNull(exceptionType));
			json.append(",\"exception_message\":").append(toJsonStringOrNull(exceptionMessage));
			json.append(",\"error_fingerprint\":").append(toJsonStringOrNull(errorFingerprint));
		}

		json.append("}");

		appendLine(json.toString());
		persistRunSummaryToDb(runId, testId, status, durationMs, startedAtEpochMs, endedAtEpochMs, exceptionType,
				exceptionMessage, errorFingerprint, skipReason);
	}

	private void persistRunSummaryToDb(String runId, String testId, String status, long durationMs,
			long startedAtEpochMs, long endedAtEpochMs, String exceptionType, String exceptionMessage,
			String errorFingerprint, String skipReason) {
		if (!RunSummaryDb.isEnabled()) {
			return;
		}
		boolean pass = "PASS".equals(status);
		RunSummaryDb.insertTestResult(runId, testId, status, durationMs, Long.valueOf(startedAtEpochMs),
				Long.valueOf(endedAtEpochMs), pass ? null : exceptionType, pass ? null : exceptionMessage,
				pass ? null : errorFingerprint, skipReason);
	}

	private void appendLine(String line) {
		Path file = historyFile();
		synchronized (FILE_LOCK) {
			try {
				
				Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
				
			} catch (IOException e) {
				logger.error("Failed to append history line to {}", file.toAbsolutePath(), e);
			}
		}
	}

	private Path historyDir() {
		return Path.of(System.getProperty("user.dir"), HISTORY_DIR);
	}

	private Path historyFile() {
		return historyDir().resolve(HISTORY_FILE);
	}

	private String fingerprint(Throwable t) {
		if (t == null) {
			return null;
		}
		StackTraceElement[] st = t.getStackTrace();
		String top = (st != null && st.length > 0) ? st[0].toString() : "";
		String msg = t.getMessage() != null ? t.getMessage() : "";
		String raw = t.getClass().getName() + "|" + top + "|" + msg;
		return Integer.toHexString(raw.hashCode());
	}

	private String safeSingleLine(String value) {
		if (value == null) {
			return null;
		}
		return value.replace("\r", " ").replace("\n", " ").trim();
	}

	private String toJsonStringOrNull(String value) {
		if (value == null) {
			return "null";
		}
		return "\"" + escape(value) + "\"";
	}

	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private String epochToIso(long epochMs) {
		return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMs));
	}

	/**
	 * Reads {@code status}/{@code focus} (and optional {@code invalid_reason}) from the first test parameter
	 * (map or bean) into {@link RunContext}. Used by {@link utils.listeners.Retry} between retries.
	 */
	public static void extractAndSetParameterDescription(ITestResult result) {
		try {
			Object[] parameters = result.getParameters();
			if (parameters == null || parameters.length == 0) {
				RunContext.clearParameterInfo();
				RunContext.refreshTcIdFromTestParameters(result);
				return;
			}

			Object firstParam = parameters[0];

			String status = null;
			String focus = null;
			String invalidReason = null;

			if (firstParam instanceof java.util.Map) {
				@SuppressWarnings("unchecked")
				java.util.Map<String, ?> map = (java.util.Map<String, ?>) firstParam;
				status = mapGetString(map, "status", "STATUS");
				focus = mapGetString(map, "focus", "FOCUS");
				invalidReason = mapGetString(map, "invalid_reason", "INVALID_REASON");
			}
			else {
				status = getPropertyByReflection(firstParam, "status", "getStatus");
				focus = getPropertyByReflection(firstParam, "focus", "getFocus");
				invalidReason = getPropertyByReflection(firstParam, "invalid_reason", "getInvalidReason");
			}

			if (status != null && focus != null) {
				String description = (status + "-" + focus).toUpperCase();
				RunContext.setParameterDescription(description);
				
				RunContext.setTestFocus(focus.toLowerCase());
				RunContext.setDataStatus(status.toLowerCase());
				
				if (invalidReason != null && !invalidReason.isEmpty()) {
					RunContext.setInvalidReason(invalidReason.toUpperCase());
				}
				
				logger.debug("Set parameter description for {}: {} (invalidReason: {})", 
						result.getMethod().getMethodName(), description, invalidReason);
			} else {
				RunContext.clearParameterInfo();
			}
		} catch (Exception e) {
			logger.debug("Failed to extract parameter description", e);
			RunContext.clearParameterInfo();
		}
		RunContext.refreshTcIdFromTestParameters(result);
	}

	private static String mapGetString(java.util.Map<String, ?> map, String... keys) {
		if (map == null) return null;
		for (String key : keys) {
			Object value = map.get(key);
			if (value != null) {
				return value.toString();
			}
		}
		return null;
	}

	private static String getPropertyByReflection(Object obj, String fieldName, String methodName) {
		if (obj == null) return null;
		try {
			try {
				java.lang.reflect.Method method = obj.getClass().getMethod(methodName);
				Object value = method.invoke(obj);
				if (value != null) {
					return value.toString();
				}
			} catch (NoSuchMethodException e) {
			}

			try {
				java.lang.reflect.Field field = obj.getClass().getDeclaredField(fieldName);
				field.setAccessible(true);
				Object value = field.get(obj);
				if (value != null) {
					return value.toString();
				}
			} catch (NoSuchFieldException e) {
			}
		} catch (Exception e) {
			logger.debug("Failed to get property {} via reflection", fieldName, e);
		}
		return null;
	}
}

