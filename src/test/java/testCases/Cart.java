package testCases;

import java.util.HashMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import pageObjects.CartPage;
import pageObjects.ProductCataloguePage;
import testBase.BaseClass;
import utils.annotations.TestInfo;
import utils.data.model.CartData;
import utils.data.provider.ExcelDataProvider;
import utils.listeners.Retry;
import utils.logging.LoggingUtils;

public class Cart extends BaseClass {
	private static final String TEST_CLASS_NAME = "Cart";
	private ProductCataloguePage productCataloguePage;

	@Test(priority = 1, dataProvider = "testData", dataProviderClass = ExcelDataProvider.class, groups = {
			"Regression" }, retryAnalyzer = Retry.class)
	@TestInfo(file = "Cart.xlsx", type = "valid")
	public void verifyProductPresentInCart(HashMap<String, String> data) {
		LoggingUtils.logTestStart("verifyProductPresentInCart");

		CartData cartData = CartData.fromHashMap(data);
		String product = cartData.getProduct();
		String expectedMessage = cartData.getExpected();
		if (expectedMessage == null || expectedMessage.isBlank()) {
			expectedMessage = product + " should be present in the cart";
		}

		LoggingUtils.testInfo(cartData.getFocus(), cartData.getStatus(), expectedMessage);
		LoggingUtils.testData(cartData, product);

		CartPage cartPage = productCataloguePage.goToCartPage();
		LoggingUtils.info("Moved to cart page");

		boolean isProductPresent = cartPage.hasProductInCart(product);
		String actualMessage = product + " is found: " + isProductPresent;
		LoggingUtils.actual(actualMessage);
		setCartGeneratedDataContext(cartData, expectedMessage, actualMessage);

		Assert.assertTrue(isProductPresent, "Product [" + product + "] is not found in the cart");
	}

	@Test(priority = 2, dataProvider = "testData", dataProviderClass = ExcelDataProvider.class, groups = {
			"Regression" }, retryAnalyzer = Retry.class)
	@TestInfo(file = "Cart.xlsx", type = "invalid")
	public void verifyProductNotPresentInCart(HashMap<String, String> data) {
		LoggingUtils.logTestStart("verifyProductNotPresentInCart");

		CartData cartData = CartData.fromHashMap(data);
		String invalidProduct = cartData.getInvalidProduct();
		String expectedMessage = cartData.getExpected();
		if (expectedMessage == null || expectedMessage.isBlank()) {
			expectedMessage = invalidProduct + " should not be present in the cart";
		}

		LoggingUtils.testInfo(cartData.getFocus(), cartData.getStatus(), expectedMessage);
		LoggingUtils.testData(cartData, invalidProduct);

		CartPage cartPage = productCataloguePage.goToCartPage();
		LoggingUtils.info("Moved to cart page");
		boolean isProductPresent = cartPage.hasProductInCart(invalidProduct);

		String actualMessage = invalidProduct + " is found: " + isProductPresent;
		LoggingUtils.actual(actualMessage);
		setCartGeneratedDataContext(cartData, expectedMessage, actualMessage);
		Assert.assertFalse(isProductPresent, "Product [" + invalidProduct + "] is found in the cart");
	}

	@BeforeMethod(alwaysRun = true)
	public void addItemToCart(Object[] data) {

		HashMap<String, String> rowData = extractRowData(data, "cart");
		CartData cartData = CartData.fromHashMap(rowData);

		String email = cartData.getEmail();
		String pwd = cartData.getPassword();
		String product = cartData.getProduct();

		LoggingUtils.logBeforeMethodStart("addItemToCart");

		productCataloguePage = landingPage.login(email, pwd);
		LoggingUtils.logLoginAttempt(email, pwd);

		productCataloguePage.addProductToCart(product);
		LoggingUtils.info(product + " is added in to the cart");
	}

	@BeforeClass(alwaysRun = true)
	public void beforeClass() {
		LoggingUtils.logClassStart("Cart Test Class");
	}

	private void setCartGeneratedDataContext(CartData cartData, String expectedMessage, String actualMessage) {
		setGeneratedDataContext(TEST_CLASS_NAME, cartData.getFocus(), cartData.getStatus(), expectedMessage,
				actualMessage, cartData.toPayloadMap());
	}
}
