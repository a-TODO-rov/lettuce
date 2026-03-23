package io.lettuce.core;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Scan command support exposed through {@link Iterator}.
 * <p>
 * Stripped for PoC - scan commands removed since they use removed command interfaces.
 *
 * @param <T> Element type.
 * @author Mark Paluch
 * @since 5.1
 */
public abstract class ScanIterator<T> implements Iterator<T> {

    private ScanIterator() {
    }

    /**
     * @return {@code true} if the iteration has more elements.
     */
    @Override
    public boolean hasNext() {
        throw new UnsupportedOperationException("Scan commands removed in PoC");
    }

    /**
     * @return the next element in the iteration.
     */
    @Override
    public T next() {
        throw new NoSuchElementException();
    }
}
