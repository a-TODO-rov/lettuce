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
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * Two-mode tool for the reactor-free Lettuce distribution.
 * <ul>
 * <li><b>annotate</b> (one-time / drift check): finds members whose signature references a reactive type and inserts the
 * {@code @ReactorIncompatible} marker + import into the real source. This is how the markers get placed; afterwards the source
 * is self-documenting and maintainers keep the markers by hand.</li>
 * <li><b>strip</b> (build-time, the default): over a COPY of {@code src/main/java}, deletes reactive files by convention,
 * deletes {@code @ReactorIncompatible} members/types (with their javadoc), removes statements that reference removed symbols,
 * cleans imports, and scrubs dangling {@code {@link}}s. It performs NO type inference - removal is purely marker/convention
 * driven.</li>
 * </ul>
 * Usage: {@code ReactorFreeTransformer [annotate] <root>} (no mode = strip).
 */
public final class ReactorFreeTransformer {

    private static final String MARKER = "ReactorIncompatible";

    private static final String MARKER_FQN = "io.lettuce.core.internal.ReactorIncompatible";

    /** Reactor / Reactive Streams publisher types - the only inference input, and only for the annotate pass + statements. */
    private static final Set<String> REACTOR_TYPES = Set.of("Mono", "Flux", "Publisher", "ConnectableFlux", "ParallelFlux",
            "GroupedFlux");

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
                continue; // whole file removed by convention; no marker needed
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

    // ---- strip: annotation + convention driven removal (no inference) ----
    private static void strip(Path root) throws IOException {
        List<Path> files = javaFiles(root);

        // Phase A: delete reactive files by convention or type-level marker; record their type names.
        // `typeRefs` (reactive TYPE names) is what statement removal keys on - never method names, which collide
        // across classes (e.g. RedisURI's removed reactive getCredentialsProvider vs ConnectionState's reactor-free one).
        Set<String> typeRefs = new TreeSet<>(REACTOR_TYPES);
        Set<String> linkTargets = new TreeSet<>();
        List<Path> toDelete = new ArrayList<>();
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            String typeName = f.getFileName().toString().replace(".java", "");
            boolean remove = isConventionReactive(rel, typeName);
            if (!remove) {
                remove = StaticJavaParser.parse(f).getTypes().stream().anyMatch(t -> t.getAnnotationByName(MARKER).isPresent());
            }
            if (remove) {
                toDelete.add(f);
                typeRefs.add(typeName);
                linkTargets.add(typeName);
            }
        }
        for (Path f : toDelete) {
            Files.delete(f);
        }
        List<Path> retained = files.stream().filter(f -> !toDelete.contains(f)).collect(Collectors.toList());

        // Pre-scan: collect removed member names for LINK scrubbing only (safe - it edits javadoc, not code).
        for (Path f : retained) {
            for (BodyDeclaration<?> m : StaticJavaParser.parse(f).findAll(BodyDeclaration.class)) {
                if (m.getAnnotationByName(MARKER).isPresent()) {
                    linkTargets.addAll(memberNames(m));
                }
            }
        }

        // Edit pass: remove annotated members, statements referencing a reactive type or a this-file removed field,
        // and reactive imports.
        for (Path f : retained) {
            CompilationUnit cu = StaticJavaParser.parse(f);
            LexicalPreservingPrinter.setup(cu);
            boolean changed = false;
            Set<String> stmtRefs = new LinkedHashSet<>(typeRefs);
            for (BodyDeclaration<?> m : new ArrayList<>(cu.findAll(BodyDeclaration.class))) {
                if (m.getAnnotationByName(MARKER).isPresent()) {
                    if (m instanceof FieldDeclaration fd) {
                        fd.getVariables().forEach(v -> stmtRefs.add(v.getNameAsString()));
                    }
                    m.getComment().ifPresent(Comment::remove);
                    m.remove();
                    changed = true;
                }
            }
            for (IfStmt st : new ArrayList<>(cu.findAll(IfStmt.class))) {
                if (st.getParentNode().isPresent() && references(st, stmtRefs)) {
                    st.remove();
                    changed = true;
                }
            }
            for (ExpressionStmt st : new ArrayList<>(cu.findAll(ExpressionStmt.class))) {
                if (st.getParentNode().isEmpty()) {
                    continue;
                }
                if (st.getExpression() instanceof AssignExpr assign && references(assign.getValue(), stmtRefs)) {
                    // assignment to a (retained) field from a reactive value -> default it, so final fields stay initialized
                    String target = assign.getTarget().toString();
                    String field = target.contains(".") ? target.substring(target.lastIndexOf('.') + 1) : target;
                    String type = fieldType(cu, field);
                    if (type != null) {
                        assign.setValue(defaultFor(type));
                    } else {
                        st.remove();
                    }
                    changed = true;
                } else if (references(st, stmtRefs)) {
                    st.remove();
                    changed = true;
                }
            }
            changed |= cu.getImports().removeIf(i -> REACTIVE_IMPORT.matcher(i.getNameAsString()).matches());
            if (changed) {
                Files.writeString(f, LexicalPreservingPrinter.print(cu));
            }
        }

        // Link-scrub pass.
        int scrubbed = 0;
        for (Path f : retained) {
            String src = Files.readString(f);
            String out = scrubLinks(src, linkTargets);
            if (!out.equals(src)) {
                Files.writeString(f, out);
                scrubbed++;
            }
        }
        System.out.printf("[reactor-free strip] deleted %d files, reactive-type refs %d, link-scrubbed %d files%n",
                toDelete.size(), typeRefs.size(), scrubbed);
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
        reactive.addAll(RECLASSIFIED_REACTIVE); // e.g. RedisCredentialsProvider - reactive but not "*Reactive*"-named
        // a type is reactive if it is a reactor publisher type, a reclassified reactive helper, or a *Reactive* API type
        return types.stream().anyMatch(t -> t.findAll(SimpleName.class).stream()
                .anyMatch(s -> reactive.contains(s.getIdentifier()) || s.getIdentifier().contains("Reactive")));
    }

    private static boolean references(Node n, Set<String> names) {
        return n.findAll(SimpleName.class).stream().anyMatch(s -> names.contains(s.getIdentifier()));
    }

    private static String fieldType(CompilationUnit cu, String name) {
        return cu.findAll(FieldDeclaration.class).stream().flatMap(fd -> fd.getVariables().stream())
                .filter(v -> v.getNameAsString().equals(name)).map(v -> v.getType().asString()).findFirst().orElse(null);
    }

    private static Expression defaultFor(String type) {
        switch (type) {
            case "boolean":
            case "Boolean":
                return new BooleanLiteralExpr(false);
            case "int":
            case "long":
            case "short":
            case "byte":
            case "char":
            case "double":
            case "float":
                return new IntegerLiteralExpr("0");
            default:
                return new NullLiteralExpr();
        }
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
