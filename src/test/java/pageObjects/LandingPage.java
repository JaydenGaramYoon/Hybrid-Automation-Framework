package pageObjects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;

public class LandingPage extends BasePage {
	private static final Logger logger = LogManager.getLogger(LandingPage.class);

	public LandingPage(WebDriver driver) {
		super(driver);
	}

	@FindBy(id = "userEmail")
	private WebElement emailInput;

	@FindBy(id = "userPassword")
	private WebElement passwordInput;

	@FindBy(id = "login")
	private WebElement signInBtn;

	@FindBy(css = "p[class*='login-wrapper']")
	private WebElement goToSignUpPageBtn;

	public ProductCataloguePage login(String email, String pwd) {
		logger.info("Logging in");
		emailInput.sendKeys(email);
		passwordInput.sendKeys(pwd);
		waitForWebElementToBeClickable(signInBtn);
		signInBtn.click();
		return new ProductCataloguePage(driver);
	}

	public SignUpPage goToSignUpPage() {
		logger.info("Clicking submit button");
		goToSignUpPageBtn.click();
		return new SignUpPage(driver);
	}
}
