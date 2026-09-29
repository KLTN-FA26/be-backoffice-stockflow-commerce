package com.stockflow.inventory.internal.controller.dto;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
public final class CycleCountRequests {
    private CycleCountRequests(){}
    public record Create(@NotNull UUID requestId,@NotBlank @Size(max=64) String warehouse,
                         @NotNull UUID assignedTo,@NotEmpty @Size(max=200) List<@NotNull UUID> stockIds,@Size(max=1000) String note){}
    public record Version(@NotNull @Min(0) Long version){}
    public record Record(@NotNull @Min(0) Long version,@NotNull @Min(0) Integer quantity,@Size(max=1000) String reason){}
    public record Reason(@NotNull @Min(0) Long version,@NotBlank @Size(max=1000) String reason){}
    public record Assignment(@NotNull @Min(0) Long version,@NotNull UUID assignedTo,@NotBlank @Size(max=1000) String reason){}
}
