/*
 * Copyright (c) 2026-Present, Redis Ltd. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package io.lettuce.core.internal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks API elements that depend on Project Reactor and are therefore removed from the reactor-free {@code io.lettuce:lettuce}
 * distribution.
 * <p>
 * Modelled on Guava's {@code @GwtIncompatible}: the annotation declares intent, and the reactor-free build strips every
 * annotated element (and its javadoc) via {@code lettuce-build-tools}. Whole reactive packages and {@code *Reactive*} types are
 * removed by convention and do not need this marker; use it for the individual members of otherwise reactor-free classes that
 * reference reactive types (for example the {@code reactive()} accessors).
 * <p>
 * Retention is {@link RetentionPolicy#SOURCE}: the marker exists only to drive the source transformation and leaves no trace in
 * {@code lettuce-core}'s bytecode.
 *
 * @since 8.0
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR })
public @interface ReactorIncompatible {
}
