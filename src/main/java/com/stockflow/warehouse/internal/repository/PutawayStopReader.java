package com.stockflow.warehouse.internal.repository;

import com.stockflow.warehouse.internal.domain.Footprint;
import com.stockflow.warehouse.internal.domain.PickFaces;
import com.stockflow.warehouse.internal.domain.PutawayRoute;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a putaway trip stops: the stand point of each storage location, worked out from the map (kltn-docs 06 §4.4
 * step 6). Two reads however many locations there are - the bins with their shelves, and the areas - and nothing is
 * stored: a shelf moved on the map moves the route the next time it is read.
 */
@Repository
public class PutawayStopReader {

    @PersistenceContext
    private EntityManager entityManager;

    /** The stop of each location that is a bin or a storage area; any other id is absent. */
    public Map<UUID, PutawayRoute.Stop> stopsOf(Collection<UUID> locationIds) {
        Map<UUID, PutawayRoute.Stop> stops = new HashMap<>();
        if (locationIds.isEmpty()) {
            return stops;
        }
        @SuppressWarnings("unchecked")
        List<Object[]> bins = entityManager.createNativeQuery("""
                        select l.id, l.location_code, s.x, s.y, s.width, s.length, s.rotation,
                               s.pick_north, s.pick_east, s.pick_south, s.pick_west,
                               b.x as bin_x, b.y as bin_y, b.width as bin_width, b.length as bin_length
                          from warehouse.bin b
                          join warehouse.shelf_level lv on lv.id = b.level_id
                          join warehouse.shelf s on s.id = lv.shelf_id
                          join warehouse.storage_location l on l.id = b.location_id
                         where l.id in (:ids)""")
                .setParameter("ids", locationIds).getResultList();
        for (Object[] r : bins) {
            Footprint shelf = new Footprint((BigDecimal) r[2], (BigDecimal) r[3], (BigDecimal) r[4],
                    (BigDecimal) r[5], ((Number) r[6]).intValue());
            PickFaces faces = new PickFaces((Boolean) r[7], (Boolean) r[8], (Boolean) r[9], (Boolean) r[10]);
            stops.put((UUID) r[0], new PutawayRoute.Stop((UUID) r[0], (String) r[1],
                    PutawayRoute.standPointOfBin(shelf, faces, (BigDecimal) r[11], (BigDecimal) r[12],
                            (BigDecimal) r[13], (BigDecimal) r[14]), false));
        }
        @SuppressWarnings("unchecked")
        List<Object[]> areas = entityManager.createNativeQuery("""
                        select l.id, l.location_code, a.x, a.y, a.width, a.length, a.rotation, a.type
                          from warehouse.area a
                          join warehouse.storage_location l on l.id = a.location_id
                         where l.id in (:ids)""")
                .setParameter("ids", locationIds).getResultList();
        for (Object[] r : areas) {
            Footprint area = new Footprint((BigDecimal) r[2], (BigDecimal) r[3], (BigDecimal) r[4],
                    (BigDecimal) r[5], ((Number) r[6]).intValue());
            stops.put((UUID) r[0], new PutawayRoute.Stop((UUID) r[0], (String) r[1],
                    PutawayRoute.standPointOfArea(area), "OVERFLOW".equals(r[7])));
        }
        return stops;
    }
}
