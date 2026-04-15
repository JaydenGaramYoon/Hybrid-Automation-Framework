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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import triage.model.bundles.ConsolidatedFailureBundle;
import triage.model.bundles.MethodFailureBundle;
import triage.model.history.HistoryRecord;
import triage.model.history.MinimalFailureRecord;
import triage.model.metadata.AnalysisMetadata;

/** Builds {@code failure_bundle_consolidated.json} from {@code test_run_history.jsonl} and per-method windows. */
public class ConsolidatedBundleGenerator {

	private static final Logger logger = LogManager.getLogger(ConsolidatedBundleGenerator.class);
	private static final ObjectMapper objectMapper = createObjectMapper();
	private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
			.withZone(ZoneId.of("UTC"));
	private static final String HISTORY_FILE = "reports/history/test_run_history.jsonl";
	private static final int MAX_RECENT_RUNS = 10;

	private static ObjectMapper createObjectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		// [CRITICAL] Preserve order of array elements during JSON serialization
		mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
		// Allow deserialization to ignore unknown properties (e.g., test_meta, started_at, ended_at)
		mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return mapper;
	}

	/**
	 * Generate Consolidated Bundle (history-based).
	 * 
	 * From test_run_history.jsonl, consolidates failure data by METHOD:
	 * 1. Finds all methods with FAIL status
	 * 2. Collects up to 10 most recent test runs per method
	 * 3. Creates analysis metadata (pass count, fail count, failure rate, status changes)
	 * 
	 * Called: Separately after FailureBundleCollector.collectAndGenerateBundle()
	 * 
	 * @return Consolidated failure bundle with per-method failure analysis, or empty bundle if no failures found
	 */
	public static ConsolidatedFailureBundle generateFromHistory() {
		logger.info("=== Generating Consolidated Failure Bundle from history ===");

		ConsolidatedFailureBundle bundle = new ConsolidatedFailureBundle();
		bundle.setGeneratedAt(epochToIso(System.currentTimeMillis()));

		try {
			Path historyPath = findHistoryFile();
			if (historyPath == null) {
				logger.warn("History file not found in any expected location");
				return bundle;
			}

			logger.info("Reading history from: {}", historyPath.toString());
			logger.info("File exists: {}, Readable: {}", Files.exists(historyPath), Files.isReadable(historyPath));
			long lineCount = Files.lines(historyPath, StandardCharsets.UTF_8).count();
			logger.info("Total lines in history file: {}", lineCount);

			// Step 1: Load all records from history
			List<HistoryRecord> allRecords = new ArrayList<>();
			try (Stream<String> lines = Files.lines(historyPath, StandardCharsets.UTF_8)) {
				allRecords = lines
						.map(line -> parseHistoryLine(line))
						.filter(record -> record != null)
						.collect(Collectors.toList());
			}

			logger.info("Loaded {} history records", allRecords.size());

			// Step 2: Find unique test_ids with FAIL
			List<String> testIdsWithFailure = allRecords.stream()
					.filter(r -> "FAIL".equals(r.getStatus()))
					.map(r -> r.getTestId())
					.distinct()
					.sorted()
					.collect(Collectors.toList());

			logger.info("Found {} test_ids with FAIL status", testIdsWithFailure.size());

			// Step 3: Generate METHOD consolidated bundle for each test_id
			Map<String, MethodFailureBundle> methodBundles = new LinkedHashMap<>();

			for (String testId : testIdsWithFailure) {
				// All records per test_id (excluding SKIP)
				List<HistoryRecord> testRecords = allRecords.stream()
						.filter(r -> testId.equals(r.getTestId()) && !("SKIP".equals(r.getStatus())))
						.sorted((a, b) -> Long.compare(b.getStartedAtEpochMs(), a.getStartedAtEpochMs())) // Most recent first
						.collect(Collectors.toList());

				if (testRecords.isEmpty()) {
					continue;
				}

				// Find first FAIL record
				HistoryRecord firstFail = testRecords.stream()
						.filter(r -> "FAIL".equals(r.getStatus()))
						.findFirst()
						.orElse(null);

				if (firstFail == null) {
					continue;
				}

				// Step 4: Collect up to 10 RUN data in reverse order from first FAIL
				List<HistoryRecord> recordsToInclude = collectRecordsFromFirstFail(testRecords, firstFail, MAX_RECENT_RUNS);

				// Step 5: Generate METHOD bundle
				MethodFailureBundle methodBundle = buildMethodBundle(testId, recordsToInclude);
				
				// Include bundle only if fail_count > 0
				// If fail_count == 0, all are PASS, so not a failure bundle
				if (methodBundle != null && methodBundle.getAnalysis() != null && 
					methodBundle.getAnalysis().getFailCount() > 0) {
					methodBundles.put(testId, methodBundle);
				}
			}

			bundle.setTotalRunsAnalyzed(MAX_RECENT_RUNS);
			bundle.setMethodsWithFailures(methodBundles);

			logger.info("Generated consolidated bundle with {} methods", methodBundles.size());

		} catch (IOException e) {
			logger.error("Failed to generate consolidated bundle", e);
		}

		return bundle;
	}

	/**
	 * Collect up to N most recent RUN data (top N from sorted-by-latest state).
	 * firstFail is used only for validation of "whether first FAIL exists".
	 * 
	 * [Change reason]
	 * - Collect without distinguishing FAIL/PASS from most recent record
	 * - If FAIL is old but there are newer PASS records, include them together
	 * - Through this all parameters are sorted by most recent
	 */
	private static List<HistoryRecord> collectRecordsFromFirstFail(List<HistoryRecord> testRecords,
			HistoryRecord firstFail, int maxRuns) {
		List<HistoryRecord> collected = new ArrayList<>();
		for (int i = 0; i < testRecords.size() && collected.size() < maxRuns; i++) {
			collected.add(testRecords.get(i));
		}

		collected.sort((a, b) -> Long.compare(a.getStartedAtEpochMs(), b.getStartedAtEpochMs()));

		logger.debug("Collected {} records for test {} (top {} recent runs)", collected.size(),
				firstFail.getTestId(), maxRuns);

		return collected;
	}

	/**
	 * Extract METHOD string and parameter.
	 * testId: "testCases.SignUp#verifySignUpValidationMessage[INVALID-PASSWORD]"
	 * → method: "verifySignUpValidationMessage", class: "SignUp", parameter: "[INVALID-PASSWORD]"
	 */
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

	private static MethodFailureBundle buildMethodBundle(String testId, List<HistoryRecord> records) {
		if (records.isEmpty()) {
			return null;
		}

		String method = extractMethod(testId);
		String className = extractClassName(testId);
		String parameter = extractParameter(testId);

		MethodFailureBundle bundle = new MethodFailureBundle(method, className);

		List<String> params = new ArrayList<>();
		if (!parameter.isEmpty()) {
			params.add(parameter);
		}
		bundle.setParameters(params);

		List<MinimalFailureRecord> failures = new ArrayList<>();
		for (HistoryRecord record : records) {
			MinimalFailureRecord minimal = new MinimalFailureRecord();
			minimal.setRunId(record.getRunId());
			minimal.setParameter(parameter);
			minimal.setStatus(record.getStatus());
			minimal.setStartedAtEpochMs(record.getStartedAtEpochMs());
			minimal.setDurationMs(record.getDurationMs());
			
			if ("FAIL".equals(record.getStatus())) {
				minimal.setExceptionType(record.getExceptionType());
				minimal.setExceptionMessage(record.getExceptionMessage());
				minimal.setErrorFingerprint(record.getErrorFingerprint());
			}
			
			failures.add(minimal);
		}
		
		failures.sort((a, b) -> {
			long diff = b.getStartedAtEpochMs() - a.getStartedAtEpochMs();
			return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
		});
		
		if (!failures.isEmpty()) {
			logger.debug("Failures sorted (newest first): {} ... {}", 
				failures.get(0).getStartedAtEpochMs(), 
				failures.get(failures.size() - 1).getStartedAtEpochMs());
		}
		
		bundle.setFailures(failures);

		AnalysisMetadata analysis = new AnalysisMetadata();
		analysis.setTotalRuns(records.size());
		analysis.setPassCount((int) records.stream().filter(r -> "PASS".equals(r.getStatus())).count());
		analysis.setFailCount((int) records.stream().filter(r -> "FAIL".equals(r.getStatus())).count());
		
		double failureRate = analysis.getTotalRuns() > 0 
			? (double) analysis.getFailCount() / analysis.getTotalRuns() 
			: 0.0;
		analysis.setFailureRate(failureRate);

		int statusChangeCount = 0;
		for (int i = 1; i < records.size(); i++) {
			if (!records.get(i - 1).getStatus().equals(records.get(i).getStatus())) {
				statusChangeCount++;
			}
		}
		analysis.setStatusChangeCount(statusChangeCount);

		String firstFailRunId = records.stream()
				.filter(r -> "FAIL".equals(r.getStatus()))
				.map(r -> r.getRunId())
				.findFirst()
				.orElse(records.isEmpty() ? null : records.get(0).getRunId());
		analysis.setFirstFailRunId(firstFailRunId);
		
		if (!records.isEmpty()) {
			analysis.setLastUpdatedRunId(records.get(records.size() - 1).getRunId());
		}

		bundle.setAnalysis(analysis);

		logger.debug("Built method bundle: {} (records: {})", testId, failures.size());

		return bundle;
	}

	private static HistoryRecord parseHistoryLine(String line) {
		try {
			if (line == null || line.trim().isEmpty()) {
				return null;
			}
			return objectMapper.readValue(line, HistoryRecord.class);
		} catch (Exception e) {
			logger.warn("Failed to parse history line: {} | Error: {}", 
				line != null && line.length() > 100 ? line.substring(0, 100) + "..." : line, 
				e.getMessage());
			return null;
		}
	}

	private static String epochToIso(long epochMs) {
		return ISO_FORMATTER.format(Instant.ofEpochMilli(epochMs));
	}

	private static Path findHistoryFile() {
		String[] pathCandidates = {
			Paths.get(System.getProperty("user.dir"), HISTORY_FILE).toAbsolutePath().toString(),
			Paths.get(System.getProperty("user.dir"), "reports", "history", "test_run_history.jsonl").toAbsolutePath().toString(),
		};

		for (String pathStr : pathCandidates) {
			try {
				Path path = Paths.get(pathStr);
				if (Files.exists(path) && Files.isReadable(path)) {
					logger.info("Found history file at: {}", path.toAbsolutePath());
					return path;
				}
				logger.debug("Path candidate not found or not readable: {}", path.toAbsolutePath());
			} catch (Exception e) {
				logger.debug("Path candidate failed: {} | Error: {}", pathStr, e.getMessage());
			}
		}

		return null;
	}
}
