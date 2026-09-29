package com.stockflow.inventory.internal.repository;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.StockAlert;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Repository
public class StockAlertRepository {
    private final JdbcTemplate jdbc;
    public StockAlertRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static StockAlert map(java.sql.ResultSet r,int n)throws java.sql.SQLException {
        return new StockAlert(r.getObject("id",UUID.class),r.getString("sku"),r.getString("kind"),r.getString("status"),
                r.getLong("observed_qty"),r.getInt("threshold_value"),r.getTimestamp("opened_at").toInstant(),r.getTimestamp("last_observed_at").toInstant(),
                r.getTimestamp("resolved_at")==null?null:r.getTimestamp("resolved_at").toInstant(),r.getString("resolution_reason"),
                r.getObject("acknowledged_by",UUID.class),r.getTimestamp("acknowledged_at")==null?null:r.getTimestamp("acknowledged_at").toInstant());
    }
    public Optional<StockAlert> find(UUID id){return jdbc.query("select * from inventory.stock_alert where id=?",StockAlertRepository::map,id).stream().findFirst();}
    public Optional<StockAlert> open(String sku,String kind){return jdbc.query("select * from inventory.stock_alert where sku=? and kind=? and status='OPEN' for update",StockAlertRepository::map,sku,kind).stream().findFirst();}
    public StockAlert create(String sku,String kind,long quantity,int threshold,Instant now) {
        UUID id=Identifiers.newId();jdbc.update("""
                insert into inventory.stock_alert(id,sku,kind,status,observed_qty,threshold_value,opened_at,last_observed_at)
                values (?,?,?,'OPEN',?,?,?,?)
                """,id,sku,kind,quantity,threshold,Timestamp.from(now),Timestamp.from(now));return find(id).orElseThrow();
    }
    public void observed(UUID id,long quantity,int threshold,Instant now){jdbc.update("update inventory.stock_alert set observed_qty=?,threshold_value=?,last_observed_at=? where id=?",quantity,threshold,Timestamp.from(now),id);}
    public StockAlert resolve(UUID id,long quantity,Instant now,String reason){jdbc.update("""
            update inventory.stock_alert set status='RESOLVED',observed_qty=?,last_observed_at=?,resolved_at=?,resolution_reason=? where id=?
            """,quantity,Timestamp.from(now),Timestamp.from(now),reason,id);return find(id).orElseThrow();}
    public void acknowledge(UUID id,UUID actor,Instant now){jdbc.update("""
            update inventory.stock_alert set acknowledged_by=?,acknowledged_at=? where id=? and status='OPEN' and acknowledged_by is null
            """,actor,Timestamp.from(now),id);}
    public List<StockAlert> page(String status,long offset,int size){return jdbc.query("select * from inventory.stock_alert where (cast(? as varchar) is null or status=?) order by opened_at desc,id desc limit ? offset ?",StockAlertRepository::map,status,status,size,offset);}
    public long count(String status){return jdbc.queryForObject("select count(*) from inventory.stock_alert where (cast(? as varchar) is null or status=?)",Long.class,status,status);}
    public List<String> nextEvaluation(){return jdbc.query("select sku from inventory.sku_policy order by last_evaluated_at nulls first,sku limit 50",(r,n)->r.getString(1));}
    public void attempted(String sku,Instant now){jdbc.update("update inventory.sku_policy set last_evaluated_at=? where sku=?",Timestamp.from(now),sku);}
}
