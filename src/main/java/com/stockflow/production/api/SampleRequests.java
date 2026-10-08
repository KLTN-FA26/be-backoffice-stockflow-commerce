package com.stockflow.production.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Application boundary only; HTTP routes and runtime beans await the SCRUM-422 schema/grants. */
public interface SampleRequests {
    Summary create(Create command);
    Summary get(UUID id);
    Summary edit(Revision revision, Draft draft);
    UUID submit(Revision revision, UUID warehouseId);
    Summary sendToCustomer(Revision revision);
    Summary requestChanges(Revision revision, String feedback);
    Summary revise(Revision revision);
    Summary approve(Revision revision);
    Summary cancel(Revision revision);

    record Draft(UUID designSnapshotId, String blankSku, int quantity, LocalDate dueDate) { }
    record Create(UUID customerId, UUID quoteId, Draft draft) { }
    record Revision(UUID id, int round, long version) { }
    record Review(int round, UUID designSnapshotId, String designChecksum, String blankSku,
                  int quantity, LocalDate dueDate, String decision, String feedback,
                  UUID recordedBy, Instant recordedAt) { }
    record Summary(UUID id, UUID customerId, UUID quoteId, SampleStatus status,
                   int round, long version, Draft draft, String designChecksum, List<Review> reviews) {
        public Summary {
            reviews = List.copyOf(reviews);
        }
    }
}
