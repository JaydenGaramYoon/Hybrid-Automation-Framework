package triage.model.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

public class EnvironmentSnapshot {

	@JsonProperty("browser")
	private String browser;

	@JsonProperty("browser_version")
	private String browserVersion;

	@JsonProperty("browser_version_note")
	private String browserVersionNote;

	@JsonProperty("os")
	private String os;

	@JsonIgnore
	@JsonProperty("os_version")
	private String osVersion;

	@JsonProperty("execution_env")
	private String executionEnv;

	@JsonProperty("grid_url")
	private String gridUrl;

	@JsonProperty("base_url")
	private String baseUrl;

	@JsonProperty("java_version")
	private String javaVersion;

	@JsonProperty("selenium_version")
	private String seleniumVersion;

	@JsonProperty("testng_version")
	private String testngVersion;

	public EnvironmentSnapshot() {
	}

	public String getBrowser() {
		return browser;
	}

	public void setBrowser(String browser) {
		this.browser = browser;
	}

	public String getBrowserVersion() {
		return browserVersion;
	}

	public void setBrowserVersion(String browserVersion) {
		this.browserVersion = browserVersion;
	}

	public String getBrowserVersionNote() {
		return browserVersionNote;
	}

	public void setBrowserVersionNote(String browserVersionNote) {
		this.browserVersionNote = browserVersionNote;
	}

	public String getOs() {
		return os;
	}

	public void setOs(String os) {
		this.os = os;
	}

	public String getOsVersion() {
		return osVersion;
	}

	public void setOsVersion(String osVersion) {
		this.osVersion = osVersion;
	}

	public String getExecutionEnv() {
		return executionEnv;
	}

	public void setExecutionEnv(String executionEnv) {
		this.executionEnv = executionEnv;
	}

	public String getGridUrl() {
		return gridUrl;
	}

	public void setGridUrl(String gridUrl) {
		this.gridUrl = gridUrl;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getJavaVersion() {
		return javaVersion;
	}

	public void setJavaVersion(String javaVersion) {
		this.javaVersion = javaVersion;
	}

	public String getSeleniumVersion() {
		return seleniumVersion;
	}

	public void setSeleniumVersion(String seleniumVersion) {
		this.seleniumVersion = seleniumVersion;
	}

	public String getTestngVersion() {
		return testngVersion;
	}

	public void setTestngVersion(String testngVersion) {
		this.testngVersion = testngVersion;
	}

	@Override
	public String toString() {
		return "EnvironmentSnapshot [browser=" + browser + ", os=" + os + ", executionEnv=" + executionEnv + "]";
	}
}
