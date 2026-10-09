package com.stockflow.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Rows of the demo seed ({@code db/demo}) that integration tests rely on.
 *
 * <p>Since V20260928004000 an order's customer id is a foreign key to {@code customer.customer}, so
 * a test that places an order needs a customer that exists; a random UUID is refused at insert.
 * The demo seed V20260928009000 creates this one for exactly that purpose.</p>
 *
 * <h2>The warehouse map</h2>
 *
 * <p>V20260928009000 §4 seeds warehouse {@code HCM}: map 60 x 40 m; zones A and B; shelves
 * {@code A01}, {@code A02} ({@code OVERSIZE}, zone A) and {@code B01} ({@code NORMAL}, zone B), each
 * 10 x 1.2 at (5, 5), (5, 9) and (5, 14) with levels 1-2 and bins {@code A}, {@code B} on every
 * level; storage areas {@code RCV01}, {@code QC01}, {@code PACK01}, {@code DSP01} and the
 * {@code NON_STORAGE} area {@code OFFICE}; a north wall and an east door.</p>
 *
 * <p>Its ids are {@code md5('demo:...')::uuid}, so they are known without asking the database:
 * {@link #demoId} computes the same value, and the helpers below spell the keys the seed uses.
 * Shelves and areas are named by their own code ({@code "A01"}, {@code "QC01"}); bins and
 * locations by their full location code ({@code "HCM-A01-2-B"}), since a bin's own code is unique
 * only on its level. A name of the wrong shape is refused rather than turned into the id of a row
 * that does not exist.</p>
 *
 * <p>A computed id cannot tell whether its row exists. {@code DemoDataIntegrationTest} checks every
 * seeded row against these helpers, so a seed change breaks there, by name, and not as a
 * {@code NOT_FOUND} deep inside some other test.</p>
 */
public final class DemoData {

    /** The demo customer, created by V20260928009000__demo_master_data.sql. */
    public static final UUID CUSTOMER_ID = UUID.fromString("c0000000-0000-4000-8000-000000000001");

    /** Warehouse {@code HCM}, the only warehouse with a map. */
    public static final UUID WAREHOUSE_HCM = demoId("demo:wh:HCM");
    /** Zone A (sofas): shelves A01 and A02. */
    public static final UUID ZONE_HCM_A = demoId("demo:zone:HCM:A");
    /** Zone B (tables): shelf B01. */
    public static final UUID ZONE_HCM_B = demoId("demo:zone:HCM:B");
    /** The north wall, (0, 0)-(60, 0). */
    public static final UUID WALL_HCM_NORTH = demoId("demo:wall:HCM-N");
    /** The east door, (60, 2)-(60, 8), {@code OPEN}. */
    public static final UUID DOOR_HCM_EAST = demoId("demo:door:HCM-E");

    private DemoData() {
    }

    /** A shelf of {@code HCM} by its code: {@code A01}, {@code A02} or {@code B01}. */
    public static UUID shelf(String code) {
        return demoId("demo:shelf:HCM-" + ownCode(code));
    }

    /** A level of a shelf of {@code HCM}, by the shelf's code and the level's index: 1 or 2. */
    public static UUID level(String shelfCode, int levelIndex) {
        return demoId("demo:level:HCM-" + ownCode(shelfCode) + "-" + levelIndex);
    }

    /** A bin of {@code HCM} by its full location code: {@code bin("HCM-A01-2-B")}. */
    public static UUID bin(String locationCode) {
        return demoId("demo:bin:" + locationCode(locationCode));
    }

    /**
     * An area of {@code HCM} by its code: {@code RCV01}, {@code QC01}, {@code PACK01}, {@code DSP01}
     * or {@code OFFICE}.
     */
    public static UUID area(String code) {
        return demoId("demo:area:HCM-" + ownCode(code));
    }

    /**
     * A storage location by its full code: {@code HCM-A01-2-B} for a bin, {@code HCM-QC01} for an
     * area. {@code OFFICE} has none.
     */
    public static UUID location(String locationCode) {
        return demoId("demo:loc:" + locationCode(locationCode));
    }

    /** A shelf's or an area's own code: no prefix, no dash. */
    private static String ownCode(String code) {
        if (!code.matches("[A-Z0-9]+")) {
            throw new IllegalArgumentException("Expected a shelf or area code such as A01 or QC01, got " + code);
        }
        return code;
    }

    /** A full location code of {@code HCM}: {@code HCM-A01-2-B} or {@code HCM-QC01}. */
    private static String locationCode(String code) {
        if (!code.matches("HCM(-[A-Z0-9]+)+")) {
            throw new IllegalArgumentException("Expected a full location code such as HCM-A01-2-B, got " + code);
        }
        return code;
    }

    /**
     * What Postgres computes for {@code md5(key)::uuid}: the 16 bytes of the MD5 of the UTF-8 text,
     * read straight as a UUID.
     *
     * <p>Not {@link UUID#nameUUIDFromBytes}, which hashes the same way but then overwrites the
     * version and variant bits - the result is a valid version-3 UUID and a different id.</p>
     */
    public static UUID demoId(String key) {
        try {
            ByteBuffer digest = ByteBuffer.wrap(MessageDigest.getInstance("MD5")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
            return new UUID(digest.getLong(), digest.getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JVM provides MD5", e);
        }
    }
}
