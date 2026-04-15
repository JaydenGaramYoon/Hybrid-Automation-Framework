package utils.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** CRUD helpers for generated test rows and related queries via {@link DBConnection}. */
public class DBProvider {
	private static final String GENERATED_DATA_TABLE = "generated_test_data";
	DBConnection db = new DBConnection();

	/** Creates {@code generated_test_data} if missing; migrates legacy column names when present. */
	public void ensureGeneratedDataTableExists() throws SQLException {
		String createTableSql = "CREATE TABLE IF NOT EXISTS " + GENERATED_DATA_TABLE + " ("
				+ "id BIGINT PRIMARY KEY AUTO_INCREMENT, " + "run_id VARCHAR(100) NOT NULL, "
				+ "test_class VARCHAR(100) NOT NULL, " + "test_method VARCHAR(100) NOT NULL, "
				+ "focus VARCHAR(50) NULL, " + "status VARCHAR(30) NULL, " + "expected TEXT NULL, "
				+ "actual TEXT NULL, " + "data_payload TEXT NULL, "
				+ "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP" + ")";

		try (Connection conn = db.getConnection(); Statement statement = conn.createStatement()) {
			statement.execute(createTableSql);
			migrateLegacyColumnsIfNeeded(conn, statement);
		}
	}

	public void insertGeneratedTestData(String runId, String testClass, String testMethod, String focus, String status,
			String expectedMessage, String actualMessage, Map<String, String> payload) throws SQLException {
		ensureGeneratedDataTableExists();

		String insertSql = "INSERT INTO " + GENERATED_DATA_TABLE
				+ " (run_id, test_class, test_method, focus, status, expected, actual, data_payload, created_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

		try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(insertSql)) {
			ps.setString(1, runId);
			ps.setString(2, testClass);
			ps.setString(3, testMethod);
			ps.setString(4, focus);
			ps.setString(5, status);
			ps.setString(6, expectedMessage);
			ps.setString(7, actualMessage);
			ps.setString(8, mapToPayload(payload));
			ps.setTimestamp(9, Timestamp.valueOf(LocalDateTime.now()));
			ps.executeUpdate();
		}
	}

	private void migrateLegacyColumnsIfNeeded(Connection conn, Statement statement) throws SQLException {
		if (columnExists(conn, GENERATED_DATA_TABLE, "expected_message")
				&& !columnExists(conn, GENERATED_DATA_TABLE, "expected")) {
			statement.execute("ALTER TABLE " + GENERATED_DATA_TABLE + " CHANGE COLUMN expected_message expected TEXT NULL");
		}

		if (columnExists(conn, GENERATED_DATA_TABLE, "actual_message")
				&& !columnExists(conn, GENERATED_DATA_TABLE, "actual")) {
			statement.execute("ALTER TABLE " + GENERATED_DATA_TABLE + " CHANGE COLUMN actual_message actual TEXT NULL");
		}
	}

	// JDBC metadata: column present in table.
	private boolean columnExists(Connection conn, String tableName, String columnName) throws SQLException {
		try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, tableName, columnName)) {
			return rs.next();
		}
	}

	// Minimal JSON object for data_payload column.
	private String mapToPayload(Map<String, String> payload) {
		if (payload == null || payload.isEmpty()) {
			return "{}";
		}

		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> entry : payload.entrySet()) {
			if (!first) {
				sb.append(", ");
			}
			sb.append('"').append(safe(entry.getKey())).append('"').append(':').append('"')
					.append(safe(entry.getValue())).append('"');
			first = false;
		}
		sb.append('}');
		return sb.toString();
	}

	private String safe(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** Rows from {@code authentication_test_data}. */
	public List<HashMap<String, String>> getAuthenticationData() throws SQLException {

		Connection conn = db.getConnection();
		Statement statement = conn.createStatement();

		ResultSet rs = statement.executeQuery("SELECT * FROM authentication_test_data");

		List<HashMap<String, String>> dataList = new ArrayList<>();

		while (rs.next()) {

			HashMap<String, String> map = new HashMap<>();

			map.put("fName", rs.getString("first_name"));
			map.put("lName", rs.getString("last_name"));
			map.put("phoneNumber", rs.getString("phone"));
			map.put("occupation", rs.getString("occupation"));
			map.put("gender", rs.getString("gender"));
			map.put("pwd", rs.getString("password"));

			dataList.add(map);
		}

		conn.close();

		return dataList;
	}

	/** Rows from {@code purchase_test_data}. */
	public List<HashMap<String, String>> getPurchaseTestData() throws SQLException {

		Connection conn = db.getConnection();
		Statement statement = conn.createStatement();

		ResultSet rs = statement.executeQuery("SELECT * FROM purchase_test_data");

		List<HashMap<String, String>> dataList = new ArrayList<>();

		while (rs.next()) {

			HashMap<String, String> map = new HashMap<>();

			map.put("fName", rs.getString("first_name"));
			map.put("lName", rs.getString("last_name"));
			map.put("phoneNumber", rs.getString("phone"));
			map.put("occupation", rs.getString("occupation"));
			map.put("gender", rs.getString("gender"));
			map.put("pwd", rs.getString("password"));
			map.put("creditCardNumber", rs.getString("credit_card_number"));
			map.put("cvv", rs.getString("cvv"));
			map.put("nameOnCard", rs.getString("card_holder_name"));
			map.put("coupon", rs.getString("coupon"));
			map.put("country", rs.getString("country"));
			map.put("productName", rs.getString("product_name"));

			dataList.add(map);
		}

		conn.close();

		return dataList;
	}

	/** Rows from {@code login_error_test_data}. */
	public List<HashMap<String, String>> getErrorData() throws SQLException {

		Connection conn = db.getConnection();
		Statement statement = conn.createStatement();

		ResultSet rs = statement.executeQuery("SELECT * FROM login_error_test_data");

		List<HashMap<String, String>> dataList = new ArrayList<>();

		while (rs.next()) {

			HashMap<String, String> map = new HashMap<>();

			map.put("email", rs.getString("email"));
			map.put("pwd", rs.getString("password"));
			map.put("wrongPwd", rs.getString("wrong_password"));
			map.put("product", rs.getString("product"));
			map.put("wrongProduct", rs.getString("wrong_product"));

			dataList.add(map);
		}

		conn.close();

		return dataList;
	}
}
