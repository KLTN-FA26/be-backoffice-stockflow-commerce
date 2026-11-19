package com.stockflow.order.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerAddressSummary;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderAddressSnapshot;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.entity.OrderAddressChangeJpaEntity;
import com.stockflow.order.internal.entity.OrderAddressJpaEmbeddable;
import com.stockflow.order.internal.repository.OrderAddressChangeJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * See {@link ShippingAddressChanges}. The order's row lock first - the lock the first label takes
 * ({@code OrderServiceImpl.recordFirstLabel}), so no carton is labelled between the check and the change - then the
 * customer's address book is read and the change written, in one transaction.
 */
@Service
@Transactional
class ShippingAddressChangeService implements ShippingAddressChanges {

    private static final Logger log = LoggerFactory.getLogger(ShippingAddressChangeService.class);
    private static final String SHIPPING = "SHIPPING";

    private final OrderRepository repository;
    private final OrderAddressChangeJpaRepository changes;
    private final CustomerService customers;
    private final OrderService orders;
    private final Clock clock;

    ShippingAddressChangeService(OrderRepository repository, OrderAddressChangeJpaRepository changes,
                                 CustomerService customers, OrderService orders, Clock clock) {
        this.repository = repository;
        this.changes = changes;
        this.customers = customers;
        this.orders = orders;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "order-shipping-address", resourceId = "#orderId")
    public OrderSummary change(UUID orderId, UUID shippingAddressId, UUID by) {
        Order order = repository.findByIdForUpdate(new OrderId(orderId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No order with id " + orderId));
        OrderAddressSnapshot previous = order.shippingAddress();
        if (order.changeShippingAddress(addressBookEntry(order, shippingAddressId, by))) {
            repository.save(order);
            changes.save(new OrderAddressChangeJpaEntity(Identifiers.newId(), orderId, order.status().name(),
                    toEntity(previous), toEntity(order.shippingAddress()), by, clock.instant()));
            log.info("Order {}: delivery address changed by {}", order.orderNumber(), by);
        }
        return orders.findById(orderId).orElseThrow();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Change> changes(UUID orderId) {
        return changes.findByOrderIdOrderByChangedAtAscIdAsc(orderId).stream()
                .map(row -> new Change(OrderStatus.valueOf(row.getOrderStatus()), toSummary(row.getPreviousAddress()),
                        toSummary(row.getNewAddress()), row.getChangedBy(), row.getChangedAt()))
                .toList();
    }

    /**
     * The customer's shipping address named (14 §4.2 step 9): 404 {@code ADDRESS_NOT_FOUND} when it is not one of the
     * order's customer's, 400 for a billing address - as the order's first address was chosen.
     */
    private OrderAddressSnapshot addressBookEntry(Order order, UUID addressId, UUID by) {
        CustomerAddressSummary address = order.customerId() == null ? null
                : customers.listAddresses(order.customerId(), by, true).stream()
                        .filter(entry -> entry.addressId().equals(addressId)).findFirst().orElse(null);
        if (address == null) {
            throw new BusinessException(ErrorCode.ADDRESS_NOT_FOUND);
        }
        if (!SHIPPING.equals(address.type())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Address is not a " + SHIPPING + " address");
        }
        return new OrderAddressSnapshot(address.recipientName(), address.phone(), address.line1(), address.line2(),
                address.wardCode(), address.wardName(), address.provinceCode(), address.provinceName(),
                address.countryCode(), address.postalCode());
    }

    private static OrderAddressJpaEmbeddable toEntity(OrderAddressSnapshot address) {
        return address == null ? null : new OrderAddressJpaEmbeddable(address.recipientName(), address.phone(),
                address.line1(), address.line2(), address.wardCode(), address.wardName(), address.provinceCode(),
                address.provinceName(), address.countryCode(), address.postalCode());
    }

    private static OrderSummary.AddressSummary toSummary(OrderAddressJpaEmbeddable address) {
        return address == null || address.getRecipientName() == null ? null
                : new OrderSummary.AddressSummary(address.getRecipientName(), address.getPhone(), address.getLine1(),
                        address.getLine2(), address.getWardCode(), address.getWardName(), address.getProvinceCode(),
                        address.getProvinceName(), address.getCountryCode(), address.getPostalCode());
    }
}
