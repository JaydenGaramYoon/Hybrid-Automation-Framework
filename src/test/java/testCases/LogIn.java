package testCases;

import java.util.HashMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import testBase.BaseClass;
import utils.annotations.TestInfo;
import utils.data.model.LogInData;
import utils.data.provider.ExcelDataProvider;
import utils.listeners.Retry;
import utils.logging.LoggingUtils;

public class LogIn extends BaseClass {
	@Test(priority = 1, dataProvider = "testData", dataProviderClass = ExcelDataProvider.class, groups = {
			"Regression" }, retryAnalyzer = Retry.class)
	@TestInfo(file = "LogIn.xlsx", type = "valid")
	public void verifyLogInSuccess(HashMap<String, String> data) {
		LoggingUtils.logTestStart("verifyLogInSuccess");

		LogInData logInData = LogInData.fromHashMap(data);
		String email = logInData.getEmail();
		String pwd = logInData.getPassword();
		String focus = logInData.getFocus();
		String status = logInData.getStatus();
		String expectedMsg = logInData.getExpectedMsg();

		LoggingUtils.testInfo(focus, status, expectedMsg);
		LoggingUtils.testData(logInData);

		landingPage.login(email, pwd);
		LoggingUtils.logLoginAttempt(email, pwd);

		String actualMsg = landingPage.getToastMessage();
		LoggingUtils.actual(actualMsg);
		setGeneratedDataContext("LogIn", focus, status, expectedMsg, actualMsg, logInData.toPayloadMap());

		Assert.assertEquals(actualMsg, expectedMsg);
	}

	@Test(priority = 1, dataProvider = "testData", dataProviderClass = ExcelDataProvider.class, groups = {
			"Regression" }, retryAnalyzer = Retry.class)
	@TestInfo(file = "LogIn.xlsx", type = "invalid")
	public void verifyLogInValidationMessage(HashMap<String, String> data) {
		LoggingUtils.logTestStart("verifyLogInValidationMessage");
		LogInData logInData = LogInData.fromHashMap(data);
		String focus = logInData.getFocus();
		String status = logInData.getStatus();

		if ("email".equals(focus))
			logInData.setEmail(data.get("invalid_email"));
		else if ("password".equals(focus))
			logInData.setPassword(data.get("invalid_password"));

		// Wrong-password scenarios need a real account email so the app returns the invalid-credentials toast.
		if ("password".equals(focus)) {
			String e = logInData.getEmail();
			if (e == null || e.isBlank()) {
				logInData.setEmail("testUser1@example.com");
			}
		}

		String email = logInData.getEmail();
		String pwd = logInData.getPassword();
		String expectedMsg = logInData.getExpectedMsg();

		LoggingUtils.testInfo(focus, status, expectedMsg);
		LoggingUtils.testData(logInData);

		landingPage.login(email, pwd);
		LoggingUtils.logLoginAttempt(email, pwd);

		String actualMsg = landingPage.getToastMessage();
		LoggingUtils.actual(actualMsg);
		setGeneratedDataContext("LogIn", focus, status, expectedMsg, actualMsg, logInData.toPayloadMap());

		Assert.assertEquals(actualMsg, expectedMsg);
	}

	@org.testng.annotations.AfterMethod(alwaysRun = true)
	public void cleanupAfterLogInTest() {
		LoggingUtils.info("[CLEANUP] cleanupAfterLogInTest - closing driver for parameter isolation");
		if (driver != null) {
			try {
				driver.quit();
				LoggingUtils.info("[CLEANUP] Driver quit successfully");
			} catch (Exception e) {
				LoggingUtils.info("[CLEANUP] Driver quit failed: " + e.getMessage());
			}
		}
	}

	@BeforeClass(alwaysRun = true)
	public void beforeClass() {
		LoggingUtils.logClassStart("LogIn Test Class");
	}
}
