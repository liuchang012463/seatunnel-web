package org.apache.seatunnel.web.dao.repository;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;

import javax.annotation.Nonnull;

/**
 * Type adapter for getter references passed to MyBatis-Plus lambda wrappers.
 *
 * <p>MyBatis-Plus serializes these references to discover a database column;
 * it does not invoke them with a possibly-null entity. The narrower functional
 * descriptor makes that input contract explicit to null analysis.</p>
 */
public final class MyBatisColumn {

    private MyBatisColumn() {
    }

    /** Adapts a getter reference without changing its serialized lambda. */
    public static <T, R> NonNullGetter<T, R> getter(NonNullGetter<T, R> getter) {
        return getter;
    }

    @FunctionalInterface
    public interface NonNullGetter<T, R> extends SFunction<T, R> {
        @Override
        R apply(@Nonnull T row);
    }
}
