package pageObjects;

import java.time.Duration;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import utils.config.ConfigUtils;

/**
 * Shared explicit waits, navigation, and toast/spinner helpers for concrete page objects.
 */
public class BasePage {
	WebDriver driver;
	private static final Logger logger = LogManager.getLogger(BasePage.class);
	private static final int EXPLICIT_TIMEOUT = ConfigUtils.getInt("explicit.timeout");

	public BasePage(WebDriver driver) {

		this.driver = driver;
		PageFactory.initElements(driver, this);

	}

	@FindBy(css = "button[routerlink='/dashboard/']")
	private WebElement btnHome;

	@FindBy(css = "button[routerlink='/dashboard/cart']")
	private WebElement btnCart;

	@FindBy(xpath = "//button[normalize-space()='Sign Out']")
	protected WebElement btnSignOut;

	@FindBy(css = "div[class*='ng-trigger']")
	protected WebElement toast;

	By spinnerLocator = By.cssSelector("ng.-animating");
	By toastLocator = By.cssSelector("div[class*='ng-trigger']");

	public void waitForWebElementToAppear(By locator) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
	}

	public void waitForWebElementToAppear(WebElement element) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.visibilityOf(element));
	}

	public void waitForAllWebElementsToAppear(List<WebElement> elements) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.visibilityOfAllElements(elements));
	}

	public void waitForWebElementToBeVisible(WebElement element) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.visibilityOf(element));
	}

	public void waitForWebElementToBeInvisible(WebElement element) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.invisibilityOf(element));
	}

	public void waitForWebElementToDisappear(WebElement element) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.invisibilityOf(element));
	}

	public void waitForWebElementToDisappear(By locator) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.invisibilityOfElementLocated(locator));
	}

	public void waitForWebElementToBeClickable(WebElement element) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.elementToBeClickable(element));
	}

	public void waitForWebElementAttributeContainsValue(WebElement element, String attribute, String value) {
		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));
		wait.until(ExpectedConditions.attributeContains(element, attribute, value));
	}

	public CartPage goToCartPage() {

		if (isElementPresent(spinnerLocator)) {
			waitForWebElementToDisappear(spinnerLocator);
		} else {
		}

		waitForWebElementToBeClickable(btnCart);
		btnCart.click();

		return new CartPage(driver);
	}

	public boolean isElementPresent(By locator) {
		try {
			waitForWebElementToAppear(locator);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public void waitToastToBeDisappear() {
		try {
			waitForWebElementToDisappear(toast);
		} catch (TimeoutException e) {
			logger.debug("Toast message did not disappear within timeout");
		}
	}

	public String getToastMessage() {
		waitForWebElementToAppear(toastLocator);
		return toast.getText();
	}

	public String getToastMessage(String expectedMessage) {

		WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_TIMEOUT));

		wait.until(ExpectedConditions.textToBePresentInElementLocated(toastLocator, expectedMessage));

		return driver.findElement(toastLocator).getText();
	}
}
