/*
 * Copyright (c) 2026-Present, Redis Ltd. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package io.lettuce.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * Two-mode tool for the reactor-free Lettuce distribution.
 * <ul>
 * <li><b>annotate</b> (one-time / drift check): finds members whose signature references a reactive type and inserts the
 * {@code @ReactorIncompatible} marker. This is how the markers are placed; afterwards the source is self-documenting.</li>
 * <li><b>strip</b> (build-time, the default): over a COPY of {@code src/main/java}, deletes reactive files by convention or a
 * type-level marker, deletes {@code @ReactorIncompatible} members (with their javadoc), removes now-unused reactive imports,
 * and scrubs {@code {@link}}s to removed symbols. It is a pure declaration-level rewrite - <b>no statement analysis, no type
 * inference, no field defaulting</b>. Any reactor coupling inside a shared method body must be moved behind a reflective seam
 * in the source (so nothing names a reactive type in a body); the reactor-free compile is the backstop.</li>
 * </ul>
 * Usage: {@code ReactorFreeTransformer [annotate] <root>} (no mode = strip).
 */
public final class ReactorFreeTransformer {

    private static final String MARKER = "ReactorIncompatible";

    private static final String MARKER_FQN = "io.lettuce.core.internal.ReactorIncompatible";

    /** Reactor / Reactive Streams publisher types - inference input for the annotate pass only. */
    private static final Set<String> REACTOR_TYPES = Set.of("Mono", "Flux", "Publisher", "ConnectableFlux", "ParallelFlux",
            "GroupedFlux");

    /** Pure-reactive helpers that live outside a reactive package and are removed wholesale. */
    private static final Set<String> RECLASSIFIED_REACTIVE = Set.of("ScanStream", "RedisPublisher", "Operators",
            "RedisCredentialsProvider", "AsyncCredentialsProviderAdapter");

    private static final Pattern REACTIVE_IMPORT = Pattern
            .compile("^(reactor\\..*|org\\.reactivestreams.*|io\\.lettuce\\..*[Rr]eactive.*)$");

    public static void main(String[] args) throws IOException {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        if (args.length == 2 && "annotate".equals(args[0])) {
            annotate(Paths.get(args[1]));
        } else if (args.length == 1) {
            strip(Paths.get(args[0]));
        } else {
            System.err.println("usage: ReactorFreeTransformer [annotate] <root>");
            System.exit(2);
        }
    }

    // ---- annotate: place @ReactorIncompatible on signature-reactive members (one-time / drift check) ----
    private static void annotate(Path root) throws IOException {
        int annotated = 0;
        for (Path f : javaFiles(root)) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            if (isConventionReactive(rel, f.getFileName().toString().replace(".java", ""))) {
                continue;
            }
            CompilationUnit cu = StaticJavaParser.parse(f);
            LexicalPreservingPrinter.setup(cu);
            boolean changed = false;
            for (BodyDeclaration<?> m : cu.findAll(BodyDeclaration.class)) {
                if (!m.getAnnotationByName(MARKER).isPresent() && signatureIsReactive(m)) {
                    m.addMarkerAnnotation(MARKER);
                    changed = true;
                    annotated++;
                }
            }
            if (changed) {
                if (cu.getImports().stream().noneMatch(i -> i.getNameAsString().equals(MARKER_FQN))) {
                    cu.addImport(MARKER_FQN);
                }
                Files.writeString(f, LexicalPreservingPrinter.print(cu));
            }
        }
        System.out.printf("[reactor-free annotate] added %d @ReactorIncompatible markers%n", annotated);
    }

    // ---- strip: pure declaration-level removal (convention files + @ReactorIncompatible declarations) ----
    private static void strip(Path root) throws IOException {
        List<Path> files = javaFiles(root);

        // Phase A: delete reactive files (convention or type-level marker); record their names for link scrubbing.
        Set<String> linkTargets = new TreeSet<>();
        List<Path> toDelete = new ArrayList<>();
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            String typeName = f.getFileName().toString().replace(".java", "");
            boolean remove = isConventionReactive(rel, typeName)
                    || StaticJavaParser.parse(f).getTypes().stream().anyMatch(t -> t.getAnnotationByName(MARKER).isPresent());
            if (remove) {
                toDelete.add(f);
                linkTargets.add(typeName);
            }
        }
        for (Path f : toDelete) {
            Files.delete(f);
        }
        List<Path> retained = files.stream().filter(f -> !toDelete.contains(f)).collect(Collectors.toList());

        // Pre-scan: record removed member names, for link scrubbing only.
        for (Path f : retained) {
            for (BodyDeclaration<?> m : StaticJavaParser.parse(f).findAll(BodyDeclaration.class)) {
                if (m.getAnnotationByName(MARKER).isPresent()) {
                    linkTargets.addAll(memberNames(m));
                }
            }
        }

        // Edit pass: delete @ReactorIncompatible members (+ their javadoc) and now-unused reactive imports.
        for (Path f : retained) {
            CompilationUnit cu = StaticJavaParser.parse(f);
            LexicalPreservingPrinter.setup(cu);
            boolean changed = false;
            for (BodyDeclaration<?> m : new ArrayList<>(cu.findAll(BodyDeclaration.class))) {
                if (m.getAnnotationByName(MARKER).isPresent()) {
                    m.getComment().ifPresent(Comment::remove);
                    m.remove();
                    changed = true;
                }
            }
            changed |= cu.getImports().removeIf(i -> REACTIVE_IMPORT.matcher(i.getNameAsString()).matches());
            if (changed) {
                Files.writeString(f, LexicalPreservingPrinter.print(cu));
            }
        }

        // Link-scrub pass: {@link}/@see to removed symbols become {@code} / are dropped.
        int scrubbed = 0;
        for (Path f : retained) {
            String src = Files.readString(f);
            String out = scrubLinks(src, linkTargets);
            if (!out.equals(src)) {
                Files.writeString(f, out);
                scrubbed++;
            }
        }
        System.out.printf("[reactor-free strip] deleted %d files, link-scrubbed %d files%n", toDelete.size(), scrubbed);
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    private static boolean isConventionReactive(String rel, String typeName) {
        return rel.contains("/reactive/") || typeName.contains("Reactive") || RECLASSIFIED_REACTIVE.contains(typeName);
    }

    private static List<String> memberNames(BodyDeclaration<?> m) {
        if (m instanceof MethodDeclaration md) {
            return List.of(md.getNameAsString());
        }
        if (m instanceof ConstructorDeclaration cd) {
            return List.of(cd.getNameAsString());
        }
        if (m instanceof FieldDeclaration fd) {
            return fd.getVariables().stream().map(VariableDeclarator::getNameAsString).collect(Collectors.toList());
        }
        return List.of();
    }

    private static boolean signatureIsReactive(BodyDeclaration<?> m) {
        List<Type> types = new ArrayList<>();
        if (m instanceof MethodDeclaration md) {
            types.add(md.getType());
            md.getParameters().forEach(p -> types.add(p.getType()));
        } else if (m instanceof FieldDeclaration fd) {
            fd.getVariables().forEach(v -> types.add(v.getType()));
        } else if (m instanceof ConstructorDeclaration cd) {
            cd.getParameters().forEach(p -> types.add(p.getType()));
        } else {
            return false;
        }
        Set<String> reactive = new LinkedHashSet<>(REACTOR_TYPES);
        reactive.addAll(RECLASSIFIED_REACTIVE);
        return types.stream().anyMatch(t -> t.findAll(SimpleName.class).stream()
                .anyMatch(s -> reactive.contains(s.getIdentifier()) || s.getIdentifier().contains("Reactive")));
    }

    private static String scrubLinks(String src, Set<String> removed) {
        Pattern link = Pattern.compile("\\{@link(?:plain)?\\s+#?([A-Za-z_][A-Za-z0-9_]*)[^}]*}");
        Matcher m = link.matcher(src);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String sym = m.group(1);
            m.appendReplacement(sb, removed.contains(sym) ? Matcher.quoteReplacement("{@code " + sym + "}") : "$0");
        }
        m.appendTail(sb);
        String out = sb.toString();
        for (String sym : removed) {
            out = out.replaceAll("(?m)^\\s*\\*\\s*@see\\s+#?" + Pattern.quote(sym) + "\\b.*$\\n?", "");
        }
        return out;
    }

}
