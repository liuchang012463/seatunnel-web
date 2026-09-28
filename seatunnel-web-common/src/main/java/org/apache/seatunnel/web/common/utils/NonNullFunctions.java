package org.apache.seatunnel.web.common.utils;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.function.Function;

/** Adapters for callbacks that invoke an instance method on their input. */
public final class NonNullFunctions {

    private NonNullFunctions() {
    }

    /**
     * A callback whose implementation requires a non-null input object.
     * The null-safe adapter below maps a null input to a null result.
     */
    @FunctionalInterface
    public interface NonNullFunction<T, R> extends Function<T, R> {
        @Override
        R apply(@Nonnull T value);
    }

    /**
     * Adapts an instance-method callback so null inputs produce null outputs
     * instead of dereferencing a null receiver.
     */
    public static <T, R> Function<T, R> from(@Nonnull NonNullFunction<T, R> function) {
        return value -> value == null ? null : function.apply(value);
    }

    /**
     * Builds a comparator that sorts null elements last and delegates key null handling.
     */
    public static <T, R> Comparator<T> comparing(
            @Nonnull NonNullFunction<T, R> keyExtractor,
            @Nonnull Comparator<? super R> keyComparator) {
        return (left, right) -> {
            if (left == null) {
                return right == null ? 0 : 1;
            }
            if (right == null) {
                return -1;
            }
            return keyComparator.compare(keyExtractor.apply(left), keyExtractor.apply(right));
        };
    }

    /**
     * Builds a natural-order comparator that sorts null elements and keys last.
     */
    public static <T, R extends Comparable<? super R>> Comparator<T> comparing(
            @Nonnull NonNullFunction<T, R> keyExtractor) {
        return comparing(keyExtractor, Comparator.nullsLast(Comparator.naturalOrder()));
    }

}
