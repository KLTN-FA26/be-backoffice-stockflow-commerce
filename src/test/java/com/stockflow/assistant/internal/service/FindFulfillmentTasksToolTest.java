package com.stockflow.assistant.internal.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.stockflow.assistant.internal.domain.ToolCall;
import com.stockflow.assistant.internal.domain.ToolExecution;
import com.stockflow.assistant.internal.domain.ToolOutcome;
import com.stockflow.common.api.PageResponse;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.api.FulfillmentService;
import com.stockflow.fulfillment.api.FulfillmentTask;
import com.stockflow.fulfillment.api.FulfillmentTaskQuery;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.support.TestUsers;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * findFulfillmentTasks (ROLE-7): the pick-list screen's own list, asked for as the screen asks, and of a task only
 * where it stands - not who is on it.
 */
class FindFulfillmentTasksToolTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final UUID HCM = UUID.randomUUID();
    private static final UUID HN = UUID.randomUUID();
    private static final UUID PICKER = UUID.randomUUID();
    private static final Instant ASSIGNED = Instant.parse("2026-10-29T01:15:00Z");

    private final FulfillmentService fulfillment = mock(FulfillmentService.class);
    private final WarehouseService warehouses = mock(WarehouseService.class);
    private final CurrentUserProvider users = mock(CurrentUserProvider.class);
    private final FindFulfillmentTasksTool tool = new FindFulfillmentTasksTool(fulfillment, warehouses, users);
    private CurrentUser picker;

    @BeforeEach
    void signedIn() {
        picker = TestUsers.builder().id(PICKER).role(Role.WAREHOUSE_STAFF).scope(DataScope.WAREHOUSE)
                .warehouses("HCM").can("fulfillment-pick-lists", Action.READ).build();
        when(users.current()).thenReturn(Optional.of(picker));
        when(warehouses.findWarehouse(HCM)).thenReturn(Optional.of(new WarehouseView(HCM, "HCM", "Kho Hồ Chí Minh",
                "12 Đường số 3", true)));
        when(warehouses.findWarehouse(HN)).thenReturn(Optional.of(new WarehouseView(HN, "HN", "Kho Hà Nội",
                "5 Phố Huế", true)));
    }

    private static ToolArguments arguments(String status, int limit) {
        return ToolArguments.parse(JSON, "{\"status\":\"" + status + "\",\"limit\":" + limit + "}");
    }

    private static FulfillmentTask task(String orderNumber, UUID warehouse, UUID assignee, String pick, String pack) {
        return new FulfillmentTask(UUID.randomUUID(), UUID.randomUUID(), orderNumber, warehouse, assignee,
                assignee == null ? null : "Nguyễn Văn Nhặt", pick, pack, assignee == null ? null : ASSIGNED, null,
                null, null, List.of(new FulfillmentTask.IntegrityEvidence(UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64), false, ASSIGNED)), null);
    }

    private void listed(long total, FulfillmentTask... tasks) {
        when(fulfillment.listTasks(any(), any())).thenReturn(PageResponse.of(List.of(tasks), 0, 50, total));
    }

    @Test
    @DisplayName("ANY asks the screen's list for every status, a status is passed as it is, the limit is the page")
    void askedAsTheScreenAsks() {
        listed(0);
        ArgumentCaptor<FulfillmentTaskQuery> query = ArgumentCaptor.forClass(FulfillmentTaskQuery.class);

        tool.run(arguments("ANY", 50));
        tool.run(arguments("PICKED", 7));

        verify(fulfillment, times(2)).listTasks(eq(picker), query.capture());
        assertThat(query.getAllValues()).extracting(FulfillmentTaskQuery::status, FulfillmentTaskQuery::warehouseId,
                        FulfillmentTaskQuery::assignedUserId, FulfillmentTaskQuery::page, FulfillmentTaskQuery::size)
                .containsExactly(tuple(null, null, null, 0, 50), tuple("PICKED", null, null, 0, 7));
    }

    @Test
    @DisplayName("a row says where the task stands and whether it is the user's, with its warehouse's code")
    void rows() {
        listed(3, task("SO-1", HCM, PICKER, "PENDING", "PENDING"), task("SO-2", HCM, UUID.randomUUID(), "PICKING",
                "PENDING"), task("SO-3", HN, null, "PENDING", null));

        FindFulfillmentTasksTool.Answer answer = tool.run(arguments("ANY", 50));

        assertThat(answer.tasks()).extracting(FindFulfillmentTasksTool.Row::orderNumber,
                        FindFulfillmentTasksTool.Row::warehouseCode, FindFulfillmentTasksTool.Row::pickStatus,
                        FindFulfillmentTasksTool.Row::assigned, FindFulfillmentTasksTool.Row::assignedToUser)
                .containsExactly(tuple("SO-1", "HCM", "PENDING", true, true),
                        tuple("SO-2", "HCM", "PICKING", true, false),
                        tuple("SO-3", "HN", "PENDING", false, false));
        assertThat(answer.tasks().getFirst().assignedAt()).isEqualTo(ASSIGNED);
        assertThat(answer.total()).isEqualTo(3);
        assertThat(answer.truncated()).isFalse();
    }

    @Test
    @DisplayName("each warehouse is looked up once in a call, however many of its tasks are listed")
    void oneLookupPerWarehouse() {
        listed(3, task("SO-1", HCM, PICKER, "PENDING", "PENDING"), task("SO-2", HCM, PICKER, "PENDING", "PENDING"),
                task("SO-3", HCM, PICKER, "PENDING", "PENDING"));

        tool.run(arguments("PENDING", 50));

        verify(warehouses, times(1)).findWarehouse(HCM);
    }

    @Test
    @DisplayName("neither the assignee's name or id nor the design checksums reach the answer")
    void nothingOfThePeopleOrTheDesigns() throws Exception {
        UUID colleague = UUID.randomUUID();
        listed(1, task("SO-1", HCM, colleague, "PICKING", "PENDING"));

        String sent = JSON.writeValueAsString(tool.run(arguments("ANY", 50)));

        assertThat(sent).contains("SO-1").doesNotContain("Nguyễn Văn Nhặt", colleague.toString(), "a".repeat(64),
                "b".repeat(64), HCM.toString());
    }

    @Test
    @DisplayName("more tasks than returned is said: total counts them all")
    void truncated() {
        listed(120, task("SO-1", HCM, PICKER, "PENDING", "PENDING"));

        FindFulfillmentTasksTool.Answer answer = tool.run(arguments("PENDING", 1));

        assertThat(answer.total()).isEqualTo(120);
        assertThat(answer.truncated()).isTrue();
    }

    @Test
    @DisplayName("fifty tasks with every moment set stay well under the result limit")
    void fiftyRowsFit() {
        FulfillmentTask[] tasks = java.util.stream.IntStream.range(0, FindFulfillmentTasksTool.MAX_ROWS)
                .mapToObj(i -> new FulfillmentTask(UUID.randomUUID(), UUID.randomUUID(),
                        "SO-20261028-%06d".formatted(i), HCM, PICKER, "Nguyễn Văn Nhặt", "PICKED", "PACKING", ASSIGNED,
                        ASSIGNED.plusSeconds(60), ASSIGNED.plusSeconds(600), ASSIGNED.plusSeconds(900), List.of(),
                        null))
                .toArray(FulfillmentTask[]::new);
        listed(400, tasks);
        AssistantTools tools = new AssistantTools(List.of(tool), JsonMapper.builder()
                .addModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build());

        ToolExecution execution = tools.execute(new ToolCall("toolu_1", "findFulfillmentTasks",
                "{\"status\":\"ANY\",\"limit\":50}"));

        assertThat(execution.outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(execution.result().length())
                .as("well under the limit above which the model gets RESULT_TOO_LARGE and no task at all")
                .isLessThan(ToolExecution.MAX_RESULT_LENGTH * 9 / 10);
    }

    @Test
    @DisplayName("a warehouse that cannot be found leaves the code empty rather than failing the list")
    void unknownWarehouse() {
        UUID gone = UUID.randomUUID();
        when(warehouses.findWarehouse(gone)).thenReturn(Optional.empty());
        listed(1, task("SO-1", gone, PICKER, "PENDING", "PENDING"));

        assertThat(tool.run(arguments("ANY", 50)).tasks().getFirst().warehouseCode()).isNull();
    }

    @Test
    @DisplayName("the answer says whose tasks it lists, by fulfillment's order: oversight, design checks, own tasks")
    void visibility() {
        listed(0);
        CurrentUser manager = TestUsers.builder().role(Role.WAREHOUSE_MANAGER).scope(DataScope.WAREHOUSE)
                .warehouses("HCM").can("fulfillment-pick-lists", Action.READ)
                .can("fulfillment-task-oversight", Action.READ).can("fulfillment-design-reverification", Action.READ)
                .build();
        CurrentUser qc = TestUsers.builder().role(Role.QC_STAFF).scope(DataScope.WAREHOUSE).warehouses("HCM")
                .can("fulfillment-pick-lists", Action.READ).can("fulfillment-design-reverification", Action.READ)
                .build();

        assertThat(tool.run(arguments("ANY", 5)).visibility()).isEqualTo(FindFulfillmentTasksTool.Visibility.OWN_TASKS);
        when(users.current()).thenReturn(Optional.of(manager));
        assertThat(tool.run(arguments("ANY", 5)).visibility())
                .isEqualTo(FindFulfillmentTasksTool.Visibility.EVERY_TASK);
        when(users.current()).thenReturn(Optional.of(qc));
        assertThat(tool.run(arguments("ANY", 5)).visibility())
                .isEqualTo(FindFulfillmentTasksTool.Visibility.DESIGN_HOLDS);
    }

    @Test
    @DisplayName("the statuses offered are fulfillment's pick statuses but SHORT, which fulfillment never sets (a short "
            + "pick keeps its task PICKING), ANY first, so none is missing or invented")
    void statusesAreFulfillments() {
        assertThat(FindFulfillmentTasksTool.STATUSES.getFirst()).isEqualTo("ANY");
        assertThat(FindFulfillmentTasksTool.STATUSES.subList(1, FindFulfillmentTasksTool.STATUSES.size()))
                .containsExactlyElementsOf(Arrays.stream(PickStatus.values()).map(Enum::name)
                        .filter(status -> !status.equals("SHORT")).toList());
    }
}
