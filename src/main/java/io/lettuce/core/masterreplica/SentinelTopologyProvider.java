package io.lettuce.core.masterreplica;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.internal.Exceptions;
import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.models.role.RedisInstance;
import io.lettuce.core.models.role.RedisNodeDescription;
import io.lettuce.core.sentinel.api.StatefulRedisSentinelConnection;
import io.lettuce.core.sentinel.api.async.RedisSentinelAsyncCommands;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * Topology provider using Redis Sentinel and the Sentinel API.
 *
 * @author Mark Paluch
 * @since 4.1
 */
class SentinelTopologyProvider implements TopologyProvider {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(SentinelTopologyProvider.class);

    private final String masterId;

    private final RedisClient redisClient;

    private final RedisURI sentinelUri;

    private final Duration timeout;

    /**
     * Creates a new {@link SentinelTopologyProvider}.
     *
     * @param masterId must not be empty
     * @param redisClient must not be {@code null}.
     * @param sentinelUri must not be {@code null}.
     */
    public SentinelTopologyProvider(String masterId, RedisClient redisClient, RedisURI sentinelUri) {

        LettuceAssert.notEmpty(masterId, "MasterId must not be empty");
        LettuceAssert.notNull(redisClient, "RedisClient must not be null");
        LettuceAssert.notNull(sentinelUri, "Sentinel URI must not be null");

        this.masterId = masterId;
        this.redisClient = redisClient;
        this.sentinelUri = sentinelUri;
        this.timeout = sentinelUri.getTimeout();
    }

    @Override
    public List<RedisNodeDescription> getNodes() {

        logger.debug("lookup topology for masterId {}", masterId);

        try {
            return getNodesAsync().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw Exceptions.bubble(e);
        }
    }

    @Override
    public CompletableFuture<List<RedisNodeDescription>> getNodesAsync() {

        logger.debug("lookup topology for masterId {}", masterId);

        return redisClient.connectSentinelAsync(StringCodec.UTF8, sentinelUri).thenCompose(this::getNodes);
    }

    protected CompletableFuture<List<RedisNodeDescription>> getNodes(
            StatefulRedisSentinelConnection<String, String> connection) {

        RedisSentinelAsyncCommands<String, String> async = connection.async();

        CompletableFuture<Map<String, String>> masterFuture = async.master(masterId).toCompletableFuture();
        CompletableFuture<List<Map<String, String>>> replicasFuture = async.replicas(masterId).toCompletableFuture();

        return masterFuture.thenCombine(replicasFuture, (master, replicas) -> {

            List<RedisNodeDescription> result = new ArrayList<>();

            result.add(toNode(master, RedisInstance.Role.UPSTREAM));
            result.addAll(replicas.stream().filter(SentinelTopologyProvider::isAvailable)
                    .map(map -> toNode(map, RedisInstance.Role.REPLICA)).collect(Collectors.toList()));

            return result;
        }).thenCompose(nodes -> ResumeAfter.close(connection).thenEmit(nodes))
                .whenComplete((nodes, t) -> {
                    if (t != null) {
                        connection.closeAsync();
                    }
                });
    }

    private static boolean isAvailable(Map<String, String> map) {

        String flags = map.get("flags");
        if (flags != null) {
            if (flags.contains("s_down") || flags.contains("o_down") || flags.contains("disconnected")) {
                return false;
            }
        }
        return true;
    }

    private RedisNodeDescription toNode(Map<String, String> map, RedisInstance.Role role) {

        String ip = map.get("ip");
        String port = map.get("port");
        return new RedisMasterReplicaNode(ip, Integer.parseInt(port), sentinelUri, role);
    }

}
