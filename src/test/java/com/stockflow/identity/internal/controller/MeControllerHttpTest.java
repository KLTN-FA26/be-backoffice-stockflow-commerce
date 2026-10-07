package com.stockflow.identity.internal.controller;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.AuthenticatedUserArgumentResolver;
import com.stockflow.common.security.CurrentUserProvider;
import com.stockflow.common.security.PermissionCatalog;
import com.stockflow.common.security.PermissionCatalogEntry;
import com.stockflow.identity.api.IdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code /me/permissions} without a caller: the whole catalog when security is off, 401 when on. */
class MeControllerHttpTest {

    private static final PermissionCatalog CATALOG = new PermissionCatalog(List.of(
            new PermissionCatalogEntry("procurement-suppliers", "Procurement", "Suppliers", "/admin/suppliers",
                    "/api/v1/suppliers", List.of(Action.VIEW_PAGE, Action.READ, Action.DELETE))));

    private static MockMvc mvc(boolean securityEnabled) {
        return MockMvcBuilders
                .standaloneSetup(new MeController(mock(IdentityService.class), CATALOG, securityEnabled))
                .setCustomArgumentResolvers(new AuthenticatedUserArgumentResolver(new CurrentUserProvider()))
                .setControllerAdvice()
                .build();
    }

    @Test
    void withSecurityOffEveryDeclaredPermissionIsReportedBecauseEveryEndpointIsOpen() throws Exception {
        mvc(false).perform(get("/api/v1/identity/me/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(3))
                .andExpect(jsonPath("$.data.permissions[0]").value("procurement-suppliers:DELETE"))
                .andExpect(jsonPath("$.data.dataScope").value("ALL"))
                .andExpect(jsonPath("$.data.roles.length()").value(0));
    }

    @Test
    void withSecurityOnACallWithoutAUserIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        mvc(true).perform(get("/api/v1/identity/me/permissions")))
                .hasRootCauseInstanceOf(com.stockflow.common.error.BusinessException.class);
    }
}
