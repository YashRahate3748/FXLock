package com.fxlock.quote;

import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AccountPageController.class)
@Import({SecurityConfiguration.class, AccountValidation.class, AccountPageTest.SessionProbe.class})
@ActiveProfiles("team")
class AccountPageTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountClient accounts;

    @RestController
    static class SessionProbe {
        @GetMapping("/session-probe") String principal(Principal principal) { return principal.getName(); }
    }

    @Test void mainFormUsesEmailAndOffersSignUpWithoutCaptcha() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
            .andExpect(content().string(containsString("<label for=\"username\">Email</label>")))
            .andExpect(content().string(containsString("name=\"username\" type=\"email\"")))
            .andExpect(content().string(containsString("name=\"_csrf\"")))
            .andExpect(content().string(containsString("Sign up")))
            .andExpect(content().string(not(containsString("Security code"))));
    }

    @Test void explicitDemoFormRetainsOriginalLogins() throws Exception {
        mvc.perform(get("/login").param("demo", "true"))
            .andExpect(content().string(containsString("<label for=\"username\">Username</label>")))
            .andExpect(content().string(containsString("action=\"/demo-login\"")));
        for (String name : new String[]{"customer-demo", "customer-other"}) {
            mvc.perform(post("/demo-login").with(csrf()).param("username", name).param("password", "learn-fxlock"))
                .andExpect(redirectedUrl("/")).andExpect(authenticated().withUsername(name));
        }
        verifyNoInteractions(accounts);
    }

    @Test void databaseLoginUsesM3IdentityAndKeepsPasswordOutOfSession() throws Exception {
        String password = "  ThrowawayPassword!2026  ";
        when(accounts.verify("customer@example.com", password))
            .thenReturn(new AccountClient.Account("CUST-database-user", "Customer Name", "customer@example.com"));
        var before = new MockHttpSession();
        String oldId = before.getId();
        var result = mvc.perform(post("/login").session(before).with(csrf())
                .param("username", " Customer@Example.com ").param("password", password))
            .andExpect(redirectedUrl("/dashboard/start")).andExpect(authenticated().withUsername("CUST-database-user")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        var context = (org.springframework.security.core.context.SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(context.getAuthentication().getPrincipal()).isEqualTo("CUST-database-user");
        mvc.perform(get("/session-probe").session(session)).andExpect(content().string("CUST-database-user"));
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(redirectedUrl("http://localhost:8083/dashboard/logout"));
        assertThat(session.isInvalid()).isTrue();
        verify(accounts).verify("customer@example.com", password);
    }

    @Test void shortPasswordsCanRegisterAndLoginWithoutAMinimumLength() throws Exception {
        String password = "x";
        mvc.perform(get("/register"))
            .andExpect(content().string(not(containsString("minlength="))));
        mvc.perform(post("/register").with(csrf()).param("fullName", "Name")
                .param("email", "short@example.com").param("password", password).param("confirmPassword", password))
            .andExpect(redirectedUrl("/login?registered"));
        verify(accounts).register("Name", "short@example.com", password);
        when(accounts.verify("short@example.com", password))
            .thenReturn(new AccountClient.Account("CUST-short-fixture", "Name", "short@example.com"));
        mvc.perform(post("/login").with(csrf()).param("username", "short@example.com").param("password", password))
            .andExpect(authenticated().withUsername("CUST-short-fixture"));
        verify(accounts).verify("short@example.com", password);
        doThrow(new AccountClient.AccountFailure(AccountClient.Failure.INVALID_CREDENTIALS))
            .when(accounts).verify("short@example.com", "y");
        mvc.perform(post("/login").with(csrf()).param("username", "short@example.com").param("password", "y"))
            .andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
        verify(accounts).verify("short@example.com", "y");
    }

    @Test void failuresAndLegacyResetNeverAuthenticateAsDemo() throws Exception {
        for (var failure : new AccountClient.Failure[]{AccountClient.Failure.INVALID_CREDENTIALS,
                AccountClient.Failure.UNAVAILABLE, AccountClient.Failure.PASSWORD_RESET_REQUIRED}) {
            doThrow(new AccountClient.AccountFailure(failure)).when(accounts).verify("customer@example.com", "valid-password");
            String suffix = failure == AccountClient.Failure.INVALID_CREDENTIALS ? "error"
                    : failure == AccountClient.Failure.PASSWORD_RESET_REQUIRED ? "resetRequired" : "unavailable";
            mvc.perform(post("/login").with(csrf()).param("username", "customer@example.com").param("password", "valid-password"))
                .andExpect(unauthenticated()).andExpect(redirectedUrl("/login?" + suffix));
        }
        verify(accounts, times(3)).verify("customer@example.com", "valid-password");
    }

    @Test void emailRouteRejectsDemoUsernamesWithoutFallingBack() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "customer-demo").param("password", "learn-fxlock"))
            .andExpect(unauthenticated()).andExpect(redirectedUrl("/login?error"));
        verifyNoInteractions(accounts);
    }

    @Test void credentialPostsRequireCsrf() throws Exception {
        for (String route : new String[]{"/login", "/demo-login", "/logout", "/register"}) {
            mvc.perform(post(route)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(accounts);
    }

    @Test void signupAsksForAllDetailsAndForwardsTheExactPasswordOnce() throws Exception {
        mvc.perform(get("/register")).andExpect(status().isOk())
            .andExpect(content().string(containsString("name=\"fullName\"")))
            .andExpect(content().string(containsString("name=\"email\"")))
            .andExpect(content().string(containsString("name=\"password\"")))
            .andExpect(content().string(containsString("name=\"confirmPassword\"")));
        mvc.perform(post("/register").with(csrf()).param("fullName", " Customer Name ").param("email", " Customer@Example.com ")
                .param("password", "  ThrowawayPassword!2026  ").param("confirmPassword", "  ThrowawayPassword!2026  "))
            .andExpect(redirectedUrl("/login?registered")).andExpect(unauthenticated());
        verify(accounts).register("Customer Name", "customer@example.com", "  ThrowawayPassword!2026  ");
    }

    @Test void signupValidationNeverForwardsOrRepopulatesPasswords() throws Exception {
        for (String password : new String[]{"", "   ", "a".repeat(73), "\u00e9".repeat(37)}) {
            var result = mvc.perform(post("/register").with(csrf()).param("fullName", "Name").param("email", "customer@example.com")
                    .param("password", password).param("confirmPassword", password))
                .andExpect(view().name("register")).andExpect(model().attributeExists("errors")).andReturn();
            assertThat(result.getModelAndView().getModel()).doesNotContainKeys("password", "confirmPassword");
            assertThat(result.getResponse().getContentAsString()).doesNotContain("value=\"" + password + "\"");
        }
        mvc.perform(post("/register").with(csrf()).param("fullName", " ").param("email", "bad-email")
                .param("password", "valid-password").param("confirmPassword", "different-password"))
            .andExpect(content().string(containsString("Passwords must match")))
            .andExpect(content().string(containsString("Enter a full name")));
        verifyNoInteractions(accounts);
    }

    @Test void duplicatesAndUncertainSignupAreReportedWithoutRetries() throws Exception {
        for (var failure : new AccountClient.Failure[]{AccountClient.Failure.CONFLICT, AccountClient.Failure.UNAVAILABLE}) {
            doThrow(new AccountClient.AccountFailure(failure)).when(accounts).register("Name", "customer@example.com", "valid-password");
            var result = mvc.perform(post("/register").with(csrf()).param("fullName", "Name").param("email", "customer@example.com")
                    .param("password", "valid-password").param("confirmPassword", "valid-password"))
                .andExpect(content().string(containsString(failure == AccountClient.Failure.CONFLICT
                    ? "already exists" : "Try signing in before submitting again"))).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("value=\"valid-password\"");
        }
        verify(accounts, times(2)).register("Name", "customer@example.com", "valid-password");
    }
}
