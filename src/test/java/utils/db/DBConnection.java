package utils.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import utils.config.ConfigUtils;

/** JDBC URL from {@code db_*} keys in {@link ConfigUtils}. */
public class DBConnection {

	private final String host = ConfigUtils.get("db_host");
	private final String port = ConfigUtils.get("db_port");
	private final String database = ConfigUtils.get("db_name");
	private final String user = ConfigUtils.get("db_user");
	private final String password = ConfigUtils.get("db_password");
	private final String url = "jdbc:mysql://" + host + ":" + port + "/" + database;

	/** {@code true} when {@code db_host} is set; use to skip optional DB features when unset. */
	public boolean isConfigured() {
		return host != null && !host.trim().isEmpty();
	}

	public Connection getConnection() throws SQLException {
		Connection conn = DriverManager.getConnection(url, user, password);

		return conn;
	}
}
