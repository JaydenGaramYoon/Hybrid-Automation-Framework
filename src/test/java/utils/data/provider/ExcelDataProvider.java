package utils.data.provider;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.testng.annotations.DataProvider;

import utils.annotations.TestInfo;
import utils.data.util.DataFakerUtils;
import utils.data.util.ExcelUtils;

/**
 * TestNG {@link org.testng.annotations.DataProvider}s backed by {@link ExcelUtils} and {@link TestInfo}
 * (workbook path and sheet names are convention-based).
 */
public class ExcelDataProvider {
	static String xlsxSignUp = System.getProperty("user.dir") + "\\testData\\SignUp.xlsx";
	static String fileName = System.getProperty("user.dir") + "\\testData\\LogIn.xlsx";
	static String sheetSignUp = "Sheet1";
	static String sheetName = "Sheet1";


	@DataProvider(name = "ValidSignUpData")
	public static Object[][] getValidData() throws IOException {

	    Object[][] allData = getAllSignUpData();
	    List<Object[]> filtered = new ArrayList<>();

	    for (Object[] obj : allData) {
	        HashMap<String, String> map = (HashMap<String, String>) obj[0];

	        if ("valid".equalsIgnoreCase(map.get("status"))) {
	            filtered.add(obj);
	        }
	    }

	    return filtered.toArray(new Object[0][0]);
	}
	
	@DataProvider(name = "InvalidSignUpData")
	public static Object[][] getInvalidData() throws IOException {

	    Object[][] allData = getAllSignUpData();
	    List<Object[]> filtered = new ArrayList<>();

	    for (Object[] obj : allData) {
	        HashMap<String, String> map = (HashMap<String, String>) obj[0];

	        if ("invalid".equalsIgnoreCase(map.get("status"))) {
	            filtered.add(obj);
	        }
	    }

	    return filtered.toArray(new Object[0][0]);
	}
	
	@DataProvider(name = "testData")
	public static Object[][] getTestData(Method method) throws IOException {

	    TestInfo info = method.getAnnotation(TestInfo.class);

	    String file = info.file();
	    String filter = info.type();

	    return filterData(getData(file, "Sheet1"), filter);
	}
	
	public static Object[][] getData(String file, String sheet) throws IOException {

	    ExcelUtils excel = new ExcelUtils(System.getProperty("user.dir") + "\\testData\\" + file);

	    int rows = excel.getRowCount(sheet);
	    int cols = excel.getCellCount(sheet, 0);

	    List<String> header = new ArrayList<>();

	    for (int c = 0; c < cols; c++) {
	        header.add(excel.getCellData(sheet, 0, c));
	    }

	    List<Object[]> dataList = new ArrayList<>();

	    for (int r = 1; r <= rows; r++) {
	        HashMap<String, String> map = new HashMap<>();

	        for (int c = 0; c < cols; c++) {
	            map.put(header.get(c), excel.getCellData(sheet, r, c));
	        }

	        if (map.values().stream().allMatch(v -> v == null || v.trim().isEmpty())) {
	            continue;
	        }

	        map.put("rowNum", String.valueOf(r));
	        dataList.add(new Object[]{map});
	    }

	    return dataList.toArray(new Object[0][0]);
	}
	
	private static Object[][] filterData(Object[][] allData, String status) {

	    List<Object[]> filtered = new ArrayList<>();

	    for (Object[] obj : allData) {
	        HashMap<String, String> map = (HashMap<String, String>) obj[0];

	        if (status.equalsIgnoreCase(map.get("status"))) {
	            filtered.add(obj);
	        }
	    }

	    return filtered.toArray(new Object[0][0]);
	}
	
	public static Object[][] getAllSignUpData() throws IOException {
	    ExcelUtils excel = new ExcelUtils(xlsxSignUp);
	    int rows = excel.getRowCount(sheetSignUp);
	    int cols = excel.getCellCount(sheetSignUp, 0);

	    List<String> header = new ArrayList<>();

	    for (int c = 0; c < cols; c++) {
	        header.add(excel.getCellData(sheetSignUp, 0, c));
	    }

	    List<Object[]> dataList = new ArrayList<>();

	    for (int r = 1; r <= rows; r++) {
	        HashMap<String, String> map = new HashMap<>();

	        for (int c = 0; c < cols; c++) {
	            map.put(header.get(c), excel.getCellData(sheetSignUp, r, c));
	        }

	        if (map.values().stream().allMatch(v -> v == null || v.trim().isEmpty())) {
	            continue;
	        }

	        map.put("rowNum", String.valueOf(r));
	        dataList.add(new Object[]{map});
	    }

	    return dataList.toArray(new Object[0][0]);
	}
	
	@DataProvider(name = "GetSignUpData")
	public static Object[][] getSignUpData() throws IOException {
		ExcelUtils excel = new ExcelUtils(xlsxSignUp);
		int rows = excel.getRowCount(sheetSignUp);
		int cols = excel.getCellCount(sheetSignUp, 0);
		List<String> header = new ArrayList<>();

		for (int c = 0; c < cols; c++) {
			header.add(excel.getCellData(sheetSignUp, 0, c));
		}

		Object[][] data = new Object[rows][1];

		for (int r = 1; r <= rows; r++) {
			HashMap<String, String> map = new HashMap<>();
			for (int c = 0; c < cols; c++) {
				String key = header.get(c);
				String value = excel.getCellData(sheetSignUp, r, c);
				map.put(key, value);
			}
			if (map.values().stream().allMatch(v -> v == null || v.trim().isEmpty())) {
				continue;
			}
			map.put("rowNum", String.valueOf(r));
			data[r - 1][0] = map;
		}
		return data;
	}

	public static void setSignUpData(String path, String sheetName, List<String> columns) throws IOException {
		ExcelUtils excel = new ExcelUtils(path);
		
		int rows = excel.getRowCount(sheetName);
		for (String c : columns) {
			int colIndex = excel.getColumnIndex(sheetName, c);
			for (int r = 1; r <= rows; r++) {
				String value = "";

				if (c.equals("generated_email")) {
					value = DataFakerUtils.getRandomEmail();
				} else if (c.equals("generated_phone_number")) {
					value = DataFakerUtils.getRandomPhoneNumber();
				} else if (c.equals("generated_password")) {
					value = DataFakerUtils.getRandomPwd();
				}
				excel.setCellData(sheetName, r, colIndex, value);
			}
		}
	}
}
