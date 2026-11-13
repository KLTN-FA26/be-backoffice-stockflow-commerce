package com.stockflow.chat.internal.service;

import com.stockflow.design.api.CustomerDesign;
import com.stockflow.order.api.CustomerDocument;
import com.stockflow.payment.api.CustomerInstallments;

import java.util.List;
import java.util.UUID;

/**
 * The customer beside the conversation (16 §4.1 step 3: "mở hội thoại thấy ngữ cảnh + hồ sơ khách + lịch sử"): who
 * they are and their recent documents, each read through its own module's {@code api} - the chat keeps none of it.
 *
 * @param code           the customer's code as documents print it
 * @param installments   the open instalments, the earliest due first, with whether each is overdue
 * @param conversations  the customer's recent conversations, any status
 */
public record ChatCustomerOverview(UUID customerId, String code, String name, String email, String phone,
                                   String status, List<CustomerDocument> orders, List<CustomerDocument> quotes,
                                   List<CustomerDocument> contracts, List<CustomerDocument> productionOrders,
                                   List<CustomerInstallments.OpenInstallment> installments,
                                   List<CustomerDesign> designs, List<ChatConversationView> conversations) {
}
