package com.stockflow.common.cache;

import org.springframework.cache.interceptor.KeyGenerator;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.StringJoiner;

/**
 * The default key generator, used when a {@code @Cacheable} does not specify {@code key}.
 *
 * <h2>Why Spring's default is not good enough</h2>
 *
 * <p>{@code SimpleKeyGenerator} builds a {@code SimpleKey} from the arguments and relies on their
 * {@code hashCode}. Two problems follow, and both are quiet:</p>
 *
 * <ul>
 *   <li><b>Keys are not stable across JVMs.</b> Anything whose {@code hashCode} is identity-based —
 *       an enum's default hash, a class without {@code equals}/{@code hashCode} — produces a
 *       different key on each instance. With a shared Redis that means every instance keeps its own
 *       copy under a different key, so the hit rate collapses and eviction by key misses most of
 *       them. Building the key from {@code toString()} instead makes it deterministic.</li>
 *   <li><b>Two methods on the same class collide.</b> {@code SimpleKey} does not include the method
 *       name, so {@code findById(uuid)} and {@code findArchivedById(uuid)} in one class map to the
 *       same key in the same cache, and one returns the other's result. Including the method name
 *       removes the whole category.</li>
 * </ul>
 *
 * <p>The generated key looks like {@code ProductQueryService.findBySku(SOFA-3S-GREY)} — long
 * enough to read in {@code redis-cli}, which pays for itself the first time someone has to work out
 * why a value is stale.</p>
 *
 * <p>For anything on a hot path, still pass an explicit {@code key = "#sku.code()"}: it is shorter
 * and it makes the cache key part of the method's contract rather than a consequence of its
 * signature.</p>
 */
public class StableKeyGenerator implements KeyGenerator {

    /**
     * Cut-off for a single argument's contribution.
     *
     * <p>Redis keys are capped at 512 MB, so length is not the concern — readability and memory in
     * the key space are. An argument that renders longer than this is almost certainly a collection
     * or an entity with a verbose {@code toString}, and hashing it keeps the key bounded while
     * staying deterministic.</p>
     */
    private static final int MAX_ARGUMENT_LENGTH = 64;

    @Override
    public Object generate(Object target, Method method, Object... params) {
        StringJoiner arguments = new StringJoiner(",");
        for (Object param : params) {
            arguments.add(render(param));
        }
        return method.getDeclaringClass().getSimpleName()
                + "." + method.getName()
                + "(" + arguments + ")";
    }

    private static String render(Object param) {
        if (param == null) {
            return "null";
        }
        String rendered = param.getClass().isArray()
                ? Arrays.deepToString(new Object[]{param})
                : param.toString();
        if (rendered.length() <= MAX_ARGUMENT_LENGTH) {
            return rendered;
        }
        // Deterministic across JVMs: String.hashCode is specified by the language, unlike
        // Object.hashCode. Truncating instead would risk two long arguments sharing a key.
        return rendered.substring(0, MAX_ARGUMENT_LENGTH) + "#" + rendered.hashCode();
    }
}
