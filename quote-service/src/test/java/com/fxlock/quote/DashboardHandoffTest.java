package com.fxlock.quote;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DashboardHandoffController.class, properties = "fxlock.internal-token=fixture-internal")
@Import({SecurityConfiguration.class, AccountValidation.class})
@ActiveProfiles("team")
class DashboardHandoffTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountClient accounts;
    String state = "a".repeat(43);

    @Test void authenticatedStartReachesM3AndQuoteRouteRemainsProtected() throws Exception {
        mvc.perform(get("/dashboard/start").with(user("registered-customer"))).andExpect(redirectedUrl("http://localhost:8083/dashboard/login"));
        mvc.perform(get("/dashboard/handoff").accept("text/html").param("state", state)).andExpect(status().is3xxRedirection());
    }

    String issue() throws Exception {
        String redirect = mvc.perform(get("/dashboard/handoff").param("state", state).with(user("registered-customer")))
            .andExpect(status().is3xxRedirection()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getRedirectedUrl();
        return UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("code");
    }

    void redeem(String code, String state, String token, int expected) throws Exception {
        var request = post("/internal/dashboard/redeem").contentType("application/json")
                .content("{\"code\":\"" + code + "\",\"state\":\"" + state + "\"}");
        if (token != null) request.header("X-Internal-Token", token);
        mvc.perform(request).andExpect(status().is(expected));
    }

    @Test void codesAreBackendProtectedSingleUseAndBoundToState() throws Exception {
        String code = issue();
        redeem(code, state, null, 401);
        redeem(code, state, "wrong", 401);
        mvc.perform(post("/internal/dashboard/redeem").header("X-Internal-Token", "fixture-internal")
                .contentType("application/json").content("{\"code\":\"" + code + "\",\"state\":\"" + state + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.customerRef").value("registered-customer"));
        redeem(code, state, "fixture-internal", 401);
        String another = issue();
        redeem(another, "b".repeat(43), "fixture-internal", 401);
        redeem(another, state, "fixture-internal", 401);
    }

    @Test void csrfIsStillRequiredForLogoutAndQuoteMutations() throws Exception {
        mvc.perform(post("/logout").with(user("registered-customer"))).andExpect(status().isForbidden());
        mvc.perform(post("/quotes").with(user("registered-customer"))).andExpect(status().isForbidden());
        mvc.perform(get("/dashboard/logout").with(user("registered-customer"))).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name='_csrf'")));
    }
}
