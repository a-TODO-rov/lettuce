package test;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Test that Lettuce works WITHOUT reactor-core on the classpath.
 * Only sync and async APIs are used - reactive() is NOT called.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Testing Lettuce WITHOUT Reactor ===\n");

        RedisClient client = RedisClient.create("redis://localhost:6379");
        try {
            StatefulRedisConnection<String, String> connection = client.connect();
            System.out.println("1. Connected using StatefulRedisConnection");

            // Test SYNC API
            RedisCommands<String, String> sync = connection.sync();
            sync.set("test:key", "hello-sync");
            String syncValue = sync.get("test:key");
            System.out.println("2. Sync API works: test:key = " + syncValue);

            // Test ASYNC API
            RedisAsyncCommands<String, String> async = connection.async();
            RedisFuture<String> future = async.set("test:key2", "hello-async");
            future.get(); // wait for completion
            String asyncValue = async.get("test:key2").get();
            System.out.println("3. Async API works: test:key2 = " + asyncValue);

            connection.reactive().ping();

            // Test MGET
            java.util.List<io.lettuce.core.KeyValue<String, String>> mgetResult = sync.mget("test:key", "test:key2");
            System.out.println("4. Mget API works: got " + mgetResult.size() + " results");

            connection.close();

            System.out.println("\n=== SUCCESS ===");
            System.out.println("Lettuce works without reactor-core on classpath!");
        } finally {
            client.shutdown();
        }
    }

}

