package io.lettuce.poc;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * PoC: Execute SET command using only sync API - NO Reactor on classpath!
 * This demonstrates that Reactor is not needed for sync/async operations.
 */
public class NoReactorPocTest {
    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("PoC: Running SET/GET with sync API (NO REACTOR)");
        System.out.println("==============================================");
        
        // Verify Reactor is NOT on classpath
        try {
            Class.forName("reactor.core.publisher.Mono");
            System.out.println("ERROR: Reactor IS on classpath - PoC failed!");
            System.exit(1);
        } catch (ClassNotFoundException e) {
            System.out.println("VERIFIED: Reactor is NOT on classpath");
        }
        
        RedisClient client = RedisClient.create("redis://localhost:16379");
        
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            System.out.println("Connected to Redis!");
            
            // Use SYNC API - reactive() is NEVER called
            String result = connection.sync().set("poc-key", "poc-value");
            System.out.println("SET result: " + result);
            
            String value = connection.sync().get("poc-key");
            System.out.println("GET result: " + value);
            
            // Cleanup
            connection.sync().del("poc-key");
            
            System.out.println("==============================================");
            System.out.println("SUCCESS: sync SET/GET works WITHOUT Reactor!");
            System.out.println("==============================================");
        } finally {
            client.shutdown();
        }
    }
}
