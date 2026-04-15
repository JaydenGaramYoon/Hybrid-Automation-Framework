package pageObjects;

import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;

public class CartPage extends BasePage {
	public CartPage(WebDriver driver) {
		super(driver);
	}

	@FindBy(css = ".cartSection h3")
	private List<WebElement> cartProducts;

	@FindBy(css = ".totalRow button")
	private WebElement btnCheckOut;

	By cartProductsLocator = By.cssSelector(".cartSection h3");

	public List<WebElement> getCartProducts() {
		waitForWebElementToAppear(cartProductsLocator);
		return cartProducts;
	}

	public boolean hasProductInCart(String productName) {
		waitForWebElementToAppear(cartProductsLocator);
		boolean match = cartProducts.stream()
				.anyMatch(cartProduct -> cartProduct.getText().equalsIgnoreCase(productName));
		return match;
	}

	public CheckOutPage goToCheckout() {
		waitForWebElementToAppear(btnCheckOut);
		btnCheckOut.click();
		return new CheckOutPage(driver);
	}
}
