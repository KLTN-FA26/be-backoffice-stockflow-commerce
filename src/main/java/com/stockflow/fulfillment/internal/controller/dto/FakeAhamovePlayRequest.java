package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * Plays the rest of an order's life on the fake Ahamove.
 *
 * @param outcome      DELIVERED (default) or FAILED
 * @param reason       FAILED: Ahamove's wording of the failure of each drop-off
 * @param driverName   the driver, if one is still to be found
 * @param driverPhone  likewise
 * @param vehiclePlate likewise; kept on the fake order only
 */
public record FakeAhamovePlayRequest(@Size(max = 16) String outcome, @Size(max = 200) String reason,
                                     @Size(max = 120) String driverName, @Size(max = 32) String driverPhone,
                                     @Size(max = 32) String vehiclePlate) {
}
