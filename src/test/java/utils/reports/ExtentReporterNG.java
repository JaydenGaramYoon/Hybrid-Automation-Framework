package utils.reports;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import utils.run.RunContext;

/**
 * Lazily creates one {@link com.aventstack.extentreports.ExtentReports} per JVM and writes under
 * {@code reports/extent_report/} using {@link RunContext#getRunId()}.
 */
public class ExtentReporterNG {

	private static ExtentReports extent = null;
	private static String reportPath = null;

	/** Returns the singleton report; creates it and sets the HTML path on first use. */
	public static ExtentReports getReportObject() {
		if (extent == null) {
			String runId = RunContext.getRunId();
			String cleanRunId = runId.replaceAll("^run-", "");
			reportPath = System.getProperty("user.dir") + "//reports//extent_report//run-" + cleanRunId + "_extent_report.html";
			ExtentSparkReporter reporter = new ExtentSparkReporter(reportPath);
			reporter.config().setReportName("Automation Framework Selenium Report | Garam Engineer");
			reporter.config().setDocumentTitle("Test Results");
			extent = new ExtentReports();
			extent.attachReporter(reporter);
			extent.setSystemInfo("QA Engineer", "Garam Yoon");
		}
		return extent;
	}

	/** Persists buffered events to disk. */
	public static void flush() {
		if (extent != null) {
			extent.flush();
		}
	}

	/** Clears the singleton so the next run gets a new report file. */
	public static void reset() {
		extent = null;
		reportPath = null;
	}
}
