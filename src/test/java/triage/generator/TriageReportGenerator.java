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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import triage.model.bundles.ConsolidatedFailureBundle;
import triage.model.bundles.FailureBundle;
import triage.model.bundles.MethodFailureBundle;
import triage.model.history.HistoryRecord;
import triage.model.history.IndividualFailure;

/** Emits {@code reports/triage/*_triage_report.json} from the consolidated failure bundle and classifications. */
public class TriageReportGenerator {
	private static final Logger logger = LogManager.getLogger(TriageReportGenerator.class);
	private static final ObjectMapper objectMapper = new ObjectMapper()
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneId.of("UTC"));
	private static final String TRIAGE_DIR = "reports/triage";
	private static final String CONSOLIDATED_BUNDLE = "reports/triage/failure_bundle_consolidated.json";
	private static final String HISTORY_FILE = "reports/history/test_run_history.jsonl";

	private static final class HistoryRunFailures {
		final Set<String> failedTestIds;
		final Map<String, String> tcIdByTestId;
		final Map<String, String> exceptionMessageByTestId;

		HistoryRunFailures(Set<String> failedTestIds, Map<String, String> tcIdByTestId,
				Map<String, String> exceptionMessageByTestId) {
			this.failedTestIds = failedTestIds;
			this.tcIdByTestId = tcIdByTestId;
			this.exceptionMessageByTestId = exceptionMessageByTestId;
		}
	}

	/**
	 * Generate triage report combining current run failures with consolidated analysis.
	 * 
	 * Combines failures from failure_bundle_run-*.json with consolidated bundle data
	 * to generate comprehensive triage report with per-method classification.
	 * Includes failure statistics and marks which methods failed in current run.
	 * 
	 * @param bundle Failure bundle from current run (required)
	 * @param runId Current run ID
	 */
	public static void generateReport(FailureBundle bundle, String runId) {
		if (bundle == null) {
			logger.warn("No failures to generate report");
			return;
		}
		List<IndividualFailure> bundleFailures = bundle.getFailures();
		if (bundleFailures == null) {
			bundleFailures = Collections.emptyList();
		}

		try {
			String normalizedRunId = normalizeRunId(runId);
			HistoryRunFailures historyFails = loadHistoryFailuresForRun(normalizedRunId);
			Set<String> failedTestIdsThisRun = new HashSet<>(historyFails.failedTestIds);
			Map<String, String> tcFromHistory = historyFails.tcIdByTestId;
			Map<String, String> exceptionFromHistory = historyFails.exceptionMessageByTestId;

			ConsolidatedFailureBundle consolidatedBundle = loadConsolidatedBundle();

			Map<String, IndividualFailure> currentFailureMap = new HashMap<>();
			for (IndividualFailure failure : bundleFailures) {
				if (failure.getTestId() != null) {
					currentFailureMap.put(failure.getTestId(), failure);
				}
			}

			if (failedTestIdsThisRun.isEmpty() && !currentFailureMap.isEmpty()) {
				logger.warn(
						"No FAIL rows in history for run {}; using failure bundle test_ids as fallback for current-run alignment.",
						normalizedRunId);
				failedTestIdsThisRun.addAll(currentFailureMap.keySet());
			}

			for (IndividualFailure f : bundleFailures) {
				String tid = f.getTestId();
				if (tid != null && !failedTestIdsThisRun.contains(tid)) {
					logger.warn(
							"Failure bundle test_id {} not present as FAIL in history for run {}; ignoring for current-run triage alignment (bundle/history mismatch).",
							tid, normalizedRunId);
				}
			}

			Map<String, Object> report = new LinkedHashMap<>();
			report.put("generated_at", ISO_FORMATTER.format(Instant.now()));
			report.put("latest_run_id", runId);
			report.put("current_run_failure_count", failedTestIdsThisRun.size());

			Map<String, Object> methodsWithFailures = new LinkedHashMap<>();

			if (consolidatedBundle != null && consolidatedBundle.getMethodsWithFailures() != null) {
				for (Map.Entry<String, MethodFailureBundle> entry : consolidatedBundle.getMethodsWithFailures().entrySet()) {
					String fullTestId = entry.getKey();
					MethodFailureBundle methodBundle = entry.getValue();
					boolean failedThisRun = failedTestIdsThisRun.contains(fullTestId);

					if (methodBundle.getAnalysis() != null && methodBundle.getAnalysis().getFailureRate() == 0.0
							&& !failedThisRun) {
						logger.debug("Skip method with no failures in recent history: {}", fullTestId);
						continue;
					}

					boolean hasCurrentRunFailure = failedThisRun;

					String methodClassification = determineMethodClassification(methodBundle, hasCurrentRunFailure);

					Map<String, Object> methodInfo = new LinkedHashMap<>();
					methodInfo.put("method", methodBundle.getMethod());
					methodInfo.put("class", methodBundle.getClassName());
					methodInfo.put("parameters", methodBundle.getParameters());
					methodInfo.put("analysis", methodBundle.getAnalysis());
					methodInfo.put("classification", methodClassification);
					methodInfo.put("is_failed_in_current_run", hasCurrentRunFailure);

					if (hasCurrentRunFailure) {
						String ht = tcFromHistory.get(fullTestId);
						if (ht != null && !ht.isBlank()) {
							methodInfo.put("tc_id", ht.trim());
						}
					}

					methodsWithFailures.put(fullTestId, methodInfo);
				}
			}

			for (String failTid : failedTestIdsThisRun) {
				IndividualFailure failure = currentFailureMap.get(failTid);
				@SuppressWarnings("unchecked")
				Map<String, Object> methodInfo = (Map<String, Object>) methodsWithFailures.get(failTid);
				if (methodInfo == null) {
					if (failure != null) {
						methodInfo = buildSyntheticMethodInfoForFailure(failTid, failure);
						applyHistoryTcToMethodAndDetail(methodInfo, tcFromHistory.get(failTid));
					} else {
						methodInfo = buildSyntheticMethodInfoFromHistoryOnly(failTid, tcFromHistory.get(failTid),
								exceptionFromHistory.get(failTid));
					}
					methodsWithFailures.put(failTid, methodInfo);
				} else {
					methodInfo.put("is_failed_in_current_run", true);
					String ht = tcFromHistory.get(failTid);
					if (ht != null && !ht.isBlank()) {
						methodInfo.put("tc_id", ht.trim());
					} else if (failure != null) {
						if (failure.getError() != null && failure.getError().getTcId() != null
								&& !failure.getError().getTcId().isBlank()) {
							methodInfo.put("tc_id", failure.getError().getTcId().trim());
						} else if (failure.getMeta() != null && failure.getMeta().getTcId() != null
								&& !failure.getMeta().getTcId().isBlank()) {
							methodInfo.put("tc_id", failure.getMeta().getTcId().trim());
						}
					}
					if (failure != null) {
						methodInfo.put("current_run_failure", buildCurrentRunFailureDetail(failure));
						applyHistoryTcToMethodAndDetail(methodInfo, ht);
					} else {
						methodInfo.put("current_run_failure",
								buildMinimalCurrentRunFailureFromHistory(failTid, tcFromHistory.get(failTid),
										exceptionFromHistory.get(failTid)));
					}
				}
			}

			report.put("methods_with_failures", methodsWithFailures);

			// Counts for this run only (Slack/CI triage line): failed methods in the current run, by label.
			Map<String, Integer> classificationStats = new LinkedHashMap<>();
			classificationStats.put("BUG", 0);
			classificationStats.put("FLAKY", 0);
			classificationStats.put("NEEDS_REVIEW", 0);

			for (Object methodObj : methodsWithFailures.values()) {
				if (methodObj instanceof Map) {
					Map<String, Object> methodMap = (Map<String, Object>) methodObj;
					Object cur = methodMap.get("is_failed_in_current_run");
					if (!Boolean.TRUE.equals(cur)) {
						continue;
					}
					String classification = (String) methodMap.get("classification");
					if (classification != null && classificationStats.containsKey(classification)) {
						classificationStats.put(classification, classificationStats.get(classification) + 1);
					}
				}
			}

			report.put("classification_summary", classificationStats);

			String reportFileName = "run-" + runId.replaceAll("^run-", "") + "_triage_report.json";
			Path reportPath = Paths.get(TRIAGE_DIR, reportFileName);

			Files.createDirectories(reportPath.getParent());
			String reportJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report);
			Files.write(reportPath, reportJson.getBytes(StandardCharsets.UTF_8));

			logger.info("Triage report saved: {}", reportPath.toAbsolutePath());
			logSummary(classificationStats, failedTestIdsThisRun.size());

		} catch (IOException e) {
			logger.error("Failed to generate triage report", e);
		}
	}
	
	/**
	 * Read consolidated bundle.
	 */
	private static ConsolidatedFailureBundle loadConsolidatedBundle() {
		try {
			Path consolidatedPath = Paths.get(CONSOLIDATED_BUNDLE);
			if (Files.exists(consolidatedPath)) {
				String jsonContent = new String(Files.readAllBytes(consolidatedPath), StandardCharsets.UTF_8);
				return objectMapper.readValue(jsonContent, ConsolidatedFailureBundle.class);
			}
		} catch (IOException e) {
			logger.warn("Failed to load consolidated bundle: {}", e.getMessage());
		}
		return null;
	}

	private static String normalizeRunId(String runId) {
		if (runId == null || runId.isEmpty()) {
			return "";
		}
		String t = runId.trim();
		return t.startsWith("run-") ? t : "run-" + t;
	}

	private static Path findHistoryFile() {
		String[] pathCandidates = {
				Paths.get(System.getProperty("user.dir"), HISTORY_FILE).toAbsolutePath().toString(),
				Paths.get(System.getProperty("user.dir"), "reports", "history", "test_run_history.jsonl")
						.toAbsolutePath().toString(),
		};
		for (String pathStr : pathCandidates) {
			try {
				Path path = Paths.get(pathStr);
				if (Files.exists(path) && Files.isReadable(path)) {
					return path;
				}
			} catch (Exception e) {
				logger.debug("History path candidate failed: {} | {}", pathStr, e.getMessage());
			}
		}
		return null;
	}

	private static HistoryRecord parseHistoryLine(String line) {
		try {
			if (line == null || line.trim().isEmpty()) {
				return null;
			}
			return objectMapper.readValue(line, HistoryRecord.class);
		} catch (Exception e) {
			logger.warn("Failed to parse history line for triage: {}", e.getMessage());
			return null;
		}
	}

	private static HistoryRunFailures loadHistoryFailuresForRun(String normalizedRunId) {
		Set<String> ids = new HashSet<>();
		Map<String, String> tcMap = new HashMap<>();
		Map<String, String> excMap = new HashMap<>();
		Path path = findHistoryFile();
		if (path == null) {
			logger.warn("History file not found; current-run triage flags will fall back to empty (expected at {}).",
					HISTORY_FILE);
			return new HistoryRunFailures(ids, tcMap, excMap);
		}
		try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
			lines.forEach(line -> {
				HistoryRecord rec = parseHistoryLine(line);
				if (rec == null || !"FAIL".equals(rec.getStatus())) {
					return;
				}
				if (!normalizedRunId.equals(normalizeRunId(rec.getRunId()))) {
					return;
				}
				String tid = rec.getTestId();
				if (tid == null || tid.isEmpty()) {
					return;
				}
				ids.add(tid);
				if (rec.getTcId() != null && !rec.getTcId().isBlank()) {
					tcMap.put(tid, rec.getTcId().trim());
				}
				if (rec.getExceptionMessage() != null && !rec.getExceptionMessage().isBlank()) {
					excMap.put(tid, rec.getExceptionMessage());
				}
			});
		} catch (IOException e) {
			logger.warn("Failed to read history for triage: {}", e.getMessage());
		}
		return new HistoryRunFailures(ids, tcMap, excMap);
	}

	/**
	 * Determine method-level classification.
	 * totalCount = 10 fixed baseline
	 */
	private static String determineMethodClassification(MethodFailureBundle methodBundle, boolean hasCurrentRunFailure) {
		if (methodBundle == null || methodBundle.getAnalysis() == null) {
			return "NEEDS_REVIEW";
		}
		
		int failCount = methodBundle.getAnalysis().getFailCount();
		int statusChangeCount = methodBundle.getAnalysis().getStatusChangeCount();
		double failureRate = methodBundle.getAnalysis().getFailureRate();
		
		if (statusChangeCount >= 3 && failureRate >= 0.2 && failureRate < 1.0) {
			return "FLAKY";
		}
		
		if (failureRate == 1.0 && failCount >= 3) {
			return "BUG";
		}
		
		return "NEEDS_REVIEW";
	}
	
	/**
	 * Logs classification counts for the current run (same distribution as {@code classification_summary}).
	 */
	private static void logSummary(Map<String, Integer> classificationStats, int currentRunFailureCount) {
		int sum = classificationStats.values().stream().mapToInt(Integer::intValue).sum();
		if (sum == 0 && currentRunFailureCount == 0) {
			return;
		}
		int denom = sum > 0 ? sum : (currentRunFailureCount > 0 ? currentRunFailureCount : 1);

		logger.info("========== TRIAGE CLASSIFICATION SUMMARY (current run) ==========");
		logger.info("Classified failures in this run: {}", sum);
		logger.info("BUG:            {} ({}%)", classificationStats.get("BUG"),
				String.format("%.1f", classificationStats.get("BUG") * 100.0 / denom));
		logger.info("FLAKY:          {} ({}%)", classificationStats.get("FLAKY"),
				String.format("%.1f", classificationStats.get("FLAKY") * 100.0 / denom));
		logger.info("NEEDS_REVIEW:   {} ({}%)", classificationStats.get("NEEDS_REVIEW"),
				String.format("%.1f", classificationStats.get("NEEDS_REVIEW") * 100.0 / denom));
		logger.info("==================================================================");
		if (sum != currentRunFailureCount) {
			logger.warn(
					"classification_summary sum ({}) does not match current run failure count ({}); check triage data.",
					sum, currentRunFailureCount);
		}
	}

	private static void applyHistoryTcToMethodAndDetail(Map<String, Object> methodInfo, String historyTc) {
		if (historyTc == null || historyTc.isBlank()) {
			return;
		}
		String t = historyTc.trim();
		methodInfo.put("tc_id", t);
		@SuppressWarnings("unchecked")
		Map<String, Object> detail = (Map<String, Object>) methodInfo.get("current_run_failure");
		if (detail != null) {
			detail.put("tc_id", t);
		}
	}

	private static Map<String, Object> buildMinimalCurrentRunFailureFromHistory(String testId, String tcId,
			String exceptionMessage) {
		Map<String, Object> currentRunFailureDetail = new LinkedHashMap<>();
		currentRunFailureDetail.put("test_id", testId);
		String msg = exceptionMessage;
		if (msg == null || msg.isBlank()) {
			msg = "(no failure bundle entry; see history for this run)";
		}
		currentRunFailureDetail.put("error_message", msg);
		currentRunFailureDetail.put("duration_ms", 0L);
		currentRunFailureDetail.put("screenshot", null);
		if (tcId != null && !tcId.isBlank()) {
			currentRunFailureDetail.put("tc_id", tcId.trim());
		}
		return currentRunFailureDetail;
	}

	private static Map<String, Object> buildSyntheticMethodInfoFromHistoryOnly(String testId, String tcId,
			String exceptionMessage) {
		MethodFailureBundle stub = new MethodFailureBundle(extractMethod(testId), extractClassName(testId));
		List<String> params = new ArrayList<>();
		String p = extractParameter(testId);
		if (!p.isEmpty()) {
			params.add(p);
		}
		stub.setParameters(params);
		stub.setAnalysis(null);
		Map<String, Object> methodInfo = new LinkedHashMap<>();
		methodInfo.put("method", stub.getMethod());
		methodInfo.put("class", stub.getClassName());
		methodInfo.put("parameters", stub.getParameters());
		methodInfo.put("analysis", null);
		methodInfo.put("classification", determineMethodClassification(stub, true));
		methodInfo.put("is_failed_in_current_run", true);
		if (tcId != null && !tcId.isBlank()) {
			methodInfo.put("tc_id", tcId.trim());
		}
		methodInfo.put("current_run_failure", buildMinimalCurrentRunFailureFromHistory(testId, tcId, exceptionMessage));
		return methodInfo;
	}

	private static Map<String, Object> buildCurrentRunFailureDetail(IndividualFailure currentFailure) {
		Map<String, Object> currentRunFailureDetail = new LinkedHashMap<>();
		currentRunFailureDetail.put("test_id", currentFailure.getTestId());
		currentRunFailureDetail.put("error_message", currentFailure.getError() != null
				? currentFailure.getError().getExceptionMessage() : "");
		currentRunFailureDetail.put("duration_ms", currentFailure.getDurationMs());
		currentRunFailureDetail.put("screenshot", currentFailure.getScreenshot() != null
				? currentFailure.getScreenshot().getFilePath() : null);
		if (currentFailure.getError() != null && currentFailure.getError().getTcId() != null
				&& !currentFailure.getError().getTcId().isEmpty()) {
			currentRunFailureDetail.put("tc_id", currentFailure.getError().getTcId());
		}
		return currentRunFailureDetail;
	}

	/**
	 * When consolidated bundle has no row for this test_id (e.g. key drift), still emit a triage row so Slack counts
	 * match {@link FailureBundle#getFailures()}.
	 */
	private static Map<String, Object> buildSyntheticMethodInfoForFailure(String testId, IndividualFailure failure) {
		MethodFailureBundle stub = new MethodFailureBundle(extractMethod(testId), extractClassName(testId));
		List<String> params = new ArrayList<>();
		String p = extractParameter(testId);
		if (!p.isEmpty()) {
			params.add(p);
		}
		stub.setParameters(params);
		stub.setAnalysis(null);
		String classification = determineMethodClassification(stub, true);
		Map<String, Object> methodInfo = new LinkedHashMap<>();
		methodInfo.put("method", stub.getMethod());
		methodInfo.put("class", stub.getClassName());
		methodInfo.put("parameters", stub.getParameters());
		methodInfo.put("analysis", null);
		methodInfo.put("classification", classification);
		methodInfo.put("is_failed_in_current_run", true);
		if (failure.getError() != null && failure.getError().getTcId() != null
				&& !failure.getError().getTcId().isBlank()) {
			methodInfo.put("tc_id", failure.getError().getTcId().trim());
		} else if (failure.getMeta() != null && failure.getMeta().getTcId() != null
				&& !failure.getMeta().getTcId().isBlank()) {
			methodInfo.put("tc_id", failure.getMeta().getTcId().trim());
		}
		methodInfo.put("current_run_failure", buildCurrentRunFailureDetail(failure));
		return methodInfo;
	}

	/** Same rules as {@link ConsolidatedBundleGenerator} for test_id strings. */
	private static String extractMethod(String testId) {
		int methodStart = testId.indexOf('#') + 1;
		if (methodStart == 0) {
			return testId;
		}
		int bracketIndex = testId.indexOf('[', methodStart);
		if (bracketIndex == -1) {
			return testId.substring(methodStart);
		}
		return testId.substring(methodStart, bracketIndex);
	}

	private static String extractParameter(String testId) {
		int bracketIndex = testId.indexOf('[');
		if (bracketIndex == -1) {
			return "";
		}
		return testId.substring(bracketIndex);
	}

	private static String extractClassName(String testId) {
		int hashIndex = testId.indexOf('#');
		if (hashIndex == -1) {
			return "";
		}
		String classPart = testId.substring(0, hashIndex);
		int lastDot = classPart.lastIndexOf('.');
		if (lastDot == -1) {
			return classPart;
		}
		return classPart.substring(lastDot + 1);
	}
}
