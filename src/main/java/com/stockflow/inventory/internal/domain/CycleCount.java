package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A stock count over a set of slots, from planning to submission (SCRUM-146, spec §3, §5).
 *
 * <p>The aggregate owns the rules that used to be scattered over SQL and a service: a closed count
 * takes no counts, a count is submitted only when every line is counted, and a second submit or
 * cancel is a harmless no-op rather than an error, so a double tap or a retry after a timeout cannot
 * raise anything twice. It does not touch stock: variances become adjustments in the application
 * layer, after {@link #submit} says this call is the one that completed the count.</p>
 *
 * <p>Counting overwrites the line's expected quantity with the book quantity read at that moment
 * (spec §5, D3): the variance is judged against what the books held when the shelf was counted, not
 * against a figure frozen at planning that picks and receipts have since moved.</p>
 */
public final class CycleCount extends AggregateRoot {

    /** The longest note the column holds. */
    private static final int NOTE_MAX = 1000;

    /** A slot to count and the book quantity it was planned with. */
    public record CountLineDraft(CountSlot slot, int expectedQty) { }

    private final UUID id;
    private final String number;
    private final UUID warehouseId;
    private final CycleCountTrigger trigger;
    private final boolean blind;
    private final LocalDate plannedDate;
    private final Instant createdAt;
    private final long version;
    private final List<CycleCountLine> lines;

    private CycleCountStatus status;
    private UUID assignedUserId;
    private Instant startedAt;
    private Instant completedAt;
    private String note;

    /** Rehydration from stored rows. */
    public CycleCount(UUID id, String number, UUID warehouseId, CycleCountTrigger trigger, CycleCountStatus status,
                      boolean blind, UUID assignedUserId, LocalDate plannedDate, Instant startedAt,
                      Instant completedAt, String note, Instant createdAt, List<CycleCountLine> lines, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.status = Objects.requireNonNull(status, "status");
        this.blind = blind;
        this.assignedUserId = assignedUserId;
        this.plannedDate = plannedDate;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.note = note;
        this.createdAt = createdAt;
        this.lines = new ArrayList<>(Objects.requireNonNull(lines, "lines"));
        this.version = version;
    }

    /**
     * @throws BusinessException {@code CYCLE_COUNT_EMPTY_SCOPE} when there is nothing to count
     * @throws IllegalArgumentException when two drafts name the same slot (the caller dedupes; a duplicate is a bug)
     */
    public static CycleCount plan(String number, UUID warehouseId, CycleCountTrigger trigger, boolean blind,
                                  UUID assignedUserId, LocalDate plannedDate, String note,
                                  List<CountLineDraft> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_EMPTY_SCOPE, "The scope matches no stock to count");
        }
        // By lock key, not record equality: a carton is one slot wherever it stands, so two drafts of one carton at
        // different bins (it moved between two reads) are the same slot, as the claim and the advisory lock see it.
        Set<String> seen = new HashSet<>();
        List<CycleCountLine> built = new ArrayList<>(lines.size());
        for (CountLineDraft draft : lines) {
            if (!seen.add(draft.slot().lockKey())) {
                throw new IllegalArgumentException("Slot planned twice: " + draft.slot().lockKey());
            }
            built.add(new CycleCountLine(draft.slot(), draft.expectedQty()));
        }
        return new CycleCount(Identifiers.newId(), number, warehouseId, trigger, CycleCountStatus.PLANNED, blind,
                assignedUserId, plannedDate, null, null, blankToNull(note), Instant.now(), built, 0L);
    }

    /**
     * The recount a short pick opens on its slot. Blind and unassigned: whoever walks over to the bin
     * must not be told what the books expect, and the manager hands it out.
     */
    public static CycleCount shortPick(String number, UUID warehouseId, CountSlot slot, int expectedQty,
                                       String note, LocalDate day) {
        return plan(number, warehouseId, CycleCountTrigger.SHORT_PICK, true, null, day, note,
                List.of(new CountLineDraft(slot, expectedQty)));
    }

    /** Hands the count to a counter; only before counting starts, so a half-counted sheet keeps its counter. */
    public void assign(UUID userId) {
        if (status != CycleCountStatus.PLANNED) {
            throw new BusinessException(ErrorCode.CONFLICT, "A count can be assigned only while it is planned");
        }
        this.assignedUserId = Objects.requireNonNull(userId, "userId");
    }

    /** Someone else's count is theirs to count, unless the caller may decide (approver). */
    public void requireCounter(UUID userId, boolean mayDecide) {
        if (!mayDecide && assignedUserId != null && !assignedUserId.equals(userId)) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_ASSIGNEE, "This count is assigned to someone else");
        }
    }

    /**
     * Four eyes, counting side: on a count assigned to someone, a line another user already counted is not
     * overwritten by a third person - not even one who may decide. That user sees the books, so replacing the
     * assignee's figure and then approving the variance it raises would have one person set and decide the number
     * (the approval refuses the last counter too, {@code StockAdjustment#requireNotCountedBy}; this closes the first
     * half). The assignee corrects their own and anyone's line; an unassigned count has no owner to protect.
     *
     * @throws BusinessException {@code CYCLE_COUNT_LINE_COUNTED_BY_OTHER}
     */
    public void requireMayCount(CycleCountLine line, UUID by) {
        if (assignedUserId != null && !assignedUserId.equals(by)
                && line.countedBy() != null && !line.countedBy().equals(by)) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_LINE_COUNTED_BY_OTHER,
                    "This line was counted by someone else; only the assignee may count it again");
        }
    }

    public CycleCountLine count(UUID lineId, int countedQty, int bookQty, UUID by, Instant at) {
        requireOpen();
        CycleCountLine line = lines.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No such line on this count"));
        requireMayCount(line, by);
        line.count(countedQty, bookQty, by, at);
        start(at);
        return line;
    }

    /**
     * A carton line follows its carton to the bin it was moved to whole, so the count and the variance it raises name
     * the carton where it is. Only while the count is open: a submitted count is history.
     */
    public CycleCountLine relocateCarton(UUID lineId, LocationId to) {
        requireOpen();
        CycleCountLine line = lines.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "No such line on this count"));
        line.relocate(Objects.requireNonNull(to, "to"));
        return line;
    }

    /** A slot found on the shelf that the plan did not list. */
    public CycleCountLine addLine(CountSlot slot, int countedQty, int bookQty, UUID by, Instant at) {
        requireOpen();
        // By lock key: a carton line that followed its carton to another bin is still that carton's line.
        if (lines.stream().anyMatch(l -> l.slot().lockKey().equals(slot.lockKey()))) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_SLOT_BUSY, "This slot is already on the count");
        }
        CycleCountLine line = new CycleCountLine(slot, bookQty);
        line.count(countedQty, bookQty, by, at);
        lines.add(line);
        start(at);
        return line;
    }

    /**
     * @return true when this call completed the count, false when it already was complete: the caller
     *         raises adjustments only on true, so a repeated submit raises none
     */
    public boolean submit(Instant at) {
        if (status == CycleCountStatus.COMPLETED) {
            return false;
        }
        if (status == CycleCountStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN);
        }
        List<String> missing = lines.stream().filter(l -> !l.isCounted()).map(CycleCount::describe).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_LINE_NOT_COUNTED,
                    "Not counted yet: " + String.join(", ", missing));
        }
        this.status = CycleCountStatus.COMPLETED;
        this.completedAt = Objects.requireNonNull(at, "at");
        return true;
    }

    /** @return false when it was already cancelled. */
    public boolean cancel(String reason, Instant at) {
        if (status == CycleCountStatus.CANCELLED) {
            return false;
        }
        if (status == CycleCountStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN);
        }
        Objects.requireNonNull(at, "at");
        String prefix = note == null || note.isEmpty() ? "" : note + " | ";
        String combined = prefix + "Cancelled: " + Objects.requireNonNullElse(reason, "");
        this.note = combined.length() > NOTE_MAX ? combined.substring(0, NOTE_MAX) : combined;
        this.status = CycleCountStatus.CANCELLED;
        return true;
    }

    /**
     * Flags lines for a second count after approval found the figure doubtful; returns their slots. A line is
     * flagged once: its doubt is handed to that recount, and counting the slot again after it is a new count.
     *
     * @throws BusinessException {@code CONFLICT} naming every line already flagged, before any line is flagged
     */
    public List<CountSlot> markRecount(Collection<UUID> lineIds) {
        if (status != CycleCountStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN, "Only a submitted count can be sent for recount");
        }
        List<String> flagged = lines.stream().filter(line -> lineIds.contains(line.id()) && line.recountRequired())
                .map(line -> line.id().toString()).toList();
        if (!flagged.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Already sent for recount: " + String.join(", ", flagged));
        }
        List<CountSlot> slots = new ArrayList<>();
        for (CycleCountLine line : lines) {
            if (lineIds.contains(line.id())) {
                line.requireRecount();
                slots.add(line.slot());
            }
        }
        return slots;
    }

    private void requireOpen() {
        if (!status.isOpen()) {
            throw new BusinessException(ErrorCode.CYCLE_COUNT_NOT_OPEN);
        }
    }

    private void start(Instant at) {
        if (status == CycleCountStatus.PLANNED) {
            this.status = CycleCountStatus.IN_PROGRESS;
            this.startedAt = at;
        }
    }

    private static String describe(CycleCountLine line) {
        CountSlot slot = line.slot();
        return slot.isCarton()
                ? "carton " + slot.stockPackageId() + " at " + slot.location()
                : slot.location() + " " + slot.sku();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public UUID warehouseId() {
        return warehouseId;
    }

    public CycleCountTrigger trigger() {
        return trigger;
    }

    public CycleCountStatus status() {
        return status;
    }

    public boolean blind() {
        return blind;
    }

    public UUID assignedUserId() {
        return assignedUserId;
    }

    public LocalDate plannedDate() {
        return plannedDate;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String note() {
        return note;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<CycleCountLine> lines() {
        return List.copyOf(lines);
    }

    public long version() {
        return version;
    }
}
