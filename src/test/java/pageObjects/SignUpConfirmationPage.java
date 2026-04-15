package pageObjects;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;

public class SignUpConfirmationPage extends BasePage {
	private static final Logger logger = LogManager.getLogger(SignUpConfirmationPage.class);

	public SignUpConfirmationPage(WebDriver driver) {
		super(driver);
	}

	@FindBy(css = "button[class*='btn-primary']")
	WebElement btnLandingPage;

	public LandingPage goToLandingPage() {
		waitForWebElementToAppear(btnLandingPage);
		logger.info("Waiting for login button to appear");
		btnLandingPage.click();
		return new LandingPage(driver);
	}
}
