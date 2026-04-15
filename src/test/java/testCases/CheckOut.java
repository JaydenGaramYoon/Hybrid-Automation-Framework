package testCases;

import java.util.HashMap;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import pageObjects.CartPage;
import pageObjects.CheckOutPage;
import pageObjects.OrderCompletionPage;
import pageObjects.ProductCataloguePage;
import testBase.BaseClass;
import utils.annotations.TestInfo;
import utils.data.model.CheckOutData;
import utils.data.provider.ExcelDataProvider;
import utils.listeners.Retry;
import utils.logging.LoggingUtils;

public class CheckOut extends BaseClass {
	ProductCataloguePage productCataloguePage;
	CartPage cartPage;
	CheckOutPage checkOutPage;

	private String email;
	private String pwd;

	@Test(priority = 1, dataProvider = "testData", dataProviderClass = ExcelDataProvider.class, groups = {
			"Regression" }, retryAnalyzer = Retry.class)
	@TestInfo(file = "CheckOut.xlsx", type = "valid")
	public void verifyCheckOutSuccess(HashMap<String, String> data) {
		LoggingUtils.logTestStart("verifyCheckOutSuccess");

		CheckOutData checkout = CheckOutData.fromHashMap(data);
		LoggingUtils.testInfo(checkout.getFocus(), checkout.getStatus(),
				checkout.getExpectedMsg() + " should be displayed");

		checkOutPage.enterOrderInformation(checkout.getCardNumber(), checkout.getCvv(), checkout.getFullName(),
				checkout.getCoupon(), checkout.getEmail(), checkout.getCountry());
		LoggingUtils.testData(checkout);

		OrderCompletionPage orderCompeltionPage = checkOutPage.checkOut();
		LoggingUtils.info(checkout.getOrderInfo());

		String actualMsg = orderCompeltionPage.getMessage();
		String actualMessage = actualMsg + " is displayed";
		LoggingUtils.actual(actualMessage);
		setGeneratedDataContext("CheckOut", checkout.getFocus(), checkout.getStatus(), checkout.getExpectedMsg(),
				actualMsg, checkout.toPayloadMap());

		Assert.assertEquals(actualMsg, checkout.getExpectedMsg());
	}

	@BeforeMethod(alwaysRun = true)
	public void addItemToCartThenGoToCheckOutPage(Object[] data) {

		HashMap<String, String> rowData = extractRowData(data, "checkout");
		CheckOutData checkout = CheckOutData.fromHashMap(rowData);
		email = checkout.getEmail();
		pwd = checkout.getPassword();
		String product = checkout.getProduct();

		LoggingUtils.logBeforeMethodStart("addItemToCartThenGoToCheckOutPage");
		productCataloguePage = landingPage.login(email, pwd);
		LoggingUtils.logLoginAttempt(email, pwd);

		productCataloguePage.addProductToCart(product);
		LoggingUtils.info(product + " is added into cart");

		cartPage = productCataloguePage.goToCartPage();
		LoggingUtils.info("Moved to Cart page");

		checkOutPage = cartPage.goToCheckout();
		LoggingUtils.info("Moved to Check Out page");
	}
	
	@BeforeClass(alwaysRun = true)
	public void beforeClass() {
		LoggingUtils.logClassStart("CheckOut Test Class");
	}
}