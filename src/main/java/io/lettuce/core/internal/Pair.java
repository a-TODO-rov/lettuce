package io.lettuce.core.internal;

import java.util.Objects;

/**
 * A simple immutable pair of two values, used as an internal replacement for Reactor's {@code Tuple2}.
 *
 * @param <T1> the type of the first element
 * @param <T2> the type of the second element
 * @author Aleksandar Todorov
 * @since 7.0
 */
public final class Pair<T1, T2> {

    private final T1 first;

    private final T2 second;

    private Pair(T1 first, T2 second) {
        this.first = first;
        this.second = second;
    }

    /**
     * Create a new {@link Pair}.
     *
     * @param first the first element, must not be {@code null}
     * @param second the second element, must not be {@code null}
     * @param <T1> the type of the first element
     * @param <T2> the type of the second element
     * @return the new {@link Pair}
     */
    public static <T1, T2> Pair<T1, T2> of(T1 first, T2 second) {
        return new Pair<>(first, second);
    }

    /**
     * @return the first element.
     */
    public T1 getFirst() {
        return first;
    }

    /**
     * @return the second element.
     */
    public T2 getSecond() {
        return second;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Pair))
            return false;
        Pair<?, ?> pair = (Pair<?, ?>) o;
        return Objects.equals(first, pair.first) && Objects.equals(second, pair.second);
    }

    @Override
    public int hashCode() {
        return Objects.hash(first, second);
    }

    @Override
    public String toString() {
        return "[" + first + ", " + second + "]";
    }

}

