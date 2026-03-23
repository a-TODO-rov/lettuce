package io.lettuce.core.cluster.api.sync;

import io.lettuce.core.cluster.api.NodeSelectionSupport;

/**
 * Synchronous and thread-safe Redis API to execute commands on a {@link NodeSelectionSupport}.
 *
 * @author Mark Paluch
 * @author Tihomir Mateev
 */
public interface NodeSelectionCommands<K, V> extends BaseNodeSelectionCommands<K, V>, NodeSelectionStringCommands<K, V> {
}
