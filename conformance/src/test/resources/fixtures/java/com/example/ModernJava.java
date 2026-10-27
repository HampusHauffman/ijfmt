package com.example;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static java.util.Objects.requireNonNull;
import javax.annotation.processing.Generated;

/**
 * Exercises a broad slice of the Java formatter: generics, annotations, lambdas, switch expressions, records,
 * sealed hierarchies, text blocks, try-with-resources, labeled loops and javadoc.
 * @param <K> key type
 * @param <V> value type
 * @author someone
 */
@Generated(value = "fixture", date = "2026-01-01", comments = "a very long annotation argument to force wrapping in narrow styles")
@SuppressWarnings({"unchecked","rawtypes"})
public final class ModernJava<K extends Comparable<? super K>, V> implements Iterable<Map.Entry<K, V>>, AutoCloseable {
    private static final int[] PRIMES = {2,3,5,7,11,13,17,19,23,29,31,37,41,43,47,53,59,61,67,71,73,79,83,89,97,101,103};
    private final Map<K, List<V>> index = new TreeMap<>();
    private volatile boolean closed;
    @Deprecated(since = "1.0", forRemoval = true) protected transient int legacyCounter;

    sealed interface Shape permits Circle, Square, Rectangle {}
    record Circle(double radius) implements Shape {}
    record Square(double side) implements Shape { Square { if (side < 0) throw new IllegalArgumentException("negative side: " + side); } }
    record Rectangle(double width, double height, String label, Map<String, List<Integer>> metadata, Optional<String> description) implements Shape {}

    enum Color { RED("r"), GREEN("g"), BLUE("b"); private final String code; Color(String code) { this.code = code; } String code() { return code; } }

    static double area(Shape shape) {
        return switch (shape) {
            case Circle c -> Math.PI * c.radius() * c.radius();
            case Square s -> s.side() * s.side();
            case Rectangle(double w, double h, var label, var meta, var desc) when w > 0 && h > 0 -> w * h;
            case Rectangle r -> 0;
        };
    }

    public ModernJava<K, V> put(K key, V value) { index.computeIfAbsent(requireNonNull(key, "key"), k -> new ArrayList<>()).add(value); return this; }

    public Map<K, Integer> sizes() {
        return index.entrySet().stream().filter(e -> !e.getValue().isEmpty()).sorted(Map.Entry.comparingByKey()).collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size(), (a, b) -> a + b, LinkedHashMap::new));
    }

    String describe(Object o) {
        if (o instanceof String s && !s.isBlank()) { return "string of length " + s.length(); }
        else if (o instanceof Integer i) return "int " + i;
        String kind = o == null ? "null" : o.getClass().isArray() ? "array" : o instanceof Collection<?> c ? "collection of " + c.size() + " elements" : "object";
        return kind;
    }

    int legacySwitch(int day) {
        int result;
        switch (day) {
            case 1: case 7: result = 0; break;
            case 2:
            case 3: { result = 1; break; }
            default: result = -1;
        }
        outer:
        for (int i = 0; i < 10; i++) { for (int j = 0; j < 10; j++) { if (i * j > day) break outer; if (j % 2 == 0) continue outer; } }
        do { result++; } while (result < 0);
        return result;
    }

    String json() {
        return """
            {
              "name": "fixture",
              "values": [1, 2, 3]
            }
            """;
    }

    void io(java.nio.file.Path path) throws java.io.IOException {
        try (var in = java.nio.file.Files.newInputStream(path); var out = new java.io.ByteArrayOutputStream()) {
            in.transferTo(out);
        } catch (java.io.UncheckedIOException | IllegalStateException e) {
            throw new java.io.IOException("failed to copy " + path, e);
        } finally {
            legacyCounter++;
        }
    }

    <R> List<R> mapAll(Function<? super V, ? extends R> mapper) {
        List<R> out = new ArrayList<>();
        index.values().forEach(list -> list.forEach(v -> { R r = mapper.apply(v); if (r != null) out.add(r); }));
        Runnable noop = () -> {};
        Comparator<String> byLength = Comparator.comparingInt(String::length).thenComparing(Comparator.reverseOrder());
        return out;
    }

    // @formatter:off
    int[][] matrix = {
        {1, 0, 0},
        {0, 1, 0},
        {0, 0, 1}
    };
    // @formatter:on

    @Override public Iterator<Map.Entry<K, V>> iterator() {
        return new Iterator<>() {
            private final Iterator<Map.Entry<K, List<V>>> it = index.entrySet().iterator();
            @Override public boolean hasNext() { return it.hasNext(); }
            @Override public Map.Entry<K, V> next() { var e = it.next(); return Map.entry(e.getKey(), e.getValue().get(0)); }
        };
    }

    @Override public void close() { closed = true; }

    static { assert PRIMES.length > 0 : "primes"; }
}
