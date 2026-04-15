package triage.collector;

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
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import triage.generator.ConsolidatedBundleGenerator;
import triage.generator.TriageReportGenerator;
import triage.model.bundles.ConsolidatedFailureBundle;
import triage.model.bundles.FailureBundle;
import triage.model.history.IndividualFailure;

/** In-memory failure list for the run; flushed to JSON and triggers consolidated/triage reports. */
public class FailureBundleCollector {

	private static final Logger logger = LogManager.getLogger(FailureBundleCollector.class);
	private static final ObjectMapper objectMapper = createObjectMapper();
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneId.of("UTC"));

	private static ObjectMapper createObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		// [CRITICAL] Preserve array element order during JSON serialization
		mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		return mapper;
	}

	/**
	 * Thread-safe shared storage for failures collected during a run.
	 * Key: runId, Value: List of failures for that run
	 * This replaces ThreadLocal to support parallel test execution.
	 */
	private static final ConcurrentHashMap<String, List<IndividualFailure>> FAILURES_BY_RUN = new ConcurrentHashMap<>();
	private static final String FAILURE_DIR = "reports/failure";
	private static final String TRIAGE_DIR = "reports/triage";

	/**
	 * Add failure bundle.
	 * Classification logic integration
	 * 
	 * Uses shared storage (ConcurrentHashMap) instead of ThreadLocal
	 * to support parallel test execution.
	 * 
	 * @param failure The individual failure to be collected (null-safe)
	 */
	public static void addFailure(IndividualFailure failure) {
		if (failure != null) {
			// Perform classification
			String runId = failure.getRunId();
			FAILURES_BY_RUN.computeIfAbsent(runId, k -> new ArrayList<>()).add(failure);
			ThreadContext.put("testId", failure.getTestId());
			ThreadContext.put("runId", runId);
			String tc = failure.getError() != null ? failure.getError().getTcId() : null;
			if (tc == null && failure.getMeta() != null) {
				tc = failure.getMeta().getTcId();
			}
			if (tc != null && !tc.isEmpty()) {
				ThreadContext.put("tcId", tc);
			}
			logger.info("Failure bundle collected [classification={}]", failure.getClassification());
			ThreadContext.remove("testId");
			ThreadContext.remove("runId");
			ThreadContext.remove("tcId");
		}
	}

	/**
	 * Save all collected failure bundles as JSON (post-run).
	 * Generate classification result report
	 * 
	 * Uses shared storage (ConcurrentHashMap) instead of ThreadLocal
	 * to support parallel test execution.
	 * 
	 * @param runId Execution ID
	 */
	public static void saveFailureBundle(String runId) {
		List<IndividualFailure> failures = FAILURES_BY_RUN.getOrDefault(runId, new ArrayList<>());

		if (failures.isEmpty()) {
			logger.debug("No failures to save for run: {}", runId);
			return;
		}

		try {
			String testId = failures.isEmpty() ? "-" : failures.get(0).getTestId();
			ThreadContext.put("testId", testId);
			ThreadContext.put("runId", runId);
			String tcCtx = null;
			for (IndividualFailure f : failures) {
				if (f.getError() != null && f.getError().getTcId() != null && !f.getError().getTcId().isBlank()) {
					tcCtx = f.getError().getTcId().trim();
					break;
				}
				if (f.getMeta() != null && f.getMeta().getTcId() != null && !f.getMeta().getTcId().isBlank()) {
					tcCtx = f.getMeta().getTcId().trim();
					break;
				}
			}
			if (tcCtx != null) {
				ThreadContext.put("tcId", tcCtx);
			}

			ensureFailureDirectory();

			FailureBundle bundle = new FailureBundle();
			bundle.setRunId(runId);
			bundle.setGeneratedAt(ISO_FORMATTER.format(Instant.now()));
			bundle.setFailures(new ArrayList<>(failures));  // Create a copy to prevent concurrent modification

			String fileName = String.format("run-%s_failure_bundle.json", runId.replaceAll("^run-", ""));
			Path filePath = Paths.get(System.getProperty("user.dir"), FAILURE_DIR, fileName);

			String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
			Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));

			logger.info("Failure bundle saved: {} ({} failures)", filePath.toAbsolutePath(), failures.size());

			ConsolidatedFailureBundle consolidatedBundle = ConsolidatedBundleGenerator.generateFromHistory();
			if (consolidatedBundle != null && consolidatedBundle.getMethodsWithFailures() != null) {
				saveConsolidatedBundle(consolidatedBundle, runId);
			}
			
			TriageReportGenerator.generateReport(bundle, runId);
		} catch (IOException e) {
			logger.error("Failed to save failure bundle", e);
		} finally {
			ThreadContext.remove("testId");
			ThreadContext.remove("runId");
			ThreadContext.remove("tcId");
		}
	}

	private static void ensureFailureDirectory() {
		try {
			Path failureDir = Paths.get(System.getProperty("user.dir"), FAILURE_DIR);
			if (!Files.exists(failureDir)) {
				Files.createDirectories(failureDir);
				logger.debug("Created failure directory: {}", failureDir.toAbsolutePath());
			}
		} catch (IOException e) {
			logger.warn("Failed to create failure directory", e);
		}
	}

	private static void ensureTriageDirectory() {
		try {
			Path triageDir = Paths.get(System.getProperty("user.dir"), TRIAGE_DIR);
			if (!Files.exists(triageDir)) {
				Files.createDirectories(triageDir);
				logger.debug("Created triage directory: {}", triageDir.toAbsolutePath());
			}
		} catch (IOException e) {
			logger.warn("Failed to create triage directory", e);
		}
	}

	private static void saveConsolidatedBundle(ConsolidatedFailureBundle bundle, String runId) {
		try {
			ensureTriageDirectory();

			String fileName = "failure_bundle_consolidated.json";
			Path filePath = Paths.get(System.getProperty("user.dir"), TRIAGE_DIR, fileName);

			String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
			Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));

			logger.info("Consolidated failure bundle saved: {} ({} methods)", filePath.toAbsolutePath(),
					bundle.getMethodsWithFailures().size());
		} catch (IOException e) {
			logger.error("Failed to save consolidated bundle", e);
		}
	}

	/**
	 * Get total failure count across all runs.
	 * @return Total number of failures collected
	 */
	public static int getFailureCount() {
		return FAILURES_BY_RUN.values().stream().mapToInt(List::size).sum();
	}

	/**
	 * Get all collected failures across all runs.
	 * @return List of all individual failures
	 */
	public static List<IndividualFailure> getFailures() {
		List<IndividualFailure> allFailures = new ArrayList<>();
		FAILURES_BY_RUN.values().forEach(allFailures::addAll);
		return allFailures;
	}
}
