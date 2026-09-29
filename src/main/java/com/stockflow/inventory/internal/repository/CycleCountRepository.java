package com.stockflow.inventory.internal.repository;

import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.CycleCountLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Count lines and posting ledger belong to one root transaction; no independent line mutations. */
@Repository
public class CycleCountRepository {
    private final JdbcTemplate jdbc;
    public CycleCountRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lockRequest(UUID requestId){jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,702))",r -> {},requestId.toString());}
    public List<String> skus(List<UUID> stockIds) {
        String parameters=String.join(",",java.util.Collections.nCopies(stockIds.size(),"?"));
        return jdbc.query("select distinct sku from inventory.stock_item where id in ("+parameters+") order by sku",(r,n)->r.getString(1),stockIds.toArray());
    }
    public List<CycleCountLine> lines(UUID id) {
        return jdbc.query("""
                select l.*,s.sku,s.location_code,s.lot_number,s.serial_number,s.received_at,s.expiry_date,
                       s.on_hand,s.reserved,s.version current_version
                from inventory.cycle_count_line l join inventory.stock_item s on s.id=l.stock_id
                where l.count_id=? order by l.stock_id
                """,(r,n)->new CycleCountLine(r.getObject("stock_id",UUID.class),r.getString("sku"),r.getString("location_code"),
                r.getString("lot_number"),r.getString("serial_number"),r.getTimestamp("received_at")==null?null:r.getTimestamp("received_at").toInstant(),
                r.getObject("expiry_date",java.time.LocalDate.class),r.getInt("baseline_qty"),r.getLong("baseline_version"),
                r.getObject("counted_qty",Integer.class),r.getString("reason"),r.getInt("on_hand"),r.getInt("reserved"),r.getLong("current_version")),id);
    }
    public void add(UUID id,UUID stockId) {
        jdbc.update("""
                insert into inventory.cycle_count_line(count_id,stock_id,baseline_qty,baseline_version)
                select ?,id,on_hand,version from inventory.stock_item where id=?
                """,id,stockId);
    }
    public void refresh(UUID id) {
        jdbc.update("""
                update inventory.cycle_count_line l set baseline_qty=s.on_hand,baseline_version=s.version,
                counted_qty=null,reason=null from inventory.stock_item s where l.stock_id=s.id and l.count_id=?
                """,id);
    }
    public void count(UUID id,UUID stockId,int quantity,String reason) {
        jdbc.update("update inventory.cycle_count_line set counted_qty=?,reason=? where count_id=? and stock_id=?",quantity,reason,id,stockId);
    }
    public void release(UUID id){jdbc.update("update inventory.cycle_count_line set active=false where count_id=?",id);}
    public void post(UUID id,CycleCountLine l,UUID counter,UUID approver,Instant now) {
        jdbc.update("""
                insert into inventory.stock_adjustment(id,count_id,stock_id,before_qty,after_qty,counted_by,approved_by,reason,posted_at)
                values (?,?,?,?,?,?,?,?,?)
                """,Identifiers.newId(),id,l.stockId(),l.baseline(),l.counted(),counter,approver,l.reason(),java.sql.Timestamp.from(now));
        jdbc.update("update inventory.stock_item set on_hand=?,version=version+1,last_modified_at=? where id=?",
                l.counted(),java.sql.Timestamp.from(now),l.stockId());
    }
}
