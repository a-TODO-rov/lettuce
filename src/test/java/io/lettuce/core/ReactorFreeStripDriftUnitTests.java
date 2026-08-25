/*
 * Copyright (c) 2026-Present, Redis Ltd. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package io.lettuce.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Guards the {@code io.lettuce:lettuce} reactor-free build (produced by {@code tools/reactor-free/strip.py}).
 * <p>
 * The strip removes the reactive source set (reactive packages, {@code *Reactive*} names, and a few reclassified helpers) and
 * patches a fixed, small set of "danglers" - non-reactive files that reference reactive types. This test asserts that the set
 * of danglers has not changed. If it fails, someone added reactor coupling to a non-reactive class; either move that code into
 * a reactive package ({@code .../reactive/...}), or register the file in {@code strip.py} and in {@link #KNOWN_DANGLERS}.
 * <p>
 * This is an early, friendly warning in the normal build; the reactor-free compile itself is the hard backstop.
 */
class ReactorFreeStripDriftUnitTests {

    /** Reactive imports: Project Reactor, Reactive Streams, or any Lettuce reactive type. */
    private static final Pattern REACTIVE_IMPORT = Pattern
            .compile("^\\s*import\\s+(reactor\\.|org\\.reactivestreams|io\\.lettuce\\.[\\w.]*[Rr]eactive).*");

    /** Reactive helpers that live outside a reactive package but are treated as reactive (removed wholesale by the strip). */
    private static final Set<String> RECLASSIFIED_REACTIVE = setOf("io/lettuce/core/ScanStream.java",
            "io/lettuce/core/RedisPublisher.java", "io/lettuce/core/Operators.java",
            "io/lettuce/core/RedisCredentialsProvider.java", "io/lettuce/core/AsyncCredentialsProviderAdapter.java");

    /**
     * Non-reactive files that legitimately reference reactive types and are patched by the strip. This set is the contract: it
     * must change in lockstep with {@code tools/reactor-free/strip.py}.
     */
    private static final Set<String> KNOWN_DANGLERS = setOf("io/lettuce/core/api/StatefulRedisConnection.java",
            "io/lettuce/core/cluster/ClusterScanSupport.java", "io/lettuce/core/cluster/NodeSelectionInvocationHandler.java",
            "io/lettuce/core/cluster/StatefulRedisClusterPubSubConnectionImpl.java",
            "io/lettuce/core/cluster/api/StatefulRedisClusterConnection.java",
            "io/lettuce/core/cluster/pubsub/StatefulRedisClusterPubSubConnection.java",
            "io/lettuce/core/dynamic/RedisCommandFactory.java",
            "io/lettuce/core/failover/StatefulRedisMultiDbConnectionImpl.java",
            "io/lettuce/core/failover/StatefulRedisMultiDbPubSubConnectionImpl.java",
            "io/lettuce/core/masterslave/MasterSlaveConnectionWrapper.java",
            "io/lettuce/core/pubsub/StatefulRedisPubSubConnection.java",
            "io/lettuce/core/pubsub/StatefulRedisPubSubConnectionImpl.java",
            "io/lettuce/core/sentinel/api/StatefulRedisSentinelConnection.java");

    @Test
    void danglerSetMatchesTheStripContract() {

        Path srcRoot = Paths.get("src/main/java");
        assertThat(Files.isDirectory(srcRoot)).as("run from the module root; src/main/java must exist").isTrue();

        Set<String> danglers = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(srcRoot)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String rel = srcRoot.relativize(p).toString().replace('\\', '/');
                if (isReactiveFile(rel)) {
                    return;
                }
                if (referencesReactive(p)) {
                    danglers.add(rel);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(danglers)
                .as("Reactor coupling in non-reactive classes changed. Either move the reactive code into a */reactive/* "
                        + "package, or update tools/reactor-free/strip.py AND KNOWN_DANGLERS in this test.")
                .isEqualTo(new TreeSet<>(KNOWN_DANGLERS));
    }

    private static boolean isReactiveFile(String rel) {
        return rel.contains("/reactive/") || rel.substring(rel.lastIndexOf('/') + 1).contains("Reactive")
                || RECLASSIFIED_REACTIVE.contains(rel);
    }

    private static boolean referencesReactive(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            return lines.stream().anyMatch(l -> REACTIVE_IMPORT.matcher(l).matches());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

}
