package io.lettuce.core.event;

/**
 * Exception thrown when attempting to use reactive EventBus features without Project Reactor on the classpath.
 * <p>
 * This exception is thrown by {@link EventBus#reactive()} when Reactor is not available.
 *
 * @author Aleksandar Todorov
 * @since 7.5
 * @see EventBus#reactive()
 */
public class ReactorNotAvailableException extends IllegalStateException {

    /**
     * Creates a new {@link ReactorNotAvailableException}.
     *
     * @param message the detail message
     */
    public ReactorNotAvailableException(String message) {
        super(message);
    }

}
