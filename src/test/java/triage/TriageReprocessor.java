package triage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import triage.generator.ConsolidatedBundleGenerator;
import triage.generator.TriageReportGenerator;
import triage.model.bundles.ConsolidatedFailureBundle;
import triage.model.bundles.FailureBundle;
import triage.model.history.IndividualFailure;

/** CLI: re-reads a {@code failure_bundle_*.json} and rebuilds triage output with current classification rules. */
public class TriageReprocessor {
	private static final Logger logger = LogManager.getLogger(TriageReprocessor.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	public static void main(String[] args) {
		if (args.length == 0) {
			System.err.println("Usage: java TriageReprocessor <failure_bundle_json_path>");
			System.err.println("Example: java TriageReprocessor reports/triage/failure_bundle_run-1774821151883.json");
			System.exit(1);
		}

		String failureBundlePath = args[0];
		reprocess(failureBundlePath);
	}

	/**
	 * Reclassifies each failure, regenerates {@code failure_bundle_consolidated.json} from history, and rewrites triage JSON.
	 */
	public static void reprocess(String failureBundlePath) {
		try {
			Path bundlePath = Paths.get(failureBundlePath);

			if (!Files.exists(bundlePath)) {
				logger.error("File not found: {}", bundlePath.toAbsolutePath());
				System.exit(1);
			}

			logger.info("[REPROCESS] Reading failure bundle from: {}", bundlePath.toAbsolutePath());
			String jsonContent = Files.readString(bundlePath, StandardCharsets.UTF_8);
			FailureBundle bundle = objectMapper.readValue(jsonContent, FailureBundle.class);

			if (bundle == null || bundle.getFailures() == null) {
				logger.error("Invalid failure bundle: null or missing failures");
				System.exit(1);
			}

			String runId = bundle.getRunId();
			logger.info("[REPROCESS] Bundle contains {} failures for run: {}", bundle.getFailures().size(), runId);

			int count = 0;
			for (IndividualFailure failure : bundle.getFailures()) {
				String oldClassification = failure.getClassification();
				TriageClassificationEngine.classify(failure);
				String newClassification = failure.getClassification();

				if (oldClassification == null || !oldClassification.equals(newClassification)) {
					logger.info("[REPROCESS] {} : {} -> {}", 
						failure.getTestId(), oldClassification, newClassification);
					count++;
				}
			}

			logger.info("[REPROCESS] Reclassified {} failures", count);

			logger.info("[REPROCESS] Regenerating consolidated bundle from history...");
			ConsolidatedFailureBundle consolidatedBundle = ConsolidatedBundleGenerator.generateFromHistory();
			if (consolidatedBundle != null && consolidatedBundle.getMethodsWithFailures() != null) {
				saveConsolidatedBundle(consolidatedBundle);
				logger.info("[REPROCESS] Consolidated bundle regenerated with {} methods", 
					consolidatedBundle.getMethodsWithFailures().size());
			}

			logger.info("[REPROCESS] Regenerating triage report...");
			TriageReportGenerator.generateReport(bundle, runId);

			logger.info("[REPROCESS] Complete! Check reports/triage/triage_report_{}.json", runId);

		} catch (Exception e) {
			logger.error("[REPROCESS] Failed to reprocess", e);
			e.printStackTrace();
			System.exit(1);
		}
	}

	private static void saveConsolidatedBundle(ConsolidatedFailureBundle bundle) {
		try {
			String fileName = "failure_bundle_consolidated.json";
			Path triageDir = Paths.get(System.getProperty("user.dir"), "reports", "triage");
			Files.createDirectories(triageDir);

			Path filePath = triageDir.resolve(fileName);

			String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
			Files.write(filePath, json.getBytes(StandardCharsets.UTF_8));

			logger.info("[REPROCESS] Consolidated bundle saved to: {}", filePath.toAbsolutePath());
		} catch (Exception e) {
			logger.error("[REPROCESS] Failed to save consolidated bundle", e);
		}
	}
}
