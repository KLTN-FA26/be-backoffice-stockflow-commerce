package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps a stock package (a carton) true to its rows wherever stock is taken out of one (SCRUM-473, #141).
 *
 * <p>The database checks a carton against its rows at commit (V20261020000200): an IN_STOCK carton holds
 * something, any other holds nothing, an unopened one holds exactly what was counted into it. Those checks read
 * without locking, so they are only safe when every writer keeps one lock order (V20261020000100): <b>the SKU
 * advisory lock, then the cartons ({@code FOR UPDATE}, in id order), then their stock rows by id.</b> A caller
 * therefore {@link #lockFor locks} the cartons of the rows it is about to lock - after the SKU lock, before the
 * rows - marks each row it {@link LockedCartons#tookFrom took units from}, and {@link #settle settles} once its
 * rows are saved: each carton taken from is opened, and closed CONSUMED when nothing is left in it.</p>
 *
 * <p>Taking from a carton that was not locked first is refused at once ({@link IllegalStateException}): it is the
 * lock order broken, and the commit-time check would otherwise surface the miss as a 500 - or not at all.</p>
 */
@Component
class StockPackageContents {

    private final StockPackageRepository stockPackages;
    private final StockItemRepository stockItems;

    StockPackageContents(StockPackageRepository stockPackages, StockItemRepository stockItems) {
        this.stockPackages = stockPackages;
        this.stockItems = stockItems;
    }

    /** The cartons of these rows, locked in id order; none when every row is loose. Call under the SKU lock. */
    public LockedCartons lockFor(Collection<StockItem> rows) {
        Set<StockPackageId> ids = rows.stream().map(StockItem::stockPackageId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return new LockedCartons(Map.of());
        }
        return new LockedCartons(stockPackages.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(StockPackage::id, Function.identity())));
    }

    /** After the rows are saved: every carton taken from is opened, or CONSUMED once empty. */
    public void settle(LockedCartons cartons, Instant now) {
        for (StockPackageId id : cartons.taken) {
            StockPackage carton = cartons.locked.get(id);
            carton.contentsTaken(stockItems.onHandInStockPackage(id), now);
            stockPackages.save(carton);
        }
    }

    /** The cartons a caller locked, and which of them it has taken units from. */
    static final class LockedCartons {

        private final Map<StockPackageId, StockPackage> locked;
        private final Set<StockPackageId> taken = new LinkedHashSet<>();

        private LockedCartons(Map<StockPackageId, StockPackage> locked) {
            this.locked = locked;
        }

        /** {@code row} gave up units; nothing to do for loose stock. */
        void tookFrom(StockItem row) {
            if (row.isLoose()) {
                return;
            }
            if (!locked.containsKey(row.stockPackageId())) {
                throw new IllegalStateException("Stock package %s was not locked before its row %s was taken from"
                        .formatted(row.stockPackageId(), row.id()));
            }
            taken.add(row.stockPackageId());
        }
    }
}
