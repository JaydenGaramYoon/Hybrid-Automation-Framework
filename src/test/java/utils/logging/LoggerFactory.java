package utils.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Caches one Log4j {@link Logger} per class or name (thread-safe). */
public class LoggerFactory {
	private static final Map<String, Logger> loggers = new ConcurrentHashMap<>();

	public static Logger getLogger(Class<?> clazz) {
		String className = clazz.getSimpleName();
		return loggers.computeIfAbsent(className, key -> LogManager.getLogger(clazz));
	}

	public static Logger getLogger(String name) {
		return loggers.computeIfAbsent(name, key -> LogManager.getLogger(name));
	}
}