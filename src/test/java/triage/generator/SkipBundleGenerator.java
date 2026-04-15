package triage.generator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.testng.ITestResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import triage.model.bundles.SkipBundle;
import triage.model.history.IndividualSkip;
import triage.model.metadata.EnvironmentSnapshot;
import utils.run.RunContext;
import utils.config.ConfigUtils;

/** Collects {@link IndividualSkip} rows and writes {@code reports/skip/run-*_skip_bundle.json}. */
public class SkipBundleGenerator {

	private static final Logger logger = LogManager.getLogger(SkipBundleGenerator.class);
	private static final ObjectMapper objectMapper = createObjectMapper();
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneId.of("UTC"));
	
	private static ObjectMapper createObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		return mapper;
	}
	
	/**
	 * Thread-safe shared storage for skips collected during a run.
	 * Key: runId, Value: List of skips for that run
	 * This replaces ThreadLocal to support parallel test execution.
	 */
	private static final ConcurrentHashMap<String, List<IndividualSkip>> SKIPS_BY_RUN = new ConcurrentHashMap<>();

	/**
	 * Create IndividualSkip from ITestResult when test is skipped.
	 * 
	 * Only creates skip_bundle entries for SKIPPED tests.
	 * FAILED or PASSED tests are NOT included in skip_bundle - they go to run_history only.
	 * 
	 * @param result TestNG ITestResult from skipped test method
	 * @return IndividualSkip object with skip details, or null if result is not SKIP status
	 */
	public static IndividualSkip createFromTestResult(ITestResult result) {
		Objects.requireNonNull(result, "result");

		if (result.getStatus() != ITestResult.SKIP) {
			return null;
		}

		IndividualSkip skip = new IndividualSkip();

		String testId = RunContext.testId(result);
		String runId = RunContext.getRunId();
		long epochMs = result.getEndMillis() > 0 ? result.getEndMillis() : Instant.now().toEpochMilli();
		String skipId = generateSkipId(runId, String.valueOf(epochMs));

		skip.setTestId(testId);
		skip.setRunId(runId);
		skip.setSkipId(skipId);
		skip.setStatus("SKIP");

		long startedAtEpochMs = result.getStartMillis();
		long endedAtEpochMs = result.getEndMillis() > 0 ? result.getEndMillis() : Instant.now().toEpochMilli();
		long durationMs = Math.max(0, endedAtEpochMs - startedAtEpochMs);

		skip.setStartedAt(epochToIso(startedAtEpochMs));
		skip.setEndedAt(epochToIso(endedAtEpochMs));
		skip.setStartedAtEpochMs(startedAtEpochMs);
		skip.setEndedAtEpochMs(endedAtEpochMs);
		skip.setDurationMs(durationMs);

		int invocationCount = result.getMethod().getCurrentInvocationCount();
		skip.setInvocationCount(invocationCount);

		skip.setSkipReason(analyzeSkipReason(result));

		Throwable t = result.getThrowable();
		if (t != null) {
			skip.setExceptionType(t.getClass().getName());
			skip.setExceptionMessage(safeSingleLine(t.getMessage()));
			skip.setStackTrace(getStackTraceString(t));
		}

		skip.setEnvironment(createEnvironmentSnapshot());

		Object[] params = result.getParameters();
		if (params != null && params.length > 0 && params[0] instanceof java.util.Map) {
			java.util.Map<?, ?> map = (java.util.Map<?, ?>) params[0];
			Object v = map.get("tc_id");
			if (v != null) {
				String tc = v.toString().trim();
				if (!tc.isEmpty()) {
					skip.setTcId(tc);
				}
			}
		}

		return skip;
	}

	/**
	 * Skip reason analysis (setup failure, data issue, env not ready, etc.)
	 * IMPORTANT: First check if skip_reason is already set in RunContext (e.g., by Listeners)
	 * Only analyze exception if skip_reason is not set
	 */
	private static String analyzeSkipReason(ITestResult result) {
		String skipReasonFromContext = RunContext.getSkipReason();
		if (skipReasonFromContext != null && !skipReasonFromContext.isEmpty()) {
			return skipReasonFromContext;
		}
		
		Throwable t = result.getThrowable();
		if (t == null) {
			return "unknown";
		}

		String message = (t.getMessage() != null ? t.getMessage() : "").toLowerCase();
		String exceptionType = t.getClass().getSimpleName().toLowerCase();

		if (message.contains("filenotfound") || message.contains("file not found") 
			|| message.contains("data source") || message.contains("database")
			|| exceptionType.contains("filenotfound")) {
			return "data_not_found";
		}

		if (message.contains("timeout") || message.contains("connection") 
			|| message.contains("refused") || message.contains("server not")
			|| message.contains("not available") || message.contains("not ready")
			|| message.contains("could not start a new session")
			|| message.contains("invalid address of the remote server")
			|| message.contains("browser start-up failure")
			|| message.contains("landingpage") || message.contains("driver")
			|| exceptionType.contains("sessionnotcreated")) {
			return "env_not_ready";
		}

		if (message.contains("session id is null")) {
			return "env_not_ready";
		}

		if (message.contains("setup") || message.contains("before") 
			|| exceptionType.contains("setup")
			|| exceptionType.contains("nullpointerexception") && !message.contains("session id is null")
			|| message.contains("cannot invoke") && !message.contains("session id is null")
			|| message.contains("fixture") || message.contains("initialization")) {
			return "setup_failed";
		}

		return "retry_exhausted";
	}

	/**
	 * Environment information snapshot
	 */
	private static EnvironmentSnapshot createEnvironmentSnapshot() {
		EnvironmentSnapshot env = new EnvironmentSnapshot();

		env.setBrowser(ConfigUtils.get("browser"));
		env.setBrowserVersion(null);
		env.setBrowserVersionNote("not_available_before_browser_launch");
		env.setOs(ConfigUtils.get("os"));
		env.setExecutionEnv(ConfigUtils.get("execution_env"));
		env.setGridUrl(ConfigUtils.get("grid_url"));
		env.setBaseUrl(ConfigUtils.get("base_url"));
		env.setJavaVersion(System.getProperty("java.version", "unknown"));
		env.setSeleniumVersion("4.41.0");
		env.setTestngVersion("7.4.0");

		return env;
	}

	/**
	 * Add skip to collection (buffering in memory, thread-safe).
	 * 
	 * Collects skip entries in memory using thread-safe ConcurrentHashMap
	 * instead of ThreadLocal to support parallel test execution.
	 * 
	 * @param skip The individual skip to be collected (null-safe)
	 */
	public static void addSkip(IndividualSkip skip) {
		if (skip != null) {
			String runId = skip.getRunId();
			SKIPS_BY_RUN.computeIfAbsent(runId, k -> new ArrayList<>()).add(skip);
			ThreadContext.put("testId", skip.getTestId());
			ThreadContext.put("runId", runId);
			String stc = skip.getTcId();
			if (stc != null && !stc.isEmpty()) {
				ThreadContext.put("tcId", stc);
			}
			logger.info("Skip bundle collected [reason={}]", skip.getSkipReason());
			ThreadContext.remove("testId");
			ThreadContext.remove("runId");
			ThreadContext.remove("tcId");
		}
	}

	/**
	 * Save all collected SKIP bundles to JSON file (post-run).
	 * 
	 * Persists all collected skip entries for the run as JSON array.
	 * Uses shared storage (ConcurrentHashMap) instead of ThreadLocal
	 * to support parallel test execution.
	 * 
	 * @param runId Execution ID for grouping skips
	 */
	public static void saveSkipBundle(String runId) {
		List<IndividualSkip> skips = SKIPS_BY_RUN.getOrDefault(runId, new ArrayList<>());

		if (skips.isEmpty()) {
			logger.debug("No skips to save for run: {}", runId);
			return;
		}

		try {
			String testId = skips.isEmpty() ? "-" : skips.get(0).getTestId();
			ThreadContext.put("testId", testId);
			ThreadContext.put("runId", runId);

			ensureSkipDirectory();

			SkipBundle bundle = new SkipBundle();
			bundle.setRunId(runId);
			bundle.setGeneratedAt(ISO_FORMATTER.format(Instant.now()));
			bundle.setSkips(new ArrayList<>(skips));

			String fileName = String.format("run-%s_skip_bundle.json", runId.replaceAll("^run-", ""));
			Path filePath = Paths.get(System.getProperty("user.dir"), "reports", "skip", fileName);

			String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
			Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));

			if (!skips.isEmpty()) {
				logger.info("Skip bundle saved: {} ({} skips)", filePath.toAbsolutePath(), skips.size());
			} else {
				logger.debug("Skip bundle is empty, no skips to save");
			}

		} catch (IOException e) {
			logger.error("Failed to save skip bundle", e);
		} finally {
			ThreadContext.remove("testId");
			ThreadContext.remove("runId");
			ThreadContext.remove("tcId");
		}
	}

	private static void ensureSkipDirectory() {
		try {
			Path skipDir = Paths.get(System.getProperty("user.dir"), "reports", "skip");
			if (!Files.exists(skipDir)) {
				Files.createDirectories(skipDir);
				logger.debug("Created skip directory: {}", skipDir.toAbsolutePath());
			}
		} catch (IOException e) {
			logger.warn("Failed to create skip directory", e);
		}
	}

	private static String generateSkipId(String runId, String epochMs) {
		String cleanRunId = runId.replaceAll("^run-", "");
		String shortHash = String.format("%08x", Math.abs(System.nanoTime() % 0x100000000L));
		return String.format("skip-%s-%s-%s", cleanRunId, epochMs, shortHash);
	}

	private static String epochToIso(long epochMs) {
		return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMs));
	}

	private static String safeSingleLine(String str) {
		if (str == null) {
			return "";
		}
		return str.replace("\n", " ").replace("\r", "").trim();
	}

	private static String getStackTraceString(Throwable t) {
		if (t == null) {
			return "";
		}

		StringBuilder sb = new StringBuilder();
		for (StackTraceElement element : t.getStackTrace()) {
			sb.append(element.toString()).append("\n");
		}
		return sb.toString().trim();
	}
}
