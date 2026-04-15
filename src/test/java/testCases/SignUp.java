package testCases;

import java.io.IOException;
import java.util.HashMap;

import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import pageObjects.SignUpConfirmationPage;
import pageObjects.SignUpPage;
import testBase.BaseClass;
import utils.annotations.TestInfo;
import utils.data.model.SignUpData;
import utils.data.provider.ExcelDataProvider;
import utils.listeners.Retry;
import utils.logging.LoggingUtils;

public class SignUp extends BaseClass {
	private SignUpPage signUpPage;
	SignUpConfirmationPage signUpConfirmationPage;

	@Test(priority = 1, dataProvider = "testData", dataProviderClass = ExcelDataProvider.class, groups = { "Regression"}, retryAnalyzer = Retry.class)
	@TestInfo(file = "SignUp.xlsx", type = "valid")
	public void verifyUserSignUpSuccess(HashMap<String, String> data) throws IOException {
		SignUpData signUpData = SignUpData.fromHashMap(data);

		String focus = signUpData.getFocus();
		String status = signUpData.getStatus();
		String expectedMsg = signUpData.getExpectedMsg();
		String firstName = signUpData.getFName();
		String lastName = signUpData.getLName();
		String email = signUpData.getEmail();
		String phone = signUpData.getPhone();
		String occupation = signUpData.getOccupation();
		String gender = signUpData.getGender();
		String password = signUpData.getPassword();

		LoggingUtils.testInfo(signUpData);
		LoggingUtils.testData(signUpData);

		signUpPage.enterUserInfo(firstName, lastName, email, phone, occupation, gender, password);

		signUpConfirmationPage = signUpPage.clickRegisterIfFormReady();
		LoggingUtils.info("Clicked Sign-up button");

		String actualMsg = signUpConfirmationPage.getToastMessage();
		LoggingUtils.actual(actualMsg);
		setGeneratedDataContext("SignUp", focus, status, expectedMsg, actualMsg,
				signUpData.toPayloadMap());

		Assert.assertEquals(actualMsg, expectedMsg);
	}

	@Test(priority = 2, dataProvider = "testData", dataProviderClass = ExcelDataProvider.class, groups = { "Regression"}, retryAnalyzer = Retry.class)
	@TestInfo(file = "SignUp.xlsx", type = "invalid")
	public void verifySignUpValidationMessage(HashMap<String, String> data) throws IOException {
		SignUpData signUpData = SignUpData.fromHashMap(data);
		String focus = signUpData.getFocus();
		String status = signUpData.getStatus();
		String expectedMsg = signUpData.getExpectedMsg();
		String firstName = signUpData.getFName();
		String lastName = signUpData.getLName();
		String email = signUpData.getEmail();
		String phone = signUpData.getPhone();
		String occupation = signUpData.getOccupation();
		String gender = signUpData.getGender();
		String password = signUpData.getPassword();

		if ("email".equals(focus)) {
			email = data.get("invalid_email");
			signUpData.setEmail(email);
		} else if ("phone".equals(focus)) {
			phone = data.get("invalid_phone_number");
			signUpData.setPhone(phone);
		} else if ("password".equals(focus)) {
			password = data.get("invalid_password");
			signUpData.setPassword(password);
		}

		LoggingUtils.testInfo(signUpData);
		LoggingUtils.testData(signUpData);

		signUpPage.enterUserInfo(firstName, lastName, email, phone, occupation, gender, password);
		
		signUpConfirmationPage = signUpPage.clickRegister();
		LoggingUtils.info("Clicked Sign-up button");

		String actualMsg = "";

		if (expectedMsg != null && !expectedMsg.isEmpty()) {
			actualMsg = signUpPage.getFieldErrorMessage(focus);
			LoggingUtils.actual(actualMsg);
			Assert.assertEquals(actualMsg, expectedMsg);
		}

		setGeneratedDataContext("SignUp", focus, status, expectedMsg, actualMsg,
				signUpData.toPayloadMap());
	}

	@BeforeMethod(alwaysRun = true)
	public void moveToSignUpPage() {
		LoggingUtils.logBeforeMethodStart("moveToSignUpPage");
		
		if (landingPage == null || driver == null) {
			LoggingUtils.info("moveToSignUpPage: landingPage or driver is null - launchApplication failed. Skipping test.");
			throw new SkipException("[@BeforeMethod moveToSignUpPage] launchApplication @BeforeMethod failed - driver/landingPage are null");
		}
		
		signUpPage = landingPage.goToSignUpPage();
		LoggingUtils.info("Moved to Sign-up Page");
	}

	@org.testng.annotations.AfterMethod(alwaysRun = true)
	public void cleanupAfterSignUpTest() {
		LoggingUtils.info("[CLEANUP] cleanupAfterSignUpTest - closing driver for parameter isolation");
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
		LoggingUtils.logClassStart("SignUp Test Class");
	}
}
