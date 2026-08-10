package org.apache.seatunnel.web.spi.form;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface FormField {

    String label();

    boolean required() default false;

    String placeholder() default "";

    String defaultValue() default "";

    /**
     * Optional visibility expression consumed by dynamic frontend forms.
     *
     * <p>The expression uses the simple form {@code field=value}. Multiple
     * accepted values can be separated with {@code |}.</p>
     */
    String visibleWhen() default "";

    FieldType type() default FieldType.INPUT;

    int order() default 0;
}
