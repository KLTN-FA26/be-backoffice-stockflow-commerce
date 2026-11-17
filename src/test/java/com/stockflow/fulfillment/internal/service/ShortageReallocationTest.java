package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The reallocation of a shortage, and the INVENTORY hold kept when there is no stock (07 §4.4 steps 10-11). */
class ShortageReallocationTest {

    private final PickLists pickLists = mock(PickLists.class);
    private final ShortageReallocation shortages = new ShortageReallocation(pickLists);
    private final UUID task = UUID.randomUUID();
    private final UUID line = UUID.randomUUID();
    private final CurrentUser manager = new CurrentUser(UUID.randomUUID(), "wm",
            Set.of(com.stockflow.common.security.Role.WAREHOUSE_MANAGER), Set.of(), DataScope.ALL, Set.of(), Set.of());

    @Test
    @DisplayName("reallocated: the new lines, no hold")
    void reallocated() {
        when(pickLists.reallocateShort(task, line, manager)).thenReturn(List.of());

        assertThat(shortages.reallocate(task, line, manager)).isEmpty();
        verify(pickLists, never()).holdForUnreplacedCarton(any(), any(), any());
    }

    @Test
    @DisplayName("INSUFFICIENT_STOCK: refused as it was, and the order is held in a transaction of its own")
    void noStock() {
        BusinessException refusal = new BusinessException(ErrorCode.INSUFFICIENT_STOCK, "no cartons");
        when(pickLists.reallocateShort(task, line, manager)).thenThrow(refusal);

        assertThat(catchThrowableOfType(BusinessException.class, () -> shortages.reallocate(task, line, manager)))
                .isSameAs(refusal);
        verify(pickLists).holdForUnreplacedCarton(task, line, manager);
    }

    @Test
    @DisplayName("any other refusal holds nothing")
    void otherRefusal() {
        when(pickLists.reallocateShort(task, line, manager))
                .thenThrow(new BusinessException(ErrorCode.PICK_LINE_NOT_OPEN, "not short"));

        assertThat(catchThrowableOfType(BusinessException.class, () -> shortages.reallocate(task, line, manager))
                .errorCode()).isEqualTo(ErrorCode.PICK_LINE_NOT_OPEN);
        verify(pickLists, never()).holdForUnreplacedCarton(any(), any(), any());
    }
}
