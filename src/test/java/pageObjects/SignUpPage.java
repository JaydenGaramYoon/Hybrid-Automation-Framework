package pageObjects;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.ui.Select;

public class SignUpPage extends BasePage {

	private static final Logger logger = LogManager.getLogger(SignUpPage.class);

	public SignUpPage(WebDriver driver) {
		super(driver);
	}

	@FindBy(id = "firstName")
	private WebElement txtFirstName;

	@FindBy(id = "lastName")
	private WebElement txtLastName;

	@FindBy(id = "userEmail")
	private WebElement txtEmail;

	@FindBy(id = "userMobile")
	private WebElement txtPhoneNumber;

	@FindBy(css = "select[formcontrolname='occupation']")
	private WebElement drpOccupation;

	@FindBy(css = "input[value='Male']")
	private WebElement rdMale;

	@FindBy(css = "input[value='Female']")
	private WebElement rdFemale;

	@FindBy(id = "userPassword")
	private WebElement txtPassword;

	@FindBy(id = "confirmPassword")
	private WebElement txtConfirmPassword;

	@FindBy(css = "input[type='checkbox'][formcontrolname='required']")
	private WebElement chkAgeRequirement;

	@FindBy(id = "login")
	private WebElement btnSignUp;

	@FindBy(css = "#userEmail + .invalid-feedback div")
	private WebElement msgEmailError;

	@FindBy(css = "#userMobile + .invalid-feedback div")
	private List<WebElement> msgPhoneErrors;

	@FindBy(css = "#userPassword + .invalid-feedback div")
	private WebElement msgPasswordError;

	public void enterFirstName(String fName) {
		txtFirstName.sendKeys(fName.toUpperCase());
	}

	public void enterLastName(String lName) {
		txtLastName.sendKeys(lName.toUpperCase());
	}

	public void enterEmail(String email) {
		txtEmail.sendKeys(email);
	}

	public void enterPhone(String phone) {
		txtPhoneNumber.sendKeys(phone);
	}

	public void selectOccupation(String occupation) {
		if (occupation != null && !occupation.isEmpty()) {
			Select occupations = new Select(drpOccupation);
			occupations.selectByVisibleText(occupation);
		}
	}

	public void selectGender(String gender) {
		if (gender != null && !gender.isEmpty()) {
			if (gender.toLowerCase().equalsIgnoreCase("male")) {
				rdMale.click();
			} else if (gender.toLowerCase().equalsIgnoreCase("female")) {
				rdFemale.click();
			}
		}
	}

	public void enterPassword(String pwd) {
		txtPassword.sendKeys(pwd);
	}

	public void confirmPassword(String password) {
		txtConfirmPassword.sendKeys(password);
	}

	public void clickAgeCheckbox() {
		if (!chkAgeRequirement.isSelected()) {
			chkAgeRequirement.click();
		}
	}

	public SignUpConfirmationPage clickRegister() {
		logger.info("Clicking Sign-up button");
		btnSignUp.click();
		return new SignUpConfirmationPage(driver);
	}

	public SignUpConfirmationPage clickRegisterIfFormReady() {
		waitForFormToBeReady();
		List<String> issues = getFormBlockingIssues();
		if (!issues.isEmpty()) {
			throw new IllegalStateException("Sign-up form is not ready for submit: " + String.join(" | ", issues));
		}
		return clickRegister();
	}

	public void waitForFormToBeReady() {
		waitForWebElementAttributeContainsValue(txtFirstName, "class", "ng-valid");
		waitForWebElementAttributeContainsValue(txtLastName, "class", "ng-valid");
		waitForWebElementAttributeContainsValue(txtEmail, "class", "ng-valid");
		waitForWebElementAttributeContainsValue(txtPhoneNumber, "class", "ng-valid");
		waitForWebElementAttributeContainsValue(txtPassword, "class", "ng-valid");
		waitForWebElementAttributeContainsValue(txtConfirmPassword, "class", "ng-valid");
		waitForWebElementAttributeContainsValue(drpOccupation, "class", "ng-valid");
		waitForWebElementToBeClickable(btnSignUp);
	}

	public void enterUserInfo(String fName, String lName, String email, String phone, String occupation, String gender,
			String pwd) {
		logger.info("Entering user info");
		waitForWebElementToAppear(txtFirstName);
		enterFirstName(fName);
		enterLastName(lName);
		enterEmail(email);
		enterPhone(phone);
		selectOccupation(occupation);
		selectGender(gender);
		enterPassword(pwd);
		confirmPassword(pwd);
		clickAgeCheckbox();
	}

	public String getFieldErrorMessage(String focus) {
		try {
			switch (focus.toLowerCase()) {
			case "email":
				waitForWebElementToAppear(msgEmailError);
				return msgEmailError.getText();
			case "phone":
				waitForAllWebElementsToAppear(msgPhoneErrors);
				return getPhoneErrorMessages();
			case "password":
				waitForWebElementToAppear(msgPasswordError);
				return msgPasswordError.getText();
			default:
				throw new IllegalArgumentException("Invalid focus: " + focus);
			}
		} catch (Exception e) {
			return "";
		}
	}

	public String getPhoneErrorMessages() {
		return msgPhoneErrors.stream().filter(e -> e.isDisplayed() && !e.getText().trim().isEmpty())
				.map(e -> e.getText().trim()).collect(Collectors.joining("\n"));
	}

	private List<String> getFormBlockingIssues() {
		List<String> issues = new ArrayList<>();

		if (!isValidInput(txtFirstName)) {
			issues.add("first name is invalid");
		}
		if (!isValidInput(txtLastName)) {
			issues.add("last name is invalid");
		}
		if (!isValidInput(txtEmail)) {
			issues.add("email is invalid");
		}
		if (!isValidInput(txtPhoneNumber)) {
			issues.add("phone is invalid");
		}
		if (!isValidInput(txtPassword)) {
			issues.add("password is invalid");
		}
		if (!isValidInput(txtConfirmPassword)) {
			issues.add("confirm password is invalid");
		}
		if (!isValidInput(drpOccupation)) {
			issues.add("occupation is invalid");
		}
		if (!isGenderSelected()) {
			issues.add("gender is not selected");
		}
		if (!chkAgeRequirement.isSelected()) {
			issues.add("age checkbox is not selected");
		}
		if (!btnSignUp.isEnabled()) {
			issues.add("sign-up button is disabled");
		}

		return issues;
	}

	private boolean isValidInput(WebElement element) {
		String classes = element.getAttribute("class");
		return classes != null && classes.contains("ng-valid") && !classes.contains("ng-invalid");
	}

	private boolean isGenderSelected() {
		return rdMale.isSelected() || rdFemale.isSelected();
	}

	private String safeClass(WebElement element) {
		String classes = element.getAttribute("class");
		if (classes == null) {
			return "";
		}
		return classes.replaceAll("\\s+", " ").trim();
	}
}