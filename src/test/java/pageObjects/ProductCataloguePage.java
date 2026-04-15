package pageObjects;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.FindBy;

/**
 * Post-login product grid. {@link #addProductToCart(String)} waits for spinner and toast transitions.
 */
public class ProductCataloguePage extends BasePage {
	private static final Logger logger = LogManager.getLogger(ProductCataloguePage.class);

	public ProductCataloguePage(WebDriver driver) {
		super(driver);
	}

	@FindBy(css = "#products .card")
	private List<WebElement> productCards;

	@FindBy(css = ".ng-animating")
	private WebElement spinner;

	By productsLocator = By.cssSelector("#products .card");
	By addToCartBtn = By.cssSelector(".card-body button:last-of-type");
	By toastMessage = By.cssSelector("#toast-container");

	public List<WebElement> getProductList() {
		waitForWebElementToAppear(productsLocator);
		return productCards;
	}

	public WebElement getProductByName(String proudctName) {
		WebElement product = getProductList().stream()
				.filter(prod -> prod.findElement(By.cssSelector("b")).getText().equals(proudctName)).findFirst()
				.orElse(null);
		return product;
	}

	public void addProductToCart(String proudctName) {
		getProductByName(proudctName).findElement(addToCartBtn).click();
		try {
			waitForWebElementToAppear(toastMessage);
		} catch (TimeoutException e) {
			logger.debug("Toast message did not appear");
		}
		waitForWebElementToDisappear(toastMessage);
		try {
			waitForWebElementToAppear(spinner);
		} catch (TimeoutException e) {
			logger.debug("Spinner did not appear");
		}
		waitForWebElementToDisappear(spinner);
	}
}
