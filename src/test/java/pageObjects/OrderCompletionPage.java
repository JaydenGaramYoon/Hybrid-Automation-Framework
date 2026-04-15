package pageObjects;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;

public class OrderCompletionPage extends BasePage {

	public OrderCompletionPage(WebDriver driver) {
		super(driver);
	}

	@FindBy(css = "h1[class*='hero-primary']")
	private WebElement compeletionMsg;

	By messageLocator = By.cssSelector("h1[class*='hero-primary']");

	public String getMessage() {
		waitForWebElementToAppear(messageLocator);
		return compeletionMsg.getText();
	}
}
