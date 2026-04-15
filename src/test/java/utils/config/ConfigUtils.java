package utils.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Loads {@code src/test/resources/config.properties} once. {@link #get(String)} prefers
 * {@code System.getProperty(key)} over file values so CI can override (-Dkey=value).
 */
public class ConfigUtils {

    private static Properties prop;

    static {
        loadConfig();
    }

    /**
     * Loads {@code config.properties} from the project root relative path used by tests.
     *
     * @throws RuntimeException if the file is missing or unreadable
     */
    public static Properties loadConfig() {
        try (FileInputStream fis = new FileInputStream(
                System.getProperty("user.dir") + "\\src\\test\\resources\\config.properties")) {

            prop = new Properties();
            prop.load(fis);

        } catch (IOException e) {
            throw new RuntimeException("Failed to load config file", e);
        }
		return prop;
    }
    
    /**
     * @param key case-sensitive key
     * @return system property if set, otherwise file value, otherwise {@code null}
     */
    public static String get(String key) {
        // Priority: System Property > config.properties
        String sysProperty = System.getProperty(key);
        if (sysProperty != null && !sysProperty.isEmpty()) {
            return sysProperty;
        }
        
        return prop.getProperty(key);
    }
    
    /**
     * @return parsed int, or {@code 0} when the key is missing
     * @throws NumberFormatException when the value is non-empty but not an integer
     */
    public static int getInt(String key) {
        String value = get(key);
        return value != null ? Integer.parseInt(value) : 0;
    }
}