package eu.siacs.conversations.xmpp;

import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;

public class OAuthAuthorizationTest {

    private static final Jid ALICE = Jid.of("alice@example.com");

    private static OAuthAuthorization.OpenIdConfiguration configuration() {
        final var configuration = new OAuthAuthorization.OpenIdConfiguration();
        configuration.authorizationEndpoint = "https://idp.example.com/authorize";
        configuration.tokenEndpoint = "https://idp.example.com/token";
        configuration.registrationEndpoint = "https://idp.example.com/register";
        return configuration;
    }

    private static OAuthAuthorization.ClientRegistrationResponse registration() {
        final var registration = new OAuthAuthorization.ClientRegistrationResponse();
        registration.clientId = "client-1";
        registration.clientSecret = "secret";
        registration.tokenEndpointAuthMethod = "client_secret_basic";
        registration.redirectUris = ImmutableList.of(OAuthAuthorization.REDIRECT_URI);
        return registration;
    }

    private static void assertRefused(final String url) {
        try {
            OAuthAuthorization.requireEndpoint(url, "endpoint");
            Assert.fail(url + " should be refused");
        } catch (final IllegalStateException expected) {
            // refused
        }
    }

    @Test
    public void httpsEndpointIsAccepted() {
        Assert.assertEquals(
                "idp.example.com",
                OAuthAuthorization.requireEndpoint("https://idp.example.com/x", "endpoint").host());
    }

    @Test
    public void unsafeEndpointsAreRefused() {
        assertRefused(null);
        assertRefused("");
        assertRefused("not a url");
        assertRefused("http://idp.example.com/x");
        assertRefused("ftp://idp.example.com/x");
        assertRefused("https://localhost/x");
        assertRefused("https://127.0.0.1/x");
        assertRefused("https://192.168.1.1/x");
        assertRefused("https://10.0.0.5/x");
        assertRefused("https://[::1]/x");
        assertRefused("https://router.local/x");
        assertRefused("https://user:pass@idp.example.com/x");
    }

    @Test
    public void stateFindsItsAccount() {
        final String state = OAuthAuthorization.buildState(Jid.of("alice@example.com/phone"), new byte[32]);
        Assert.assertEquals(ALICE, OAuthAuthorization.accountFromState(state));
    }

    @Test
    public void foreignStateHasNoAccount() {
        Assert.assertNull(OAuthAuthorization.accountFromState(null));
        Assert.assertNull(OAuthAuthorization.accountFromState(""));
        Assert.assertNull(OAuthAuthorization.accountFromState("***"));
        // no separator
        Assert.assertNull(OAuthAuthorization.accountFromState("YWxpY2VAZXhhbXBsZS5jb20"));
        // separator first: empty address
        Assert.assertNull(OAuthAuthorization.accountFromState("AGFiYw"));
    }

    /** RFC 7636 appendix B. */
    @Test
    public void pkceChallengeMatchesTheRfc() {
        Assert.assertEquals(
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                OAuthAuthorization.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    public void authorizationUrlCarriesPkceAndOurRedirect() {
        final byte[] verifier = new byte[32];
        java.util.Arrays.fill(verifier, (byte) 0x5a);
        final var pending =
                OAuthAuthorization.buildPendingAuthorization(
                        ALICE, configuration(), registration(), new byte[32], verifier);
        final var url = pending.authorizationUrl;
        Assert.assertEquals("idp.example.com", url.host());
        Assert.assertEquals("code", url.queryParameter("response_type"));
        Assert.assertEquals("client-1", url.queryParameter("client_id"));
        Assert.assertEquals(OAuthAuthorization.REDIRECT_URI, url.queryParameter("redirect_uri"));
        Assert.assertEquals("S256", url.queryParameter("code_challenge_method"));
        Assert.assertEquals(
                OAuthAuthorization.codeChallenge(pending.codeVerifier),
                url.queryParameter("code_challenge"));
        Assert.assertEquals(pending.state, url.queryParameter("state"));
        Assert.assertEquals(ALICE, OAuthAuthorization.accountFromState(pending.state));
        // the verifier itself never goes to the browser
        Assert.assertFalse(url.toString().contains(pending.codeVerifier));
    }

    @Test(expected = IllegalStateException.class)
    public void providerCannotSwapTheRedirect() {
        final var registration = registration();
        registration.redirectUris = ImmutableList.of("https://evil.example.com/cb");
        OAuthAuthorization.buildPendingAuthorization(
                ALICE, configuration(), registration, new byte[32], new byte[32]);
    }

    @Test(expected = IllegalStateException.class)
    public void plainHttpAuthorizationEndpointIsRefused() {
        final var configuration = configuration();
        configuration.authorizationEndpoint = "http://idp.example.com/authorize";
        OAuthAuthorization.buildPendingAuthorization(
                ALICE, configuration, registration(), new byte[32], new byte[32]);
    }

    @Test(expected = IllegalStateException.class)
    public void registrationWithoutClientIdIsRefused() {
        final var registration = registration();
        registration.clientId = "";
        OAuthAuthorization.buildPendingAuthorization(
                ALICE, configuration(), registration, new byte[32], new byte[32]);
    }
}
