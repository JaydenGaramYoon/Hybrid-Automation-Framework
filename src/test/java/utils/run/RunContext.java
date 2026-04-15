package utils.run;

import org.testng.ITestResult;

/**
 * Per-run and per-thread IDs for reporting: {@code run_id} is fixed for the JVM; {@code test_id} is
 * {@code class#method} with optional {@code [paramDesc][invalidReason]} or {@code [index]} from data-driven tests.
 */
public final class RunContext {

	private static final String RUN_ID = "run-" + System.currentTimeMillis();
	
	static {
		String cleanRunId = RUN_ID.replaceAll("^run-", "");
		System.setProperty("logRunId", cleanRunId);
	}
	
	private static final ThreadLocal<Integer> CURRENT_PARAM_INDEX = ThreadLocal.withInitial(() -> -1);
	private static final ThreadLocal<String> CURRENT_PARAM_DESCRIPTION = ThreadLocal.withInitial(() -> null);
	private static final ThreadLocal<String> CURRENT_INVALID_REASON = ThreadLocal.withInitial(() -> null);
	private static final ThreadLocal<String> CURRENT_SKIP_REASON = ThreadLocal.withInitial(() -> null);
	private static final ThreadLocal<Integer> CURRENT_ATTEMPT = ThreadLocal.withInitial(() -> 0);
	private static final ThreadLocal<String> CURRENT_TEST_FOCUS = ThreadLocal.withInitial(() -> null);
	private static final ThreadLocal<String> CURRENT_DATA_STATUS = ThreadLocal.withInitial(() -> null);
	private static final ThreadLocal<String> CURRENT_TC_ID = ThreadLocal.withInitial(() -> null);

	private RunContext() {
	}

	public static String getRunId() {
		return RUN_ID;
	}

	public static String testId(Class<?> testClass, String methodName) {
		return testClass.getName() + "#" + methodName;
	}

	/**
	 * Prefers listener-supplied parameter description (and optional invalid-reason suffix); otherwise uses the
	 * data row index, else the bare {@code class#method}.
	 */
	public static String testId(ITestResult result) {
		String baseTestId = testId(result.getMethod().getRealClass(), result.getMethod().getMethodName());
		
		String paramDesc = CURRENT_PARAM_DESCRIPTION.get();
		String invalidReason = CURRENT_INVALID_REASON.get();
		
		if (paramDesc != null && !paramDesc.isEmpty()) {
			String testIdWithParam = baseTestId + "[" + paramDesc + "]";
			if (invalidReason != null && !invalidReason.isEmpty()) {
				testIdWithParam = testIdWithParam + "[" + invalidReason + "]";
			}
			return testIdWithParam;
		}
		
		int paramIndex = CURRENT_PARAM_INDEX.get();
		if (paramIndex >= 0) {
			return baseTestId + "[" + paramIndex + "]";
		}
		
		return baseTestId;
	}

	public static void setParameterIndex(int index) {
		if (index < 0) {
			CURRENT_PARAM_INDEX.remove();
		} else {
			CURRENT_PARAM_INDEX.set(index);
		}
	}

	public static void setParameterDescription(String description) {
		if (description == null || description.isEmpty()) {
			CURRENT_PARAM_DESCRIPTION.remove();
		} else {
			CURRENT_PARAM_DESCRIPTION.set(description);
		}
	}

	public static void setInvalidReason(String reason) {
		if (reason == null || reason.isEmpty()) {
			CURRENT_INVALID_REASON.remove();
		} else {
			CURRENT_INVALID_REASON.set(reason);
		}
	}

	public static int getParameterIndex() {
		return CURRENT_PARAM_INDEX.get();
	}

	public static String getParameterDescription() {
		return CURRENT_PARAM_DESCRIPTION.get();
	}

	public static String getInvalidReason() {
		return CURRENT_INVALID_REASON.get();
	}

	/** Clears param/skip/focus state for this thread (also clears attempt). */
	public static void clearParameterInfo() {
		CURRENT_PARAM_INDEX.remove();
		CURRENT_PARAM_DESCRIPTION.remove();
		CURRENT_INVALID_REASON.remove();
		CURRENT_ATTEMPT.remove();
		CURRENT_SKIP_REASON.remove();
		CURRENT_TEST_FOCUS.remove();
		CURRENT_DATA_STATUS.remove();
		clearTcId();
	}

	public static void setSkipReason(String reason) {
		if (reason == null || reason.isEmpty()) {
			CURRENT_SKIP_REASON.remove();
		} else {
			CURRENT_SKIP_REASON.set(reason);
		}
	}

	public static String getSkipReason() {
		return CURRENT_SKIP_REASON.get();
	}

	public static void setAttempt(int attempt) {
		if (attempt < 0) {
			CURRENT_ATTEMPT.remove();
		} else {
			CURRENT_ATTEMPT.set(attempt);
		}
	}

	public static int getAttempt() {
		Integer val = CURRENT_ATTEMPT.get();
		return val != null ? val : 0;
	}

	public static void setTestFocus(String focus) {
		if (focus == null || focus.isEmpty()) {
			CURRENT_TEST_FOCUS.remove();
		} else {
			CURRENT_TEST_FOCUS.set(focus);
		}
	}

	public static String getTestFocus() {
		return CURRENT_TEST_FOCUS.get();
	}

	public static void setDataStatus(String status) {
		if (status == null || status.isEmpty()) {
			CURRENT_DATA_STATUS.remove();
		} else {
			CURRENT_DATA_STATUS.set(status);
		}
	}

	public static String getDataStatus() {
		return CURRENT_DATA_STATUS.get();
	}

	/** Excel / data row {@code tc_id} for traceability (history, bundles, logs, screenshot names). */
	public static void setTcId(String tcId) {
		if (tcId == null || tcId.isEmpty()) {
			CURRENT_TC_ID.remove();
		} else {
			CURRENT_TC_ID.set(tcId.trim());
		}
	}

	public static String getTcId() {
		return CURRENT_TC_ID.get();
	}

	public static void clearTcId() {
		CURRENT_TC_ID.remove();
	}

	/**
	 * Reads {@code tc_id} from the first test parameter when it is a {@link java.util.Map}.
	 * Clears any previous tc id when missing or not a map row.
	 */
	public static void refreshTcIdFromTestParameters(ITestResult result) {
		clearTcId();
		if (result == null) {
			return;
		}
		Object[] params = result.getParameters();
		if (params == null || params.length == 0) {
			return;
		}
		Object first = params[0];
		if (!(first instanceof java.util.Map)) {
			return;
		}
		@SuppressWarnings("unchecked")
		java.util.Map<String, ?> map = (java.util.Map<String, ?>) first;
		Object v = map.get("tc_id");
		if (v == null) {
			v = map.get("TC_ID");
		}
		if (v != null) {
			String s = v.toString().trim();
			if (!s.isEmpty()) {
				setTcId(s);
			}
		}
	}
}
