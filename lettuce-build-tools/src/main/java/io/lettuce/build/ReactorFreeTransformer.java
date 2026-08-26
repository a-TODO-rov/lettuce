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
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;

/**
 * Strips reactive code out of a COPY of {@code src/main/java} to produce the reactor-free Lettuce distribution. Modelled on
 * Guava's {@code @GwtIncompatible}: reactive code is identified by exactly two <b>explicit</b> signals declared in the source
 * itself - it lives in a {@code .../reactive/} package, or a type/member carries {@code @ReactorIncompatible}. Nothing is
 * matched by name or guessed from reactive types.
 * <p>
 * The strip:
 * <ul>
 * <li>deletes a file if it lives in a reactive package or its type is {@code @ReactorIncompatible};</li>
 * <li>deletes a {@code @ReactorIncompatible} member (with its javadoc);</li>
 * <li>removes an import that can no longer resolve - the {@code reactor} / {@code org.reactivestreams} dependencies, or a
 * lettuce type we just deleted;</li>
 * <li>scrubs {@code {@link}}s / {@code @see}s that point at removed symbols.</li>
 * </ul>
 * It is a pure declaration-level rewrite - <b>no statement analysis, no type inference</b>. Reactor coupling inside a shared
 * method body must be moved behind a reflective seam in the source; the reactor-free compile is the backstop that fails the
 * build if any reactive reference was left unmarked.
 * <p>
 * Usage: {@code ReactorFreeTransformer <root>}.
 */
public final class ReactorFreeTransformer {

    private static final String MARKER = "ReactorIncompatible";

    /** Import prefixes that cannot resolve once reactor is off the classpath - removed wholesale. */
    private static final List<String> DEAD_IMPORT_PREFIXES = List.of("reactor.", "org.reactivestreams.");

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: ReactorFreeTransformer <root>");
            System.exit(2);
        }
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        strip(Paths.get(args[0]));
    }

    private static void strip(Path root) throws IOException {
        List<Path> files = javaFiles(root);

        // Phase A: delete reactive files (reactive package or type-level marker); record their type names.
        Set<String> deletedTypes = new TreeSet<>();
        List<Path> toDelete = new ArrayList<>();
        for (Path f : files) {
            String rel = root.relativize(f).toString().replace('\\', '/');
            boolean remove = inReactivePackage(rel)
                    || StaticJavaParser.parse(f).getTypes().stream().anyMatch(t -> t.getAnnotationByName(MARKER).isPresent());
            if (remove) {
                toDelete.add(f);
                deletedTypes.add(f.getFileName().toString().replace(".java", ""));
            }
        }
        for (Path f : toDelete) {
            Files.delete(f);
        }
        List<Path> retained = files.stream().filter(f -> !toDelete.contains(f)).collect(Collectors.toList());

        // Pre-scan: names of members we are about to delete - for link scrubbing only.
        Set<String> linkTargets = new TreeSet<>(deletedTypes);
        for (Path f : retained) {
            for (BodyDeclaration<?> m : StaticJavaParser.parse(f).findAll(BodyDeclaration.class)) {
                if (m.getAnnotationByName(MARKER).isPresent()) {
                    linkTargets.addAll(memberNames(m));
                }
            }
        }

        // Edit pass: delete @ReactorIncompatible members (+ their javadoc) and imports that can no longer resolve.
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
            changed |= cu.getImports().removeIf(i -> isDeadImport(i.getNameAsString(), deletedTypes));
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

    /** An import is dead if its dependency is gone (reactor / reactive-streams) or it names a lettuce type we deleted. */
    private static boolean isDeadImport(String name, Set<String> deletedTypes) {
        if (DEAD_IMPORT_PREFIXES.stream().anyMatch(name::startsWith)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return deletedTypes.contains(dot < 0 ? name : name.substring(dot + 1));
    }

    private static boolean inReactivePackage(String rel) {
        return rel.contains("/reactive/");
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
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
