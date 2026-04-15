package utils.listeners;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.openqa.selenium.WebDriver;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.xml.XmlSuite;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;

import testBase.BaseClass;
import triage.collector.FailureBundleCollector;
import triage.generator.FailureBundleGenerator;
import triage.model.history.IndividualFailure;
import utils.config.ConfigUtils;
import utils.db.RunSummaryDb;
import utils.reports.ExtentManager;
import utils.reports.ExtentReporterNG;
import utils.run.RunContext;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TestNG {@link ITestListener}: Extent nodes, {@link ThreadContext}, per-run Log4j file appender, and
 * {@link FailureBundleCollector} / {@link FailureBundleGenerator} on failures.
 */
public class Listeners extends BaseClass implements ITestListener {
	private static final Logger logger = LogManager.getLogger(Listeners.class);
	private static final String author = ConfigUtils.get("author_name");
	private static final String role = ConfigUtils.get("author_role");
	private static final AtomicInteger activeSuites = new AtomicInteger(0);
	private static final AtomicInteger accumulatedPass = new AtomicInteger();
	private static final AtomicInteger accumulatedFail = new AtomicInteger();
	private static final AtomicInteger accumulatedSkip = new AtomicInteger();
	private static boolean log4jAppenderAdded = false;
	private static volatile String lastRunId;
	private static volatile String lastTestId;
	ExtentTest test;
	ExtentReports extent = ExtentReporterNG.getReportObject();
	private static final Object TEST_LOCK = new Object();

	@Override
	public void onTestStart(ITestResult result) {
		synchronized (TEST_LOCK) {
			String basicTestId = RunContext.testId(result.getTestClass().getRealClass(),
					result.getMethod().getMethodName());
			ThreadContext.put("testId", basicTestId);
			lastTestId = basicTestId;
			String runId = RunContext.getRunId();
			ThreadContext.put("runId", runId);
			lastRunId = runId;

			RunContext.refreshTcIdFromTestParameters(result);
			String tc = RunContext.getTcId();
			if (tc != null && !tc.isEmpty()) {
				ThreadContext.put("tcId", tc);
			} else {
				ThreadContext.remove("tcId");
			}

			RunContext.setSkipReason(null);

			BaseClass base = (BaseClass) result.getInstance();
			if (base != null) {
				ThreadContext.put("executionEnv", base.executionEnv != null ? base.executionEnv : "");
				ThreadContext.put("browserName", base.browserName != null ? base.browserName : "");
			}

			ExtentTest extentTest = extent.createTest(result.getMethod().getMethodName());
			ExtentManager.setTest(extentTest);

			extentTest.assignAuthor(author);
			extentTest.assignCategory(role);

			String executionEnv = ThreadContext.get("executionEnv");
			if (executionEnv != null && !executionEnv.isEmpty()) {
				extentTest.assignCategory(executionEnv);
			}

			if (base != null) {
				for (String group : result.getMethod().getGroups()) {
					extentTest.assignCategory(group);
				}
				String browserName = ThreadContext.get("browserName");
				if (browserName != null && !browserName.isEmpty()) {
					extentTest.assignDevice(browserName);
				}
			}

			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public void onTestSuccess(ITestResult result) {
		synchronized (TEST_LOCK) {
			String skipReason = RunContext.getSkipReason();

			if (skipReason != null && !skipReason.isEmpty()) {
				logger.info("[SKIP PENDING] {} [skipReason={}] Will be skipped, not recording PASS",
						result.getMethod().getMethodName(), skipReason);
				return;
			}

			logger.info("[PASS] {} [testId={}]", result.getMethod().getMethodName(), RunContext.testId(result));
			ExtentTest extentTest = ExtentManager.getTest();
			if (extentTest != null) {
				extentTest.log(Status.PASS, "[TEST RESULT] PASSED");
			}

			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			ExtentManager.setTest(null);
		}
	}

	@Override
	public void onTestFailure(ITestResult result) {
		synchronized (TEST_LOCK) {
			try {
				String skipReason = RunContext.getSkipReason();
				logger.info("[FAIL] {} [testId={}] | {}", result.getMethod().getMethodName(), RunContext.testId(result),
						result.getThrowable().getMessage());

				ExtentTest extentTest = ExtentManager.getTest();
				if (extentTest == null) {
					logger.error("ExtentTest is null at onTestFailure");
					return;
				}

				extentTest.fail("[TEST RESULT] FAILED");
				extentTest.fail("[FAILURE] " + result.getThrowable().getMessage());
				extentTest.fail(result.getThrowable());

				String screenshotPath = null;
				WebDriver driver = null;

				try {
					driver = (WebDriver) result.getTestClass().getRealClass().getField("driver")
							.get(result.getInstance());

					if (driver != null) {
						screenshotPath = takeScreenshot(result, driver);
						if (screenshotPath != null) {
							String extentPath = extentRelativeScreenshotPath(screenshotPath);
							extentTest.addScreenCaptureFromPath(extentPath, result.getMethod().getMethodName());
						}
					}
				} catch (Exception e) {
					logger.warn("Screenshot failed", e);
				}

				try {
					String savedTestId = ThreadContext.get("testId");
					String savedRunId = ThreadContext.get("runId");
					String savedTcId = ThreadContext.get("tcId");

					IndividualFailure failure = FailureBundleGenerator.createFromTestResult(result, screenshotPath,
							driver);
					FailureBundleCollector.addFailure(failure);

					ThreadContext.put("testId", savedTestId);
					ThreadContext.put("runId", savedRunId);
					if (savedTcId != null && !savedTcId.isEmpty()) {
						ThreadContext.put("tcId", savedTcId);
					} else {
						ThreadContext.remove("tcId");
					}

					logger.debug("Failure bundle collected for test: {}", RunContext.testId(result));
				} catch (Exception e) {
					logger.warn("Failed to create failure bundle", e);
				}

			} catch (Exception e) {
				logger.error("onTestFailure itself failed!", e);
			}

			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			ExtentManager.setTest(null);
		}
	}

	@Override
	public void onTestSkipped(ITestResult result) {
		synchronized (TEST_LOCK) {
			try {
				String testId = RunContext.testId(result);
				if (testId == null || testId.isEmpty()) {
					testId = result.getMethod().getQualifiedName();
					if (testId != null && !testId.isEmpty()) {
						testId = testId.replaceAll("\\.([^.]+)$", "#$1");
					} else {
						testId = ThreadContext.get("testId");
					}
				}
				ThreadContext.put("testId", testId);
				lastTestId = testId;
				logger.info("[SKIP] {} [testId={}]", result.getMethod().getMethodName(), testId);

				ExtentTest extentTest = ExtentManager.getTest();

				if (extentTest == null) {
					logger.error("ExtentTest is null at onTestSkipped - creating new ExtentTest");

					extentTest = extent.createTest(result.getMethod().getMethodName());
					ExtentManager.setTest(extentTest);
				}

				String skipReason = RunContext.getSkipReason();
				if (skipReason == null || skipReason.isEmpty()) {
					Throwable throwable = result.getThrowable();
					if (throwable != null) {
						String analyzedReason = analyzeSkipReasonFromThrowable(throwable);
						if (analyzedReason != null && !analyzedReason.isEmpty()
								&& !"unknown_skip".equals(analyzedReason)) {
							RunContext.setSkipReason(analyzedReason);
							skipReason = analyzedReason;
						}
					}
				}

				extentTest.skip("[TEST RESULT] SKIPPED");

				try {
					String savedTestId = ThreadContext.get("testId");
					String savedRunId = ThreadContext.get("runId");
					String savedTcId = ThreadContext.get("tcId");

					String skipReasonBeforeBundle = RunContext.getSkipReason();

					triage.model.history.IndividualSkip skip = triage.generator.SkipBundleGenerator
							.createFromTestResult(result);

					RunContext.setSkipReason(skipReasonBeforeBundle);

					logSkipDetailsToExtentReport(extentTest, result, skipReasonBeforeBundle);

					triage.generator.SkipBundleGenerator.addSkip(skip);

					ThreadContext.put("testId", savedTestId);
					ThreadContext.put("runId", savedRunId);
					if (savedTcId != null && !savedTcId.isEmpty()) {
						ThreadContext.put("tcId", savedTcId);
					} else {
						ThreadContext.remove("tcId");
					}

					logger.debug("Skip bundle collected for test: {}", RunContext.testId(result));
				} catch (Exception e) {
					logger.warn("Failed to create skip bundle", e);
				}
			} catch (Exception e) {
				logger.error("Error in onTestSkipped", e);
			}

			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			ExtentManager.setTest(null);
		}
	}

	private String analyzeSkipReasonFromThrowable(Throwable throwable) {
		if (throwable == null) {
			return "unknown_skip";
		}

		String message = throwable.getMessage() != null ? throwable.getMessage().toLowerCase() : "";
		String exceptionType = throwable.getClass().getSimpleName().toLowerCase();

		if (isInfrastructureSeleniumError(throwable)) {
			return "env_not_ready";
		}

		if (isSeleniumFluentWaitTimeout(throwable)) {
			return "ui_wait_timeout";
		}

		if (message.contains("filenotfound") || message.contains("file not found")
				|| exceptionType.contains("filenotfound")) {
			return "data_not_found";
		}

		return "unknown_skip";
	}

	/**
	 * Name of the suite XML file stem (e.g. {@code Regression} from {@code testSuites/Regression.xml}) for DB/reporting.
	 * Avoids Surefire's synthetic suite name and per-{@code <test>} names like {@code LogIn}.
	 */
	private static String resolveSuiteNameForDb(ITestContext context) {
		try {
			XmlSuite xmlSuite = context.getSuite() != null ? context.getSuite().getXmlSuite() : null;
			if (xmlSuite != null) {
				String fileName = xmlSuite.getFileName();
				if (fileName != null && !fileName.trim().isEmpty()) {
					Path p = Paths.get(fileName.trim());
					String base = p.getFileName().toString();
					if (base.toLowerCase().endsWith(".xml")) {
						base = base.substring(0, base.length() - 4);
					}
					if (!base.trim().isEmpty()) {
						return base;
					}
				}
			}
		} catch (Exception ignored) {
			// fall through
		}
		String fromMaven = System.getProperty("testSuite");
		if (fromMaven != null && !fromMaven.trim().isEmpty()) {
			return fromMaven.trim();
		}
		if (context.getCurrentXmlTest() != null) {
			return context.getCurrentXmlTest().getName();
		}
		return context.getName();
	}

	@Override
	public void onStart(ITestContext context) {
		String suiteName = resolveSuiteNameForDb(context);
		String runId = RunContext.getRunId();

		ThreadContext.put("testId", "suite-init");
		ThreadContext.put("runId", runId);

		synchronized (Listeners.class) {
			if (activeSuites.incrementAndGet() == 1) {
				accumulatedPass.set(0);
				accumulatedFail.set(0);
				accumulatedSkip.set(0);
				if (!log4jAppenderAdded) {
					addLog4j2RollingFileAppender(runId);
					log4jAppenderAdded = true;
				}
			}
		}

		RunSummaryDb.insertRunStart(suiteName);

		logger.info("[SUITE START] run_id={} suiteName={}", runId, suiteName);
	}

	private void addLog4j2RollingFileAppender(String runId) {
		try {
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			Configuration config = context.getConfiguration();

			new File("./logs").mkdirs();

			String cleanRunId = runId.replaceAll("^run-", "");
			String logFilePath = "./logs/run-" + cleanRunId + "_test_log.log";

			PatternLayout layout = PatternLayout.newBuilder()
					.withPattern("%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - [%X{testId}] [%X{runId}] [%X{tcId}] %msg%n")
					.withConfiguration(config).build();

			FileAppender appender = FileAppender.newBuilder().setName("RunIdFile-" + cleanRunId).setLayout(layout)
					.withFileName(logFilePath).withAppend(false).setConfiguration(config).build();

			appender.start();
			config.addAppender(appender);
			config.getRootLogger().addAppender(appender, null, null);
			context.updateLoggers();

			logger.info("Log4j2 FileAppender configured: {}", logFilePath);
		} catch (Exception e) {
			logger.error("Failed to add log4j2 FileAppender", e);
		}
	}

	@Override
	public void onFinish(ITestContext context) {
		if (lastRunId != null) {
			ThreadContext.put("runId", lastRunId);
		}
		if (lastTestId != null) {
			ThreadContext.put("testId", lastTestId);
		}

		String runId = lastRunId != null ? lastRunId : RunContext.getRunId();

		logger.info("[SUITE END] run_id={}", runId);

		accumulatedPass.addAndGet(context.getPassedTests().size());
		accumulatedFail.addAndGet(context.getFailedTests().size());
		accumulatedSkip.addAndGet(context.getSkippedTests().size());

		int suitesRemaining;
		synchronized (Listeners.class) {
			suitesRemaining = activeSuites.decrementAndGet();
		}

		FailureBundleCollector.saveFailureBundle(runId);

		triage.generator.SkipBundleGenerator.saveSkipBundle(runId);

		utils.reports.ExtentReporterNG.flush();

		if (suitesRemaining == 0) {
			RunSummaryDb.finishRun(accumulatedPass.get(), accumulatedFail.get(), accumulatedSkip.get());
		}
	}

	private void logSkipDetailsToExtentReport(ExtentTest extentTest, ITestResult result, String skipReason) {
		if (extentTest == null)
			return;

		if (skipReason != null && !skipReason.isEmpty()) {
			extentTest.log(Status.SKIP, "[SKIP REASON] " + skipReason);
		}
	}

	private static String extentRelativeScreenshotPath(String absoluteScreenshotPath) {
		if (absoluteScreenshotPath == null || absoluteScreenshotPath.isEmpty()) {
			return absoluteScreenshotPath;
		}
		try {
			Path extentReportDir = Paths.get(System.getProperty("user.dir"), "reports", "extent_report")
					.toAbsolutePath().normalize();
			Path shot = Paths.get(absoluteScreenshotPath).toAbsolutePath().normalize();
			Path rel = extentReportDir.relativize(shot);
			if (rel.isAbsolute()) {
				return absoluteScreenshotPath;
			}
			return rel.toString().replace('\\', '/');
		} catch (Exception e) {
			return absoluteScreenshotPath;
		}
	}

}
