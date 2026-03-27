package test;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.tracing.BraveTracing;

/**
 * Test that Lettuce works with BraveTracing but WITHOUT reactor-core on the classpath.
 * JVM lazy classloading means Mono (referenced in Tracing interface) is never loaded.
 * GraalVM closed-world analysis will fail because it tries to resolve Mono at build time.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Testing Lettuce with BraveTracing, WITHOUT Reactor ===\n");

        // Create Brave tracing infrastructure
        brave.Tracing braveTracing = brave.Tracing.newBuilder()
                .localServiceName("test-app")
                .build();

        // Wrap in Lettuce's BraveTracing adapter
        BraveTracing lettuceTracing = BraveTracing.create(braveTracing);
        System.out.println("1. BraveTracing created (no Reactor needed so far)");

        // Wire tracing into ClientResources
        DefaultClientResources resources = DefaultClientResources.builder()
                .tracing(lettuceTracing)
                .build();
        System.out.println("2. ClientResources built with tracing enabled");

        RedisClient client = RedisClient.create(resources, "redis://localhost:6379");
        try {
            StatefulRedisConnection<String, String> connection = client.connect();
            System.out.println("3. Connected using StatefulRedisConnection");

            // Test SYNC API with tracing
            RedisCommands<String, String> sync = connection.sync();
            sync.set("test:key", "hello-traced");
            String syncValue = sync.get("test:key");
            System.out.println("4. Sync API works: test:key = " + syncValue);

            // Test ASYNC API with tracing
            RedisAsyncCommands<String, String> async = connection.async();
            RedisFuture<String> future = async.set("test:key2", "hello-async-traced");
            future.get();
            String asyncValue = async.get("test:key2").get();
            System.out.println("5. Async API works: test:key2 = " + asyncValue);

            connection.close();

            System.out.println("\n=== SUCCESS ===");
            System.out.println("Lettuce with BraveTracing works without reactor-core!");
        } finally {
            client.shutdown();
        }
    }

}

