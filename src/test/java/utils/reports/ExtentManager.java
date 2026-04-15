package utils.reports;

import com.aventstack.extentreports.ExtentTest;

/** Thread-local current {@link ExtentTest} for parallel runs. */
public class ExtentManager {

	private static ThreadLocal<ExtentTest> extentTest = new ThreadLocal<>();

	public static ExtentTest getTest() {
		return extentTest.get();
	}

	public static void setTest(ExtentTest test) {
		extentTest.set(test);
	}
}