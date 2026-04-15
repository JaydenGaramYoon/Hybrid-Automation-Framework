package triage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import triage.model.history.HistoryRecord;
import triage.model.history.IndividualFailure;
import triage.model.history.RecentHistory;
import triage.model.metadata.ErrorInfo;

/**
 * Assigns {@code FLAKY}, {@code BUG}, or {@code NEEDS_REVIEW} on {@link IndividualFailure} from history and stack text.
 */
public class TriageClassificationEngine {
	private static final Logger logger = LogManager.getLogger(TriageClassificationEngine.class);

	private static final String CLASSIFICATION_BUG = "BUG";
	private static final String CLASSIFICATION_FLAKY = "FLAKY";
	private static final String CLASSIFICATION_NEEDS_REVIEW = "NEEDS_REVIEW";

	public static void classify(IndividualFailure failure) {
		String classification = determineClassification(failure);
		failure.setClassification(classification);

		logger.info("[TRIAGE] {} -> {}", failure.getTestId(), classification);
	}

	private static String determineClassification(IndividualFailure failure) {
		if (isFlakyTest(failure)) {
			return CLASSIFICATION_FLAKY;
		}

		if (isClearBug(failure)) {
			return CLASSIFICATION_BUG;
		}

		return CLASSIFICATION_NEEDS_REVIEW;
	}

	private static boolean isFlakyTest(IndividualFailure failure) {
		RecentHistory history = failure.getRecentHistory();
		if (history == null || history.getRuns() == null) {
			return false;
		}

		java.util.List<HistoryRecord> records = history.getRuns();
		
		java.util.List<HistoryRecord> failPassRecords = records.stream()
				.filter(r -> "PASS".equals(r.getStatus()) || "FAIL".equals(r.getStatus()))
				.collect(java.util.stream.Collectors.toList());
		
		if (failPassRecords.size() < 2) {
			return false;
		}

		int statusChangeCount = 0;
		for (int i = 0; i < failPassRecords.size() - 1; i++) {
			HistoryRecord current = failPassRecords.get(i);
			HistoryRecord next = failPassRecords.get(i + 1);

			boolean currentPass = "PASS".equalsIgnoreCase(current.getStatus());
			boolean nextPass = "PASS".equalsIgnoreCase(next.getStatus());

			if (currentPass != nextPass) {
				statusChangeCount++;
			}
		}

		int failCount = (int) failPassRecords.stream().filter(r -> "FAIL".equals(r.getStatus())).count();
		int passCount = (int) failPassRecords.stream().filter(r -> "PASS".equals(r.getStatus())).count();
		double failureRate = failPassRecords.size() > 0 ? (double) failCount / failPassRecords.size() : 0.0;

		if (statusChangeCount >= 3 
		    && failureRate >= 0.20 && failureRate <= 0.80
		    && passCount > 0) {
			logger.info("[FLAKY] {} - Flip-rate: {} | Failure rate: {}% ({}/{}) | Pass: {}",
					failure.getTestId(), statusChangeCount, String.format("%.1f", failureRate * 100), failCount,
					failPassRecords.size(), passCount);
			return true;
		}

		return false;
	}

	private static boolean isClearBug(IndividualFailure failure) {
		ErrorInfo error = failure.getError();
		if (error == null) {
			return false;
		}

		String message = error.getExceptionMessage();
		String stackTrace = error.getStackTrace();

		if (message == null) {
			message = "";
		}

		String combinedText = (message + " " + (stackTrace != null ? stackTrace : "")).toLowerCase();

		String[] bugKeywords = { 
			"assertionerror", 
			"nosuchelementexception",
			"assertion error: expected",
			"element not found",
			"npe",
			"nullpointerexception",
			"classCastException",
			"illegalargumentexception",
			"index out of bounds",
			"unknowncommandexception"
		};

		for (String keyword : bugKeywords) {
			if (combinedText.contains(keyword)) {
				return true;
			}
		}

		return false;
	}
}
