package io.lettuce.core.cluster.api.async;

import io.lettuce.core.cluster.api.NodeSelectionSupport;

/**
 * Asynchronous and thread-safe Redis API to execute commands on a {@link NodeSelectionSupport}.
 *
 * @author Mark Paluch
 * @author Tihomir Mateev
 */
public interface NodeSelectionAsyncCommands<K, V>
        extends BaseNodeSelectionAsyncCommands<K, V>, NodeSelectionStringAsyncCommands<K, V> {
}
