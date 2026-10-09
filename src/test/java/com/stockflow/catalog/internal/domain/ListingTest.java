package com.stockflow.catalog.internal.domain;
import com.stockflow.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class ListingTest {
    @Test void vietnameseSlugIsCanonicalAndBlankSlugIsRejected() {
        assertThat(Listing.normalizeSlug("  Bàn Gỗ Đẹp  ")).isEqualTo("ban-go-dep");
        assertThatThrownBy(() -> Listing.normalizeSlug("!!!")).isInstanceOf(BusinessException.class);
    }
    @Test void publishedUrlCannotBeChangedEvenAfterUnpublication() {
        var listing=new Listing(UUID.randomUUID(),"ban-go",null,null,3,3,false,true,null,null,null,null);
        assertThatThrownBy(() -> listing.edit("khac",null,null,3)).isInstanceOf(BusinessException.class);
        assertThat(listing.edit("ban-go","New SEO",null,3).revision()).isEqualTo(4);
        assertThatThrownBy(() -> listing.edit("ban-go",null,null,2)).isInstanceOf(BusinessException.class);
    }
}
