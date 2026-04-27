package io.lettuce.core.resource;

import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.internal.LettuceClassUtils;

/**
 * Provider for Project Reactor availability detection and guard functionality.
 * <p>
 * This class follows the same pattern as {@link EpollProvider}, {@link KqueueProvider}, and {@link IOUringProvider} for
 * handling optional dependencies. It provides:
 * <ul>
 * <li>Detection of Reactor availability via {@link #isAvailable()}</li>
 * <li>Guard method {@link #checkForReactorLibrary()} for clear error messages</li>
 * </ul>
 * <p>
 * Use {@link #checkForReactorLibrary()} at reactive API entry points to provide users with actionable error messages when
 * Reactor is not on the classpath, instead of cryptic {@link NoClassDefFoundError}.
 *
 * @author Aleksandar Todorov
 * @since 7.0
 * @see EpollProvider
 */
public class ReactorProvider {

    private static final boolean REACTOR_AVAILABLE = LettuceClassUtils.isPresent("reactor.core.publisher.Mono");

    /**
     * @return {@code true} if Project Reactor is available on the classpath.
     */
    public static boolean isAvailable() {
        return REACTOR_AVAILABLE;
    }

    /**
     * Checks for the presence of the Project Reactor library on the classpath. Throws an {@link IllegalStateException} if
     * Reactor is not available, providing a clear error message with instructions.
     * <p>
     * Call this method at entry points to the reactive API (e.g., {@code connectReactive()}, {@code reactive()}) before
     * returning or using any Reactor types.
     *
     * @throws IllegalStateException if Project Reactor (reactor-core) is not available on the classpath.
     */
    public static void checkForReactorLibrary() {
        LettuceAssert.assertState(isAvailable(),
                "Project Reactor (reactor-core) is not available. Add reactor-core to your classpath to use the reactive API.");
    }

}
