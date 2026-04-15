package utils.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import utils.config.ConfigUtils;
import utils.run.RunContext;

/**
 * Minimal DB persistence for run-level summary, per-test results, and artifact paths (same intent as JSONL
 * / reports files). Skips all work when {@code db_host} is empty or {@code db_run_summary_enabled=false}.
 */
public final class RunSummaryDb {

	private static final Logger logger = LogManager.getLogger(RunSummaryDb.class);

	private static final String TABLE_RUN = "test_run";
	private static final String TABLE_RESULT = "test_run_result";
	private static final String TABLE_ARTIFACT = "run_artifact";

	private static final DBConnection DB = new DBConnection();
	private static volatile boolean tablesEnsured;
	private static volatile boolean runRowInserted;

	private RunSummaryDb() {
	}

	public static boolean isEnabled() {
		if (!DB.isConfigured()) {
			return false;
		}
		String flag = ConfigUtils.get("db_run_summary_enabled");
		if (flag != null && "false".equalsIgnoreCase(flag.trim())) {
			return false;
		}
		return true;
	}

	private static void ensureTables() throws SQLException {
		if (tablesEnsured) {
			return;
		}
		synchronized (RunSummaryDb.class) {
			if (tablesEnsured) {
				return;
			}
			String createRun = "CREATE TABLE IF NOT EXISTS " + TABLE_RUN + " ("
					+ "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
					+ "run_id VARCHAR(100) NOT NULL, "
					+ "suite_name VARCHAR(300) NULL, "
					+ "execution_env VARCHAR(80) NULL, "
					+ "started_at TIMESTAMP NOT NULL, "
					+ "ended_at TIMESTAMP NULL, "
					+ "pass_count INT NULL, "
					+ "fail_count INT NULL, "
					+ "skip_count INT NULL, "
					+ "jenkins_job VARCHAR(300) NULL, "
					+ "build_number VARCHAR(64) NULL, "
					+ "build_url VARCHAR(800) NULL, "
					+ "UNIQUE KEY uk_run_id (run_id)"
					+ ")";

			String createResult = "CREATE TABLE IF NOT EXISTS " + TABLE_RESULT + " ("
					+ "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
					+ "run_id VARCHAR(100) NOT NULL, "
					+ "test_id VARCHAR(600) NOT NULL, "
					+ "status VARCHAR(20) NOT NULL, "
					+ "duration_ms BIGINT NOT NULL, "
					+ "started_at_epoch_ms BIGINT NULL, "
					+ "ended_at_epoch_ms BIGINT NULL, "
					+ "exception_type VARCHAR(400) NULL, "
					+ "exception_message TEXT NULL, "
					+ "error_fingerprint VARCHAR(64) NULL, "
					+ "skip_reason VARCHAR(300) NULL, "
					+ "KEY idx_trr_run (run_id), "
					+ "KEY idx_trr_fp (error_fingerprint)"
					+ ")";

			String createArtifact = "CREATE TABLE IF NOT EXISTS " + TABLE_ARTIFACT + " ("
					+ "id BIGINT PRIMARY KEY AUTO_INCREMENT, "
					+ "run_id VARCHAR(100) NOT NULL, "
					+ "artifact_type VARCHAR(64) NOT NULL, "
					+ "relative_path VARCHAR(800) NOT NULL, "
					+ "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
					+ "KEY idx_run_artifact (run_id)"
					+ ")";

			try (Connection conn = DB.getConnection(); Statement st = conn.createStatement()) {
				st.execute(createRun);
				st.execute(createResult);
				st.execute(createArtifact);
			}
			tablesEnsured = true;
		}
	}

	/** First suite in the JVM: insert the run row (same {@link RunContext#getRunId()} for the whole process). */
	public static void insertRunStart(String suiteName) {
		if (!isEnabled()) {
			return;
		}
		synchronized (RunSummaryDb.class) {
			if (runRowInserted) {
				return;
			}
			String runId = RunContext.getRunId();
			String env = ConfigUtils.get("execution_env");
			String job = getenv("JOB_NAME");
			String buildNum = getenv("BUILD_NUMBER");
			String buildUrl = getenv("BUILD_URL");

			try {
				ensureTables();
				String sql = "INSERT INTO " + TABLE_RUN
						+ " (run_id, suite_name, execution_env, started_at, jenkins_job, build_number, build_url)"
						+ " VALUES (?,?,?,?,?,?,?)";
				try (Connection conn = DB.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
					ps.setString(1, runId);
					ps.setString(2, truncate(suiteName, 300));
					ps.setString(3, truncate(env, 80));
					ps.setTimestamp(4, Timestamp.from(Instant.now()));
					ps.setString(5, truncate(job, 300));
					ps.setString(6, truncate(buildNum, 64));
					ps.setString(7, truncate(buildUrl, 800));
					ps.executeUpdate();
				}
				runRowInserted = true;
				logger.info("Run summary DB: inserted test_run row for {}", runId);
			} catch (SQLException e) {
				logger.warn("Run summary DB: failed to insert test_run for {}", runId, e);
			}
		}
	}

	/** Last suite in the JVM: update counts and record artifact paths if files exist. */
	public static void finishRun(int passCount, int failCount, int skipCount) {
		if (!isEnabled()) {
			return;
		}
		String runId = RunContext.getRunId();
		try {
			ensureTables();
			String sql = "UPDATE " + TABLE_RUN + " SET ended_at=?, pass_count=?, fail_count=?, skip_count=? WHERE run_id=?";
			try (Connection conn = DB.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setTimestamp(1, Timestamp.from(Instant.now()));
				ps.setInt(2, passCount);
				ps.setInt(3, failCount);
				ps.setInt(4, skipCount);
				ps.setString(5, runId);
				int n = ps.executeUpdate();
				if (n == 0) {
					logger.debug("Run summary DB: no test_run row to update for {} (insert may have failed)", runId);
				}
			}
			recordArtifactsIfPresent(runId);
			logger.info("Run summary DB: finished test_run {} (pass={}, fail={}, skip={})", runId, passCount, failCount,
					skipCount);
		} catch (SQLException e) {
			logger.warn("Run summary DB: failed to finish test_run for {}", runId, e);
		}
	}

	public static void insertTestResult(String runId, String testId, String status, long durationMs,
			Long startedAtEpochMs, Long endedAtEpochMs, String exceptionType, String exceptionMessage,
			String errorFingerprint, String skipReason) {
		if (!isEnabled()) {
			return;
		}
		Objects.requireNonNull(runId, "runId");
		Objects.requireNonNull(testId, "testId");
		Objects.requireNonNull(status, "status");
		try {
			ensureTables();
			String sql = "INSERT INTO " + TABLE_RESULT
					+ " (run_id, test_id, status, duration_ms, started_at_epoch_ms, ended_at_epoch_ms,"
					+ " exception_type, exception_message, error_fingerprint, skip_reason)"
					+ " VALUES (?,?,?,?,?,?,?,?,?,?)";
			try (Connection conn = DB.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
				ps.setString(1, runId);
				ps.setString(2, truncate(testId, 600));
				ps.setString(3, truncate(status, 20));
				ps.setLong(4, durationMs);
				if (startedAtEpochMs != null) {
					ps.setLong(5, startedAtEpochMs);
				} else {
					ps.setObject(5, null);
				}
				if (endedAtEpochMs != null) {
					ps.setLong(6, endedAtEpochMs);
				} else {
					ps.setObject(6, null);
				}
				ps.setString(7, truncate(exceptionType, 400));
				ps.setString(8, exceptionMessage);
				ps.setString(9, truncate(errorFingerprint, 64));
				ps.setString(10, truncate(skipReason, 300));
				ps.executeUpdate();
			}
		} catch (SQLException e) {
			logger.warn("Run summary DB: failed to insert test_run_result for {} / {}", runId, testId, e);
		}
	}

	private static void recordArtifactsIfPresent(String runId) throws SQLException {
		String clean = runId.replaceAll("^run-", "");
		Path base = Paths.get(System.getProperty("user.dir"));

		String[][] pairs = { { "failure_bundle", "reports/failure/run-" + clean + "_failure_bundle.json" },
				{ "skip_bundle", "reports/skip/run-" + clean + "_skip_bundle.json" },
				{ "triage_report", "reports/triage/run-" + clean + "_triage_report.json" } };

		for (String[] pair : pairs) {
			String type = pair[0];
			String rel = pair[1];
			Path full = base.resolve(rel);
			if (!Files.isRegularFile(full)) {
				continue;
			}
			insertArtifactRow(runId, type, rel);
		}
	}

	private static void insertArtifactRow(String runId, String artifactType, String relativePath) throws SQLException {
		String sql = "INSERT INTO " + TABLE_ARTIFACT + " (run_id, artifact_type, relative_path) VALUES (?,?,?)";
		try (Connection conn = DB.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, runId);
			ps.setString(2, truncate(artifactType, 64));
			ps.setString(3, truncate(relativePath, 800));
			ps.executeUpdate();
		}
	}

	private static String getenv(String name) {
		String v = System.getenv(name);
		return v != null && !v.isEmpty() ? v : null;
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() <= max ? s : s.substring(0, max);
	}
}
