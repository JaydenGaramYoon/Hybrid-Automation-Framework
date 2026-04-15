package utils.annotations;

import java.lang.annotation.*;

/** Excel workbook ({@link #file()}) and sheet/type tag for data providers and reporting. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface TestInfo {
    String file();
    String type();
}
