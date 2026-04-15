package pageObjects;

import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.FindBy;

public class CheckOutPage extends BasePage {

	public CheckOutPage(WebDriver driver) {
		super(driver);
	}

	@FindBy(css = "input[type*='text']")
	private List<WebElement> txtInfoFields;

	@FindBy(css = "[placeholder='Select Country']")
	private WebElement txtCountry;

	@FindBy(css = "button[class*='inserted']")
	private WebElement insertCountryBtn;

	@FindBy(css = "a[class*='submit']")
	private WebElement btnPlaceOrder;

	By infoInputFieldsLocator = By.cssSelector("input[type*='text']");
	By selectCountryLocator = By.cssSelector("button[class*='inserted']");
	By placeOrderBtnLocator = By.cssSelector("a[class*='submit']");

	public void enterCreditCardNumber(String creditCardNumber) {
		txtInfoFields.get(0).clear();
		txtInfoFields.get(0).sendKeys(creditCardNumber);
	}

	public void enterCvvCode(String cvv) {
		txtInfoFields.get(1).sendKeys(cvv);
	}

	public void enterNameOnCard(String nameOnCard) {
		txtInfoFields.get(2).sendKeys(nameOnCard.toUpperCase());
	}

	public void enterCoupon(String coupon) {
		txtInfoFields.get(3).sendKeys(coupon);
	}

	public void enterEmail(String email) {
		txtInfoFields.get(4).clear();
		txtInfoFields.get(4).sendKeys(email);
	}

	public void selectCountry(String countryName) {
		Actions a = new Actions(driver);
		a.sendKeys(txtCountry, countryName.toUpperCase()).build().perform();
		waitForWebElementToAppear(selectCountryLocator);
		insertCountryBtn.click();
	}

	public void enterOrderInformation(String creditCardNumber, String cvv, String nameOnCard, String coupon,
			String email, String countryName) {

		enterCreditCardNumber(creditCardNumber);
		enterCvvCode(cvv);
		enterNameOnCard(nameOnCard);
		enterCoupon(coupon);
		enterEmail(email);
		selectCountry(countryName);
	}

	public OrderCompletionPage checkOut() {
		btnPlaceOrder.click();
		return new OrderCompletionPage(driver);
	}
}
