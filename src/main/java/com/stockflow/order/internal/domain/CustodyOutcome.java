package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * How one custody note's goods end (WF-05 Q-11; audit round 3, RAD-3): what the customer chose, as Sales recorded it,
 * and - once the warehouse did it - who, when and the record of it. Kept beside the note
 * ({@code ordering.return_custody_outcome}), since the note itself is append-only: what was taken in is never edited,
 * how it left is written once.
 *
 * <p>Before, a custody note had no state: goods kept for a customer sat in the warehouse with a photo and no recorded
 * outcome, and nobody could record "gửi trả", "huỷ hộ" or "taken by supplementary RMA X". Its invariants are stated by
 * {@link ReturnRequest#chooseForCustody} / {@link ReturnRequest#endCustody} and again by the table's CHECKs and
 * {@code tg_return_custody_outcome}.</p>
 *
 * @param choiceNote       the record the choice rests on: required for DESTROY_ON_BEHALF (their written consent)
 * @param takenByReturnId  TAKEN_BY_RETURN only: the supplementary return of the same customer that takes the goods
 * @param takenByRmaNumber that return's RMA number, for the screens
 * @param endedAt          when the warehouse carried it out; null while the goods are still kept
 * @param endedNote        the record of the ending: the hand-over note, the destruction record, the receipt that took
 *                         them
 */
public record CustodyOutcome(UUID evidenceId, ReturnCustodyNoteChoice choice, String choiceNote, UUID takenByReturnId,
                             String takenByRmaNumber, UUID chosenBy, Instant chosenAt, Instant endedAt, UUID endedBy,
                             String endedNote) {

    public CustodyOutcome {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(choice, "choice");
        Objects.requireNonNull(chosenBy, "chosenBy");
        Objects.requireNonNull(chosenAt, "chosenAt");
        if ((choice == ReturnCustodyNoteChoice.TAKEN_BY_RETURN) != (takenByReturnId != null)) {
            throw new IllegalArgumentException("A supplementary return is named exactly when it takes the goods");
        }
        if ((endedAt == null) != (endedBy == null) || (endedAt == null) != (endedNote == null)) {
            throw new IllegalArgumentException("A custody ends by someone, at a time, with its record");
        }
    }

    /** Whether the warehouse carried it out: the goods left. */
    public boolean ended() {
        return endedAt != null;
    }
}
