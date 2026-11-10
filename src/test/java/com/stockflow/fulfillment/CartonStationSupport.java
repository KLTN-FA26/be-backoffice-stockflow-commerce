package com.stockflow.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What an integration test does at the carton check station (team plan 4C-6, kltn-docs 08 §4) to turn a picked task into
 * a shipment READY_TO_DISPATCH: start the check, scan the LPN of every carton the task picked, label them and complete.
 * It replaces the repack station's "open a package, scan the SKU, seal it" that every dispatch test used before.
 *
 * <p>The demo carton units declare no size on purpose (V20261111000100), and a label is refused without one (08 BR-07),
 * so {@link #giveCartonsASize} sets it first.</p>
 */
final class CartonStationSupport {

    /** Sends a request as the worker of the test (each test class has its own token handling). */
    @FunctionalInterface
    interface Worker {
        ResultActions call(MockHttpServletRequestBuilder request) throws Exception;
    }

    static final String BASE = "/api/v1/fulfillment";

    private CartonStationSupport() { }

    /**
     * The size and full weight the carton spec of the demo SKUs declares: a sofa carton as the repack tests measured
     * its box (215 x 95 x 90 cm, 52.5 kg), a table carton a flat one.
     */
    static void giveCartonsASize(TransactionTemplate tx, JdbcTemplate jdbc) {
        tx.executeWithoutResult(s -> {
            size(jdbc, "SOFA-3S-GREY", "215", "95", "90", "52.5");
            size(jdbc, "TABLE-OAK-160", "165", "95", "20", "38");
        });
    }

    /**
     * A heavy sofa carton (500 kg): a 1.25 t truck carries two of them, so a shipment of four needs two trucks with two
     * cartons each, as the two-part kits of the old repack station did.
     */
    static void giveSofaCartonsAWeight(TransactionTemplate tx, JdbcTemplate jdbc, String kg) {
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE inventory.uom_conversions u SET weight_kg = ?::numeric
                  FROM inventory.inventory_items i
                 WHERE i.id = u.inventory_item_id AND i.sku = 'SOFA-3S-GREY' AND NOT u.is_base AND u.active""", kg));
    }

    /** The carton spec of the SKU declares no size again, as the demo data has it. */
    static void clearCartonSize(TransactionTemplate tx, JdbcTemplate jdbc, String sku) {
        tx.executeWithoutResult(s -> jdbc.update("""
                UPDATE inventory.uom_conversions u SET length_cm = NULL, width_cm = NULL, height_cm = NULL, weight_kg = NULL
                  FROM inventory.inventory_items i
                 WHERE i.id = u.inventory_item_id AND i.sku = ? AND NOT u.is_base AND u.active""", sku));
    }

    /** The manager corrects the SKU's carton spec (08 edge case: the real carton differs from the declared one). */
    static void correctCartonSize(TransactionTemplate tx, JdbcTemplate jdbc, String sku, String length, String width,
                                  String height, String weight) {
        tx.executeWithoutResult(s -> size(jdbc, sku, length, width, height, weight));
    }

    private static void size(JdbcTemplate jdbc, String sku, String length, String width, String height, String weight) {
        jdbc.update("""
                UPDATE inventory.uom_conversions u SET length_cm = ?::numeric, width_cm = ?::numeric,
                       height_cm = ?::numeric, weight_kg = ?::numeric
                  FROM inventory.inventory_items i
                 WHERE i.id = u.inventory_item_id AND i.sku = ? AND NOT u.is_base AND u.active""",
                length, width, height, weight, sku);
    }

    /** The LPNs the task picked, in walking order. */
    static List<String> pickedLpns(JdbcTemplate jdbc, UUID task) {
        return jdbc.queryForList("SELECT lpn FROM fulfillment.pick_line WHERE pick_id = ? AND status = 'PICKED' "
                + "ORDER BY sequence_no, id", String.class, task);
    }

    static ResultActions post(Worker worker, String url, String body) throws Exception {
        return worker.call(body == null ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                : org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                .contentType("application/json").content(body));
    }

    /** Starts the check and scans every picked carton of the task: all of them are CHECKED. */
    static void startAndScanAll(Worker worker, JdbcTemplate jdbc, UUID task) throws Exception {
        post(worker, BASE + "/tasks/" + task + "/packing-start", null).andExpect(status().isOk());
        for (String lpn : pickedLpns(jdbc, task)) {
            post(worker, BASE + "/tasks/" + task + "/carton-checks", "{\"lpn\":\"" + lpn + "\"}")
                    .andExpect(status().isOk());
        }
    }

    /** Labels every CHECKED carton of the task. */
    static void labelAll(Worker worker, UUID task) throws Exception {
        post(worker, BASE + "/tasks/" + task + "/carton-labels", null).andExpect(status().isOk());
    }

    /**
     * The whole station for a picked task: start, scan, label, complete with {@code completionBody} (null for a
     * complete order). Returns the completion's answer.
     */
    static JsonNode complete(Worker worker, JdbcTemplate jdbc, ObjectMapper json, UUID task, String completionBody)
            throws Exception {
        startAndScanAll(worker, jdbc, task);
        labelAll(worker, task);
        return completeOnly(worker, json, task, completionBody);
    }

    /** Completes a task whose cartons are all labelled or removed already. */
    static JsonNode completeOnly(Worker worker, ObjectMapper json, UUID task, String completionBody) throws Exception {
        String reply = post(worker, BASE + "/tasks/" + task + "/packing-completion", completionBody == null ? "{}"
                : completionBody).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(reply).path("data");
    }

    /** {@link #complete} and the id of the shipment it made. */
    static UUID ship(Worker worker, JdbcTemplate jdbc, ObjectMapper json, UUID task, String completionBody)
            throws Exception {
        return UUID.fromString(complete(worker, jdbc, json, task, completionBody).path("shipmentId").asText());
    }

    /**
     * Several shipments of one order, which the old repack station made one completion at a time. At the check station
     * every carton the task picked is labelled before it completes and one completion ships them all, so a test that
     * needs the order to leave in parts completes once and lets this hand the cartons out to shipments of its own - the
     * way a delivery batch (team plan 4B-8) will - by the plain rows a completion writes: a shipment READY_TO_DISPATCH
     * and the {@code shipment_id} of its cartons.
     *
     * <p>{@link #ship} takes the next {@code cartons} cartons of the task. The first call runs the station for the
     * whole task and keeps that shipment for its own cartons; the cartons left over wait without a shipment until a
     * later call claims them. Every shipment but the last is partial, with a note.</p>
     */
    static final class Parts {

        private final Worker worker;
        private final TransactionTemplate tx;
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;
        private final java.util.Map<UUID, Integer> shipped = new java.util.HashMap<>();
        private final java.util.Map<UUID, UUID> first = new java.util.HashMap<>();

        Parts(Worker worker, TransactionTemplate tx, JdbcTemplate jdbc, ObjectMapper json) {
            this.worker = worker;
            this.tx = tx;
            this.jdbc = jdbc;
            this.json = json;
        }

        /** The next shipment of the task: {@code cartons} of its cartons, partial when some are left for later. */
        UUID ship(UUID task, int cartons) throws Exception {
            boolean firstCall = !first.containsKey(task);
            if (firstCall) {
                first.put(task, CartonStationSupport.ship(worker, jdbc, json, task, null));
            }
            UUID whole = first.get(task);
            List<UUID> all = jdbc.queryForList("SELECT p.id FROM fulfillment.package p JOIN fulfillment.pack k ON k.id = p.pack_id "
                    + "WHERE k.pick_id = ? AND p.status = 'LABELED' ORDER BY p.package_no", UUID.class, task);
            int done = shipped.getOrDefault(task, 0);
            List<UUID> mine = all.subList(done, Math.min(all.size(), done + cartons));
            if (mine.isEmpty()) {
                throw new IllegalStateException("The task has no carton left to ship");
            }
            boolean more = done + mine.size() < all.size();
            UUID shipment = firstCall ? whole : UUID.randomUUID();
            tx.executeWithoutResult(t -> {
            if (firstCall) {
                jdbc.update("UPDATE fulfillment.package SET shipment_id = NULL WHERE shipment_id = ?", whole);
            } else {
                jdbc.update("""
                        INSERT INTO fulfillment.shipment (id, pack_id, order_id, status, code, warehouse_id, ready_at, ready_by,
                                                          package_count, created_at)
                        SELECT ?, pack_id, order_id, 'READY_TO_DISPATCH', substr(code, 1, length(code) - 1) || ?,
                               warehouse_id, NOW(), ready_by, 1, NOW()
                          FROM fulfillment.shipment WHERE id = ?""", shipment, String.valueOf(done + 1), whole);
                jdbc.update("""
                        INSERT INTO fulfillment.shipment_event (id, shipment_id, status, occurred_at, source)
                        VALUES (gen_random_uuid(), ?, 'READY_TO_DISPATCH', NOW(), 'MANUAL')""", shipment);
            }
            for (UUID carton : mine) {
                jdbc.update("UPDATE fulfillment.package SET shipment_id = ? WHERE id = ?", shipment, carton);
            }
            jdbc.update("UPDATE fulfillment.shipment SET package_count = ?, partial = ?, partial_note = ? WHERE id = ?",
                    mine.size(), more, more ? "part of the order (test)" : null, shipment);
            });
            shipped.put(task, done + mine.size());
            return shipment;
        }
    }
}
