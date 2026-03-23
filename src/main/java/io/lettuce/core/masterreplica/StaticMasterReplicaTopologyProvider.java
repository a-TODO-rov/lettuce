package io.lettuce.core.masterreplica;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.internal.Exceptions;
import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.models.role.RedisNodeDescription;
import io.lettuce.core.models.role.RoleParser;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Topology provider for a static node collection. This provider uses a static collection of nodes to determine the role of each
 * {@link RedisURI node}. Node roles may change during runtime but the configuration must remain the same. This
 * {@link TopologyProvider} does not auto-discover nodes.
 *
 * @author Mark Paluch
 * @author Adam McElwee
 */
class StaticMasterReplicaTopologyProvider implements TopologyProvider {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(StaticMasterReplicaTopologyProvider.class);

    private final RedisClient redisClient;

    private final Iterable<RedisURI> redisURIs;

    public StaticMasterReplicaTopologyProvider(RedisClient redisClient, Iterable<RedisURI> redisURIs) {

        LettuceAssert.notNull(redisClient, "RedisClient must not be null");
        LettuceAssert.notNull(redisURIs, "RedisURIs must not be null");
        LettuceAssert.notNull(redisURIs.iterator().hasNext(), "RedisURIs must not be empty");

        this.redisClient = redisClient;
        this.redisURIs = redisURIs;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<RedisNodeDescription> getNodes() {

        RedisURI next = redisURIs.iterator().next();

        try {
            return getNodesAsync().get(next.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw Exceptions.bubble(e);
        }
    }

    @Override
    public CompletableFuture<List<RedisNodeDescription>> getNodesAsync() {

        List<CompletableFuture<RedisNodeDescription>> nodeFutures = new CopyOnWriteArrayList<>();

        for (RedisURI uri : redisURIs) {
            nodeFutures.add(getNodeDescription(uri));
        }

        CompletableFuture<?>[] arr = nodeFutures.toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(arr).thenApply(v -> {

            List<RedisNodeDescription> nodeDescriptions = new CopyOnWriteArrayList<>();
            for (CompletableFuture<RedisNodeDescription> f : nodeFutures) {
                RedisNodeDescription node = f.join();
                if (node != null) {
                    nodeDescriptions.add(node);
                }
            }

            if (nodeDescriptions.isEmpty()) {
                throw new RedisConnectionException(String.format("Failed to connect to at least one node in %s", redisURIs));
            }

            return (List<RedisNodeDescription>) nodeDescriptions;
        });
    }

    private CompletableFuture<RedisNodeDescription> getNodeDescription(RedisURI uri) {

        return redisClient.connectAsync(StringCodec.UTF8, uri).toCompletableFuture().thenCompose(connection -> {
            return getNodeDescription(uri, connection).thenCompose(it -> ResumeAfter.close(connection).thenEmit(it));
        }).handle((result, t) -> {
            if (t != null) {
                logger.warn("Cannot connect to {}", uri, t);
                return null;
            }
            return result;
        });
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static CompletableFuture<RedisNodeDescription> getNodeDescription(RedisURI uri,
            StatefulRedisConnection<String, String> connection) {

        return connection.async().role().toCompletableFuture().thenApply(roleOutputs -> {
            return (RedisNodeDescription) new RedisMasterReplicaNode(uri.getHost(), uri.getPort(), uri,
                    RoleParser.parse(roleOutputs).getRole());
        });
    }

}
