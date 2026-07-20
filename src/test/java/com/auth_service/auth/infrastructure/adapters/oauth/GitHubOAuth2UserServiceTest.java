package com.auth_service.auth.infrastructure.adapters.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubOAuth2UserServiceTest {

    private static final String ACCESS_TOKEN_VALUE = "test-access-token";

    @SuppressWarnings("unchecked")
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = mock(OAuth2UserService.class);

    @Test
    void resolvesPrimaryVerifiedEmailAndMergesSyntheticAttributes() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        when(delegate.loadUser(any())).thenReturn(baseGitHubUser());

        server.expect(requestTo("https://api.github.com/user/emails"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + ACCESS_TOKEN_VALUE))
                .andRespond(withSuccess("""
                        [
                          {"email":"secondary@example.com","primary":false,"verified":true},
                          {"email":"primary@example.com","primary":true,"verified":true}
                        ]
                        """, MediaType.APPLICATION_JSON));

        GitHubOAuth2UserService service = new GitHubOAuth2UserService(delegate, restClient);
        OAuth2User result = service.loadUser(githubUserRequest());

        assertThat(result.getName()).isEqualTo("12345");
        assertThat(result.<String>getAttribute("email")).isEqualTo("primary@example.com");
        assertThat(result.<Boolean>getAttribute("email_verified")).isTrue();
        server.verify();
    }

    @Test
    void noPrimaryVerifiedEmailResultsInUnverifiedNullEmail() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        when(delegate.loadUser(any())).thenReturn(baseGitHubUser());

        server.expect(requestTo("https://api.github.com/user/emails"))
                .andRespond(withSuccess("""
                        [
                          {"email":"unverified@example.com","primary":true,"verified":false}
                        ]
                        """, MediaType.APPLICATION_JSON));

        GitHubOAuth2UserService service = new GitHubOAuth2UserService(delegate, restClient);
        OAuth2User result = service.loadUser(githubUserRequest());

        assertThat(result.<String>getAttribute("email")).isNull();
        assertThat(result.<Boolean>getAttribute("email_verified")).isFalse();
        server.verify();
    }

    @Test
    void emailLookupFailureBecomesControlledOAuth2AuthenticationException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        when(delegate.loadUser(any())).thenReturn(baseGitHubUser());

        server.expect(requestTo("https://api.github.com/user/emails"))
                .andRespond(withServerError());

        GitHubOAuth2UserService service = new GitHubOAuth2UserService(delegate, restClient);

        assertThatThrownBy(() -> service.loadUser(githubUserRequest()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
    }

    @Test
    void malformedEmailFieldTypeBecomesControlledOAuth2AuthenticationException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        when(delegate.loadUser(any())).thenReturn(baseGitHubUser());

        server.expect(requestTo("https://api.github.com/user/emails"))
                .andRespond(withSuccess("""
                        [
                          {"email":12345,"primary":true,"verified":true}
                        ]
                        """, MediaType.APPLICATION_JSON));

        GitHubOAuth2UserService service = new GitHubOAuth2UserService(delegate, restClient);

        assertThatThrownBy(() -> service.loadUser(githubUserRequest()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
    }

    @Test
    void nullElementInEmailsArrayBecomesControlledOAuth2AuthenticationException() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        when(delegate.loadUser(any())).thenReturn(baseGitHubUser());

        server.expect(requestTo("https://api.github.com/user/emails"))
                .andRespond(withSuccess("""
                        [
                          null,
                          {"email":"primary@example.com","primary":true,"verified":true}
                        ]
                        """, MediaType.APPLICATION_JSON));

        GitHubOAuth2UserService service = new GitHubOAuth2UserService(delegate, restClient);

        assertThatThrownBy(() -> service.loadUser(githubUserRequest()))
                .isInstanceOf(OAuth2AuthenticationException.class);
        server.verify();
    }

    @Test
    void nonGitHubRegistrationDelegatesWithoutCallingGitHubApi() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        OAuth2User googleUser = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"), Map.of("sub", "abc"), "sub");
        when(delegate.loadUser(any())).thenReturn(googleUser);

        GitHubOAuth2UserService service = new GitHubOAuth2UserService(delegate, restClient);
        OAuth2User result = service.loadUser(googleUserRequest());

        assertThat(result).isSameAs(googleUser);
    }

    private static OAuth2User baseGitHubUser() {
        return new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("id", "12345", "login", "octocat"),
                "id");
    }

    private static OAuth2UserRequest githubUserRequest() {
        return new OAuth2UserRequest(clientRegistration("github"), accessToken());
    }

    private static OAuth2UserRequest googleUserRequest() {
        return new OAuth2UserRequest(clientRegistration("google"), accessToken());
    }

    private static OAuth2AccessToken accessToken() {
        return new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN_VALUE,
                Instant.now(), Instant.now().plusSeconds(3600));
    }

    private static ClientRegistration clientRegistration(String registrationId) {
        return ClientRegistration.withRegistrationId(registrationId)
                .clientId("client-id")
                .clientSecret("client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://example.com/authorize")
                .tokenUri("https://example.com/token")
                .userInfoUri("https://example.com/userinfo")
                .userNameAttributeName("id")
                .clientName(registrationId)
                .build();
    }
}
