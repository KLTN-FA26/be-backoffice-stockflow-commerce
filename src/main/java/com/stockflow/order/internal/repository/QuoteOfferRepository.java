package com.stockflow.order.internal.repository;
import com.stockflow.order.internal.domain.QuoteOffer;
import com.stockflow.common.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;
@Repository
public class QuoteOfferRepository {
    private final JdbcTemplate jdbc;
    public QuoteOfferRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lockRequest(UUID id){jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,705))",r->{},id.toString());}
    public void save(UUID id,QuoteOffer o){jdbc.update("""
            insert into ordering.design_quote_offer(quote_id,revision,quantity,unit_price,currency,valid_until,terms,created_at,created_by)
            values (?,?,?,?,'VND',?,?,?,?)
            """,id,o.revision(),o.quantity(),o.unitPrice().amount(),Timestamp.from(o.validUntil()),o.terms(),Timestamp.from(o.createdAt()),o.createdBy());}
    private static QuoteOffer map(java.sql.ResultSet r,int n)throws java.sql.SQLException {
        return new QuoteOffer(r.getInt("revision"),r.getInt("quantity"),new Money(r.getBigDecimal("unit_price"),Money.VND),
                r.getTimestamp("valid_until").toInstant(),r.getString("terms"),r.getTimestamp("created_at").toInstant(),r.getObject("created_by",UUID.class));
    }
    public QuoteOffer get(UUID id,int revision){return jdbc.query("select * from ordering.design_quote_offer where quote_id=? and revision=?",QuoteOfferRepository::map,id,revision).stream().findFirst().orElseThrow();}
    public void issue(UUID id,int revision,java.time.Instant now,UUID actor){
        jdbc.update("insert into ordering.design_quote_issue(quote_id,revision,issued_at,issued_by) values (?,?,?,?)",id,revision,Timestamp.from(now),actor);
    }
    private static String visible(boolean issuedOnly){return issuedOnly
            ? " and exists(select 1 from ordering.design_quote_issue i where i.quote_id=o.quote_id and i.revision=o.revision)" : "";}
    public long count(UUID id,boolean issuedOnly){return jdbc.queryForObject("select count(*) from ordering.design_quote_offer o where o.quote_id=?"+visible(issuedOnly),Long.class,id);}
    public List<QuoteOffer> page(UUID id,long offset,int size,boolean issuedOnly){return jdbc.query("select o.* from ordering.design_quote_offer o where o.quote_id=?"+visible(issuedOnly)+" order by revision desc limit ? offset ?",QuoteOfferRepository::map,id,size,offset);}
}
