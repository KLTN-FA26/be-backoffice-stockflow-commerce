package com.stockflow.notification.internal.repository;

import com.stockflow.notification.api.*;
import com.stockflow.common.id.Identifiers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class InventoryAlertDeliveryRepository {
    private final JdbcTemplate jdbc;
    public InventoryAlertDeliveryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Pending(UUID id,UUID alertId,String phase,String recipient,String subject,String body,int attempts,UUID token){}
    private void lockAlert(UUID id){jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,703))",r -> {},id.toString());}
    public void enqueue(InventoryAlertMessage m) {
        lockAlert(m.alertId());
        if("RESOLVED".equals(m.status()))jdbc.update("""
                update notification.inventory_alert_delivery set status='SUPERSEDED'
                where alert_id=? and event_status='OPEN' and status in ('READY','FAILED','BLOCKED_CONFIG')
                """,m.alertId());
        insert(m.alertId(),m.status(),"","StockFlow inventory "+m.status()+": "+m.sku(),
                "Alert: %s\nSKU: %s\nKind: %s\nState: %s\nUsable on-hand: %d\nThreshold: %d\nObserved at: %s\nThis records a historical transition. Check the inventory alert screen for current status."
                        .formatted(m.alertId(),m.sku(),m.kind(),m.status(),m.quantity(),m.threshold(),m.occurredAt()),m.occurredAt());
    }
    private void insert(UUID alertId,String phase,String recipient,String subject,String body,Instant now){
        jdbc.update("""
                insert into notification.inventory_alert_delivery(id,alert_id,event_status,recipient,subject,body,status,next_attempt_at,created_at)
                values (?,?,?,?,?,?,'READY',?,?) on conflict(alert_id,event_status,recipient) do nothing
                """,Identifiers.newId(),alertId,phase,recipient,subject,body,Timestamp.from(now),Timestamp.from(now));
    }
    /** Caller owns a short transaction; SKIP LOCKED prevents duplicate workers from claiming a row. */
    public Pending claim(Instant now,List<String> recipients) {
        var found=jdbc.query("""
                select * from notification.inventory_alert_delivery
                where ((status in ('READY','BLOCKED_CONFIG') and next_attempt_at<=?)
                       or (status='SENDING' and leased_until<=?))
                order by next_attempt_at,id limit 1
                """,(r,n)->new Pending(r.getObject("id",UUID.class),r.getObject("alert_id",UUID.class),r.getString("event_status"),
                r.getString("recipient"),r.getString("subject"),r.getString("body"),r.getInt("attempts"),null),Timestamp.from(now),Timestamp.from(now));
        if(found.isEmpty())return null;
        var p=found.getFirst();
        // Same lock order as enqueue/complete: alert, then delivery. Recheck after waiting.
        lockAlert(p.alertId());
        var claimed=jdbc.query("""
                select * from notification.inventory_alert_delivery where id=? and
                ((status in ('READY','BLOCKED_CONFIG') and next_attempt_at<=?) or (status='SENDING' and leased_until<=?))
                for update skip locked
                """,(r,n)->new Pending(r.getObject("id",UUID.class),r.getObject("alert_id",UUID.class),r.getString("event_status"),
                r.getString("recipient"),r.getString("subject"),r.getString("body"),r.getInt("attempts"),null),p.id(),Timestamp.from(now),Timestamp.from(now));
        if(claimed.isEmpty())return null;
        p=claimed.getFirst();
        if("OPEN".equals(p.phase()) && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from notification.inventory_alert_delivery where alert_id=? and event_status='RESOLVED')",Boolean.class,p.alertId()))) {
            jdbc.update("update notification.inventory_alert_delivery set status='SUPERSEDED',lease_token=null,leased_until=null where id=?",p.id());
            return new Pending(p.id(),p.alertId(),p.phase(),p.recipient(),p.subject(),p.body(),p.attempts(),null);
        }
        if(p.recipient().isEmpty()) {
            if(recipients.isEmpty())jdbc.update("update notification.inventory_alert_delivery set status='BLOCKED_CONFIG',next_attempt_at=?,last_error='NO_RECIPIENTS' where id=?",Timestamp.from(now.plusSeconds(60)),p.id());
            else {
                for(String recipient:recipients)insert(p.alertId(),p.phase(),recipient,p.subject(),p.body(),now);
                jdbc.update("update notification.inventory_alert_delivery set status='SUPERSEDED',last_error=null where id=?",p.id());
            }
            return new Pending(p.id(),p.alertId(),p.phase(),"",p.subject(),p.body(),p.attempts(),null);
        }
        UUID token=Identifiers.newId();
        jdbc.update("update notification.inventory_alert_delivery set status='SENDING',lease_token=?,leased_until=?,attempts=attempts+1 where id=?",
                token,Timestamp.from(now.plusSeconds(300)),p.id());
        return new Pending(p.id(),p.alertId(),p.phase(),p.recipient(),p.subject(),p.body(),p.attempts()+1,token);
    }
    public boolean complete(Pending p,Instant now,String error) {
        // Serialize recovery with a failed in-flight attempt, or it could revive the old OPEN event.
        lockAlert(p.alertId());
        String next=error==null?"SENT":p.attempts()>=10?"FAILED":"READY";
        if(error!=null && "OPEN".equals(p.phase()) && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from notification.inventory_alert_delivery where alert_id=? and event_status='RESOLVED')",Boolean.class,p.alertId())))
            next="SUPERSEDED";
        long delay=Math.min(3600,30L << Math.min(p.attempts(),7));
        return jdbc.update("""
                update notification.inventory_alert_delivery set status=?,lease_token=null,leased_until=null,
                next_attempt_at=?,last_error=?,sent_at=? where id=? and status='SENDING' and lease_token=?
                """,next,Timestamp.from(now.plusSeconds(delay)),error,error==null?Timestamp.from(now):null,p.id(),p.token())==1;
    }
    public List<AlertDeliverySummary> list(UUID alertId) {
        return jdbc.query("select * from notification.inventory_alert_delivery where alert_id=? order by created_at,id",
                (r,n)->new AlertDeliverySummary(r.getObject("id",UUID.class),r.getString("event_status"),r.getString("recipient"),r.getString("status"),
                r.getInt("attempts"),r.getTimestamp("next_attempt_at").toInstant(),r.getTimestamp("sent_at")==null?null:r.getTimestamp("sent_at").toInstant(),r.getString("last_error")),alertId);
    }
    public void retry(UUID alertId,Instant now){lockAlert(alertId);jdbc.update("""
            update notification.inventory_alert_delivery set status='READY',attempts=0,next_attempt_at=?,last_error=null
            where alert_id=? and status in ('FAILED','BLOCKED_CONFIG')
            """,Timestamp.from(now),alertId);}
}
