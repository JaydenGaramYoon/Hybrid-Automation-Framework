package utils.data.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Thin Apache POI wrapper for {@code .xlsx} test data (row/cell read/write and header lookup).
 */
public class ExcelUtils {

	public FileInputStream fi;
	public FileOutputStream fo;
	public XSSFWorkbook workbook;
	public XSSFSheet sheet;
	public XSSFRow row;
	public XSSFCell cell;
	public CellStyle style;
	String path;

	/**
	 * @param path filesystem path to the {@code .xlsx} workbook (relative to working directory or absolute)
	 */
	public ExcelUtils(String path) {
		this.path = path;
	}

	/**
	 * @param sheetName sheet tab name
	 * @return last row index in the sheet (0-based; POI semantics)
	 */
	public int getRowCount(String sheetName) throws IOException {
		fi = new FileInputStream(path);
		workbook = new XSSFWorkbook(fi);
		sheet = workbook.getSheet(sheetName);
		int rowcount = sheet.getLastRowNum();
		workbook.close();
		fi.close();
		return rowcount;
	}

	/**
	 * @param sheetName sheet tab name
	 * @param rownum    0-based row index
	 * @return number of cells in that row (last cell index)
	 */
	public int getCellCount(String sheetName, int rownum) throws IOException {
		fi = new FileInputStream(path);
		workbook = new XSSFWorkbook(fi);
		sheet = workbook.getSheet(sheetName);
		row = sheet.getRow(rownum);
		int cellcount = row.getLastCellNum();
		workbook.close();
		fi.close();
		return cellcount;
	}

	/**
	 * Reads a cell as display string via {@link DataFormatter} (safe for numbers/dates).
	 *
	 * @param sheetName sheet tab name
	 * @param rownum    0-based row
	 * @param colnum    0-based column
	 * @return formatted value, or empty string if the cell is missing or unreadable
	 */
	public String getCellData(String sheetName, int rownum, int colnum) throws IOException {
		fi = new FileInputStream(path);
		workbook = new XSSFWorkbook(fi);
		sheet = workbook.getSheet(sheetName);
		row = sheet.getRow(rownum);
		cell = row.getCell(colnum);

		DataFormatter formatter = new DataFormatter();
		String data;
		try {
			data = formatter.formatCellValue(cell);
		} catch (Exception e) {
			data = "";
		}
		workbook.close();
		fi.close();
		return data;
	}

	/**
	 * Creates the workbook/sheet/row as needed and writes {@code data} into one cell.
	 *
	 * @param sheetName sheet tab name
	 * @param rownum    0-based row
	 * @param column    0-based column
	 */
	public void setCellData(String sheetName, int rownum, int column, String data) throws IOException {

		File xlfile = new File(path);

		if (!xlfile.exists()) {
			workbook = new XSSFWorkbook();
			fo = new FileOutputStream(path);
			workbook.write(fo);
		}

		fi = new FileInputStream(path);
		workbook = new XSSFWorkbook(fi);

		if (workbook.getSheetIndex(sheetName) == -1)
			workbook.createSheet(sheetName);

		sheet = workbook.getSheet(sheetName);

		if (sheet.getRow(rownum) == null)
			sheet.createRow(rownum);

		row = sheet.getRow(rownum);

		cell = row.createCell(column);
		cell.setCellValue(data);

		fo = new FileOutputStream(path);
		workbook.write(fo);

		workbook.close();
		fi.close();
		fo.close();
	}

	/**
	 * Finds a column index by matching row 0 header cells to {@code columnName} (case-insensitive).
	 * Uses the current {@link #sheet} field; callers must have opened the workbook and selected the target sheet.
	 *
	 * @param sheetName reserved for API symmetry (sheet must already be selected on {@link #sheet})
	 * @return 0-based column index
	 * @throws RuntimeException if the column name is not found
	 */
	public int getColumnIndex(String sheetName, String columnName) {

		XSSFRow headerRow = sheet.getRow(0);

		for (int c = 0; c < headerRow.getLastCellNum(); c++) {
			String cellValue = headerRow.getCell(c).toString();

			if (cellValue.equalsIgnoreCase(columnName)) {
				return c;
			}
		}

		throw new RuntimeException("Column not found: " + columnName);
	}

}
