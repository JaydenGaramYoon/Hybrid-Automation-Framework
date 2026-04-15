package testBase;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Platform;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Listeners;
import org.testng.annotations.Parameters;

import pageObjects.LandingPage;
import triage.history.TestRunHistoryListener;
import utils.config.ConfigUtils;
import utils.db.DBProvider;
import utils.run.RunContext;

/**
 * WebDriver setup (local/Grid), {@link ConfigUtils}, and shared hooks.
 * Listeners: {@link utils.listeners.Listeners}, {@link TestRunHistoryListener}.
 */
@Listeners({ utils.listeners.Listeners.class, TestRunHistoryListener.class })
public class BaseClass {

	public WebDriver driver = null;
	public LandingPage landingPage = null;
	private static final Logger logger = LogManager.getLogger(BaseClass.class);
	public static Properties prop = ConfigUtils.loadConfig();
	private static final DBProvider DB_PROVIDER = new DBProvider();
	private static final ThreadLocal<GeneratedDataContext> GENERATED_DATA_CONTEXT = new ThreadLocal<>();
	String gridUrl = prop.getProperty("grid_url");
	String baseUrl = prop.getProperty("base_url");
	public String executionEnv = prop.getProperty("execution_env");
	FileInputStream streamedFile;
	public String browserName;

	private static class GeneratedDataContext {
		String testClass;
		String focus;
		String status;
		String expected;
		String actual;
		Map<String, String> payload;
	}

	/**
	 * Captures row payload for triage when a test fails (see
	 * {@link #getGeneratedDataPayload()}).
	 */
	protected void setGeneratedDataContext(String testClass, String focus, String status, String expected,
			String actual, Map<String, String> payload) {
		GeneratedDataContext context = new GeneratedDataContext();
		context.testClass = testClass;
		context.focus = focus;
		context.status = status;
		context.expected = expected;
		context.actual = actual;
		context.payload = payload;
		GENERATED_DATA_CONTEXT.set(context);
	}

	/** @return Excel row map for the failing test, or {@code null} */
	public static Map<String, String> getGeneratedDataPayload() {
		GeneratedDataContext context = GENERATED_DATA_CONTEXT.get();
		return context != null ? context.payload : null;
	}

	/**
	 * @param data {@code data[0]} must be a {@code HashMap<String, String>} row
	 * @throws IllegalArgumentException if the row is missing or malformed
	 */
	protected HashMap<String, String> extractRowData(Object[] data, String testName) {
		if (data == null || data.length == 0 || data[0] == null) {
			throw new IllegalArgumentException("Test data row is missing for " + testName + " test.");
		}

		if (!(data[0] instanceof HashMap<?, ?>)) {
			throw new IllegalArgumentException(
					"Expected data[0] to be HashMap but got: " + data[0].getClass().getName());
		}

		HashMap<?, ?> rawRow = (HashMap<?, ?>) data[0];
		HashMap<String, String> typedRow = new HashMap<>();

		for (Map.Entry<?, ?> entry : rawRow.entrySet()) {
			if (!(entry.getKey() instanceof String)) {
				throw new IllegalArgumentException("Expected String key in test data map but got: " + entry.getKey());
			}

			String key = (String) entry.getKey();
			String value = entry.getValue() == null ? null : String.valueOf(entry.getValue());
			typedRow.put(key, value);
		}

		return typedRow;
	}

	/**
	 * Local browser or RemoteWebDriver to {@code grid_url} depending on
	 * {@code execution_env}.
	 */
	@Parameters({ "os", "browser" })
	public WebDriver setUpEnv(String os, String browser) throws IOException, URISyntaxException {
		if (os == null || os.isEmpty()) {
			os = System.getProperty("os") != null ? System.getProperty("os") : prop.getProperty("os");
		}
		if (executionEnv.equals("remote")) {
			logger.info("Running in remote mode");
			DesiredCapabilities capabilities = new DesiredCapabilities();
			if (os.equalsIgnoreCase("windows")) {
				capabilities.setPlatform(Platform.WIN11);
			} else if (os.equalsIgnoreCase("linux")) {
				capabilities.setPlatform(Platform.LINUX);
			} else if (os.equalsIgnoreCase("mac")) {
				capabilities.setPlatform(Platform.MAC);
			} else {
				logger.warn("No matching OS for remote grid: {}; platform not set on capabilities", os);
			}
			if (browser == null || browser.isEmpty()) {
				browser = System.getProperty("browser") != null ? System.getProperty("browser")
						: prop.getProperty("browser");
			}
			logger.info(String.format("[ENV] OS: %s | Execution: %s | Browser: %s", os, executionEnv, browser));

			if (browser.contains("chrome")) {
				logger.info("Launching Chrome browser");
				capabilities.setBrowserName("chrome");
				ChromeOptions options = new ChromeOptions();
				options.addArguments("--window-size=1920,1080");
				options.addArguments("--start-maximized");

				if (browser.contains("headless")) {
					options.addArguments("--headless=new");
					options.addArguments("--disable-gpu");
				}
				options.addArguments("--no-sandbox");
				options.addArguments("--disable-dev-shm-usage");
				options.merge(capabilities);
				driver = new RemoteWebDriver(new URI(gridUrl).toURL(), options);
				driver.manage().window().setSize(new Dimension(1920, 1080));
			} else if (browser.equalsIgnoreCase("firefox")) {
				logger.info("Launching Firefox browser");
				capabilities.setBrowserName("firefox");
				FirefoxOptions options = new FirefoxOptions();
				options.addArguments("-width", "1920", "-height", "1080");
				options.merge(capabilities);
				driver = new RemoteWebDriver(new URI(gridUrl).toURL(), options);
				driver.manage().window().setSize(new Dimension(1920, 1080));
			} else if (browser.equalsIgnoreCase("edge")) {
				logger.info("Launching Edge browser");
				capabilities.setBrowserName("edge");
				EdgeOptions options = new EdgeOptions();
				options.addArguments("--window-size=1920,1080");
				options.addArguments("--start-maximized");
				if (browser.contains("headless")) {
					options.addArguments("--headless=new");
					options.addArguments("--disable-gpu");
				}
				options.addArguments("--no-sandbox");
				options.addArguments("--disable-dev-shm-usage");
				options.merge(capabilities);
				driver = new RemoteWebDriver(new URI(gridUrl).toURL(), options);
				driver.manage().window().setSize(new Dimension(1920, 1080));
			}
		}
		if (executionEnv.equals("local")) {
			logger.info("Running in local mode");
			logger.info("Initializing WebDriver");
			browser = System.getProperty("browser") != null ? System.getProperty("browser")
					: prop.getProperty("browser");

			logger.info("Selected browser: " + browser);

			if (browser.contains("chrome")) {
				logger.info("Launching Chrome browser");
				ChromeOptions options = new ChromeOptions();
				if (browser.contains("headless")) {
					options.addArguments("--headless=new");
					options.addArguments("--window-size=1920,1080");
					options.addArguments("--start-maximized");
					options.addArguments("--disable-gpu");
				}
				driver = new ChromeDriver(options);
				if (browser.contains("headless")) {
					((ChromeDriver) driver).executeCdpCommand("Emulation.setDeviceMetricsOverride",
							Map.of("width", 1920, "height", 1080, "deviceScaleFactor", 1, "mobile", false));
				} else {
					driver.manage().window().maximize();
				}
			} else if (browser.equalsIgnoreCase("firefox")) {
				logger.info("Launching Firefox browser");
				driver = new FirefoxDriver();
			} else if (browser.equalsIgnoreCase("edge")) {
				logger.info("Launching Edge browser");
				driver = new EdgeDriver();
			}
			this.browserName = browser;
			logger.info("WebDriver initialized successfully");
			logger.info(String.format("[ENV] OS: %s | Execution: %s | Browser: %s", os, executionEnv, browser));
			driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10));
			driver.manage().window().maximize();
			return driver;
		}
		return driver;
	}

	/**
	 * Writes {@code screenshots/run-{runId}_{Class}_{method}_{param tags}_{tc_id}.png};
	 * path includes {@link RunContext#getParameterDescription()} /
	 * {@link RunContext#getInvalidReason()} / {@link RunContext#getTcId()} when set.
	 */
	public String takeScreenshot(ITestResult result, WebDriver driver) throws IOException {
		logger.info("Taking screenshot");
		File screenshotDir = new File(System.getProperty("user.dir") + "//screenshots");
		if (!screenshotDir.exists()) {
			screenshotDir.mkdirs();
		}

		String className = result.getTestClass().getRealClass().getSimpleName();
		String methodName = result.getMethod().getMethodName();

		String paramDesc = utils.run.RunContext.getParameterDescription();
		String invalidReason = utils.run.RunContext.getInvalidReason();

		StringBuilder paramPart = new StringBuilder();
		if (paramDesc != null && !paramDesc.isEmpty()) {
			String cleanParamDesc = paramDesc.replaceAll("[\\[\\]]", "");
			paramPart.append("_").append(cleanParamDesc);
		}
		if (invalidReason != null && !invalidReason.isEmpty()) {
			String cleanInvalidReason = invalidReason.replaceAll("[\\[\\]]", "");
			paramPart.append("_").append(cleanInvalidReason);
		}

		String tcSeg = safeTcIdFileSegment(utils.run.RunContext.getTcId());
		if (!tcSeg.isEmpty()) {
			paramPart.append("_").append(tcSeg);
		}

		String cleanRunId = utils.run.RunContext.getRunId().replaceAll("^run-", "");
		String fileName = "run-" + cleanRunId + "_" + className + "_" + methodName + paramPart.toString() + ".png";
		String filePath = screenshotDir.getAbsolutePath() + "//" + fileName;

		TakesScreenshot ts = (TakesScreenshot) driver;
		File source = ts.getScreenshotAs(OutputType.FILE);
		File file = new File(filePath);
		FileUtils.copyFile(source, file);
		return filePath;
	}

	private static String safeTcIdFileSegment(String tcId) {
		if (tcId == null || tcId.isBlank()) {
			return "";
		}
		return tcId.trim().replaceAll("[^A-Za-z0-9_-]+", "_");
	}

	@Parameters({ "os", "browser" })
	@BeforeMethod(alwaysRun = true)
	public LandingPage launchApplication(String os, String browser, Method method, ITestResult result)
			throws IOException, URISyntaxException, Throwable {
		String methodName = result.getMethod().getMethodName();

		ThreadContext.put("testName", methodName);
		ThreadContext.put("runId", RunContext.getRunId());
		ThreadContext.put("testId", RunContext.testId(result.getMethod().getRealClass(), methodName));
		RunContext.refreshTcIdFromTestParameters(result);
		String tc = RunContext.getTcId();
		if (tc != null && !tc.isEmpty()) {
			ThreadContext.put("tcId", tc);
		} else {
			ThreadContext.remove("tcId");
		}

		String maxRetriesStr = ConfigUtils.get("retry_count");

		int maxRetries;
		try {
			maxRetries = Integer.parseInt(maxRetriesStr);
		} catch (NumberFormatException e) {
			maxRetries = 3;
		}

		int retryCount = 0;
		Throwable lastException = null;

		while (retryCount <= maxRetries) {
			try {
				driver = setUpEnv(os, browser);

				driver.get(baseUrl);

				landingPage = new LandingPage(driver);

				return landingPage;

			} catch (Throwable e) {
				lastException = e;
				String exceptionType = e.getClass().getSimpleName();
				boolean infraError = isInfrastructureSeleniumError(e);

				if (!infraError) {
					RunContext.setSkipReason("setup_failed");
					throw new SkipException("[BEFORE_METHOD_NON_RETRYABLE] " + exceptionType + ": " + e.getMessage(),
							e);
				}

				retryCount++;
				if (retryCount <= maxRetries) {
					long waitMillis = retryCount * 1000;

					try {
						Thread.sleep(waitMillis);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						RunContext.setSkipReason("env_not_ready");
						throw new SkipException("[BEFORE_METHOD_INTERRUPTED] Interrupted during backoff", e);
					}
				} else {
					retryCount++;
				}
			}
		}

		RunContext.setSkipReason("env_not_ready");

		throw new SkipException("[BEFORE_METHOD_EXHAUSTED] Environment not ready after " + (maxRetries + 1)
				+ " retries: " + lastException.getClass().getSimpleName() + " - " + lastException.getMessage());
	}

	protected static boolean isSeleniumFluentWaitTimeout(Throwable e) {
		if (e == null || !"TimeoutException".equals(e.getClass().getSimpleName())) {
			return false;
		}
		String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
		return msg.contains("expected condition failed") || msg.contains("waiting for visibility")
				|| msg.contains("waiting for presence") || msg.contains("defaultelementlocator");
	}

	protected static boolean isInfrastructureSeleniumError(Throwable e) {
		if (e == null)
			return false;

		String exceptionType = e.getClass().getSimpleName();
		String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

		if ("TimeoutException".equals(exceptionType) && isSeleniumFluentWaitTimeout(e)) {
			return false;
		}

		if (exceptionType.contains("SessionNotCreatedException") || exceptionType.contains("ConnectException")
				|| exceptionType.contains("TimeoutException") || exceptionType.contains("WebDriverException")) {
			return true;
		}

		if (message.contains("could not start a new session") || message.contains("new session")
				|| message.contains("connection refused") || message.contains("connection timeout")
				|| message.contains("unable to connect") || message.contains("econnrefused")
				|| message.contains("socket hang up")) {
			return true;
		}

		if (e.getCause() != null) {
			return isInfrastructureSeleniumError(e.getCause());
		}

		return false;
	}

	@AfterMethod(alwaysRun = true)
	public void tearDown(ITestResult result, Method method) {

		switch (result.getStatus()) {
		case ITestResult.SUCCESS:
			logger.info("============ [PASS] TEST PASSED: {} ============", method.getName());
			break;

		case ITestResult.FAILURE:
			logger.error("============ [FAIL] TEST FAILED: {} ============", method.getName());
			logger.error("Reason: ", result.getThrowable());
			break;

		case ITestResult.SKIP:
			logger.warn("============ [SKIP] TEST SKIPPED: {} ============", method.getName());
			break;
		}

		GeneratedDataContext context = GENERATED_DATA_CONTEXT.get();
		if (context != null) {
			String actual = context.actual;
			if ((actual == null || actual.isEmpty()) && result.getThrowable() != null) {
				actual = result.getThrowable().getMessage();
			}

			try {
				DB_PROVIDER.insertGeneratedTestData(RunContext.getRunId(), context.testClass, method.getName(),
						context.focus, context.status, context.expected, actual, context.payload);
			} catch (SQLException e) {
				logger.warn("Failed to persist generated data for {}", method.getName(), e);
			}
		}

		logger.info("Closing browser");

		if (driver != null) {
			try {
				driver.quit();
			} catch (Exception e) {
				logger.warn("Error quitting driver: {}", e.getMessage());
			}
		} else {
			logger.warn("Driver is null in tearDown - skipping driver.quit()");
		}
		GENERATED_DATA_CONTEXT.remove();

		int invocationCount = result.getMethod().getCurrentInvocationCount();
		int attemptBefore = RunContext.getAttempt();

		RunContext.setAttempt(0);

		int attemptAfter = RunContext.getAttempt();

		ThreadContext.clearAll();
	}
}
