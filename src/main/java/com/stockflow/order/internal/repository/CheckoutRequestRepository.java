package com.stockflow.order.internal.repository;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

/** Permanent business replay evidence, separate from the expiring HTTP response cache. */
@Repository
public class CheckoutRequestRepository {
    private final JdbcTemplate jdbc;
    public CheckoutRequestRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lock(UUID requestId){jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,704))",r->{},requestId.toString());}
    public boolean matches(UUID requestId,String fingerprint){
        return jdbc.query("select fingerprint from ordering.checkout_request where request_id=?",(r,n)->r.getString(1),requestId)
                .stream().findFirst().map(fingerprint::equals).orElse(false);
    }
    public void save(UUID requestId,String fingerprint){
        jdbc.update("insert into ordering.checkout_request(request_id,fingerprint) values (?,?) on conflict do nothing",requestId,fingerprint);
        if(!matches(requestId,fingerprint))throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
    }
}
