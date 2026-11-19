package com.stockflow.fulfillment.internal.service;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The orders of the fake Ahamove, in memory (a restart forgets them - the fake is for a demo session, not for data).
 *
 * <p>Refuses to exist under the {@code prod} profile: the fake would answer a real carrier's booking with a made-up
 * order.</p>
 */
@Component
@FakeAhamoveOnly
class FakeAhamoveOrders {

    /** One point of the route: path[0] the pickup, path[1..] the drop-offs. */
    static final class Point {
        final String address;
        final String name;
        final String phone;
        final String trackingNumber;
        String status = "";
        Instant completeTime;
        Instant failTime;
        String failComment;
        Instant returnTime;

        Point(String address, String name, String phone, String trackingNumber) {
            this.address = address;
            this.name = name;
            this.phone = phone;
            this.trackingNumber = trackingNumber;
        }
    }

    /** An order as Ahamove keeps it. Mutated only while holding the instance's lock. */
    static final class Order {
        final String id;
        final UUID carrierId;
        final String serviceCode;
        final String remarks;
        final Instant createdAt;
        final Instant pickupAt;
        final List<Point> route = new ArrayList<>();
        String status = "ASSIGNING";
        String subStatus;
        boolean cancelByUser;
        String cancelComment;
        String driverName;
        String driverPhone;
        String vehiclePlate;
        Instant acceptTime;
        Instant pickupTime;
        Instant completeTime;
        Instant cancelTime;
        /** The time of the latest report: the next is later, so the system never reads one as outdated. */
        Instant last;

        Order(String id, UUID carrierId, String serviceCode, String remarks, Instant createdAt, Instant pickupAt) {
            this.id = id;
            this.carrierId = carrierId;
            this.serviceCode = serviceCode;
            this.remarks = remarks;
            this.createdAt = createdAt;
            this.pickupAt = pickupAt;
            this.last = createdAt;
        }
    }

    static final int LISTED = 50;

    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    private final Clock clock;

    FakeAhamoveOrders(Environment environment, Clock clock) {
        this.clock = clock;
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("stockflow.fulfillment.ahamove.mode=fake is not allowed under the prod "
                    + "profile: it would answer a carrier booking with an order that does not exist");
        }
    }

    /**
     * The time of the next report about {@code order}: now, but never earlier than the one before it (a report older
     * than one already applied is dropped as outdated). Call while holding the order's lock.
     */
    Instant next(Order order) {
        Instant now = clock.instant();
        Instant at = now.isAfter(order.last) ? now : order.last.plusMillis(1);
        order.last = at;
        return at;
    }

    void put(Order order) {
        orders.put(order.id, order);
    }

    Order get(String id) {
        return id == null ? null : orders.get(id);
    }

    /** Newest first, at most {@value #LISTED}: a demo holds a handful. */
    List<Order> all() {
        return orders.values().stream().sorted((a, b) -> b.createdAt.compareTo(a.createdAt)).limit(LISTED).toList();
    }
}
