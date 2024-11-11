package com.instipod.duouniversal;

import com.duosecurity.Client;
import com.duosecurity.exception.DuoException;
import com.duosecurity.model.Token;
import com.google.common.base.Strings;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.services.managers.AuthenticationManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class DuoUniversalAuthenticator implements Authenticator {
    public static final DuoUniversalAuthenticator SINGLETON = new DuoUniversalAuthenticator();
    private final static Logger logger = Logger.getLogger(DuoUniversalAuthenticator.class);

    private String getRedirectUrl(AuthenticationFlowContext context, Boolean forceToken) {
        if (!context.getExecution().isAlternative()) {
            return context.getRefreshUrl(false).toString();
        }

        // We only need to shim in an alternative case, as the user may be able to "try another way"
        MultivaluedMap<String, String> queryParams = context.getHttpRequest().getUri().getQueryParameters();
        String sessionCode;

        if (queryParams.containsKey("duo_code") && queryParams.containsKey("session_code") && !forceToken) {
            // Duo requires the same session_code as the first redirect in order to retrieve the token
            sessionCode = queryParams.getFirst("session_code");
        } else {
            sessionCode = context.generateAccessCode();
        }

        return context.getHttpRequest().getUri().getBaseUri().toString().replaceAll("/+$", "") +
                "/realms/" + URLEncoder.encode(context.getRealm().getName(), StandardCharsets.UTF_8) +
                "/duo-universal/callback" +
                "?kc_client_id=" + URLEncoder.encode(context.getAuthenticationSession().getClient().getClientId(), StandardCharsets.UTF_8) +
                "&kc_execution=" + URLEncoder.encode(context.getExecution().getId(), StandardCharsets.UTF_8) +
                "&kc_tab_id=" + URLEncoder.encode(context.getAuthenticationSession().getTabId(), StandardCharsets.UTF_8) +
                "&kc_session_code=" + URLEncoder.encode(sessionCode, StandardCharsets.UTF_8);
    }

    private Client initDuoClient(AuthenticationFlowContext context, String redirectUrl) throws DuoException {
        // default values
        AuthenticatorConfigModel authConfig = context.getAuthenticatorConfig();
        String clientId = authConfig.getConfig().get(DuoUniversalAuthenticatorFactory.DUO_INTEGRATION_KEY);
        String secret = authConfig.getConfig().get(DuoUniversalAuthenticatorFactory.DUO_SECRET_KEY);
        String hostname = authConfig.getConfig().get(DuoUniversalAuthenticatorFactory.DUO_API_HOSTNAME);
        String overrides = authConfig.getConfig().get(DuoUniversalAuthenticatorFactory.DUO_CUSTOM_CLIENT_IDS);

        if (overrides != null && !overrides.equalsIgnoreCase("")) {
            // multivalue string separator is ##
            String[] overridesSplit = overrides.split("##");

            for (String override : overridesSplit) {
                String[] parts = override.split(",");

                if (parts.length == 3 || parts.length == 4) {
                    String duoHostname;

                    if (parts.length == 3) {
                        duoHostname = hostname;
                    } else {
                        duoHostname = parts[3];
                    }

                    // valid entries have 3 or 4 parts: keycloak client id, duo id, duo secret, (optional) api hostname
                    String keycloakClient = parts[0];
                    String duoId = parts[1];
                    String duoSecret = parts[2];

                    if (keycloakClient.equalsIgnoreCase(context.getAuthenticationSession().getClient().getId())) {
                        // found a specific client override
                        clientId = duoId;
                        secret = duoSecret;
                        hostname = duoHostname;
                    }
                }
            }
        }

        return new Client.Builder(
                clientId,
                secret,
                hostname,
                redirectUrl.replaceAll("\\+", "%20")
        ).build();
    }

    private String getImpersonatorId(AuthenticationFlowContext flowContext) {
        AuthenticationManager.AuthResult authResult = AuthenticationManager.authenticateIdentityCookie(
                flowContext.getSession(),
                flowContext.getRealm(),
                true
        );

        if (authResult == null) {
            return null;
        }

        UserSessionModel userSession = authResult.getSession();
        Map<String, String> userSessionNotes = userSession.getNotes();

        // Check if we are impersonating a user, otherwise null
        return userSessionNotes.getOrDefault(ImpersonationSessionNote.IMPERSONATOR_ID.toString(), null);
    }

    private UserModel getImpersonatorOrUser(AuthenticationFlowContext flowContext) {
        String impersonatorId = getImpersonatorId(flowContext);
        UserModel baseUser = flowContext.getUser();

        if (impersonatorId == null) {
            return baseUser;
        }

        UserModel impersonatorUser = flowContext.getSession().users().getUserById(flowContext.getRealm(), impersonatorId);

        if (impersonatorUser != null) {
            return impersonatorUser;
        }

        return baseUser;
    }

    @Override
    public void authenticate(AuthenticationFlowContext authenticationFlowContext) {
        if (authenticationFlowContext.getAuthenticatorConfig() == null) {
            logger.error("Duo Authenticator is not configured! All authentications will fail.");
            authenticationFlowContext.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }

        Map<String, String> authConfigMap = authenticationFlowContext.getAuthenticatorConfig().getConfig();

        if (authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_API_HOSTNAME, "none").equalsIgnoreCase("none")) {
            // authenticator not configured
            logger.error("Duo Authenticator is missing API hostname configuration! All authentications will fail.");
            authenticationFlowContext.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }

        if (authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_INTEGRATION_KEY, "none").equalsIgnoreCase("none")) {
            // authenticator not configured
            logger.error("Duo Authenticator is missing Integration Key configuration! All authentications will fail.");
            authenticationFlowContext.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }

        if (authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_SECRET_KEY, "none").equalsIgnoreCase("none")) {
            // authenticator not configured
            logger.error("Duo Authenticator is missing Secret Key configuration! All authentications will fail.");
            authenticationFlowContext.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }

        String duoGroups = authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_GROUPS, "none");
        UserModel user;

        if (authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_USE_IMPERSONATOR, "false").equalsIgnoreCase("true")) {
            user = getImpersonatorOrUser(authenticationFlowContext);
        } else {
            user = authenticationFlowContext.getUser();
        }

        if (user == null) {
            // no username
            logger.error("Received a flow request with no user! Returning internal error.");
            authenticationFlowContext.failure(AuthenticationFlowError.INTERNAL_ERROR);
            return;
        }

        String username = user.getUsername();
        if (!duoRequired(duoGroups, user)) {
            String userGroupsStr = user.getGroupsStream().map(GroupModel::getName).collect(Collectors.joining(","));
            logger.infof("Skipping Duo MFA for %s based on group membership, groups=%s", username, userGroupsStr);
            authenticationFlowContext.success();
            return;
        }

        String regexMatch = authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_USERNAME_FORMATTER_REGEX_MATCH, "none");
        if (!regexMatch.equalsIgnoreCase("none")) {
            String regexReplace = authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_USERNAME_FORMATTER_REGEX_REPLACE, "");
            String newUsername = username.replaceAll(regexMatch, regexReplace);
            logger.infof("Used regex to update username to %s for user %s", newUsername, username);
            username = newUsername;
        }

        String customAttributeName = authConfigMap.getOrDefault(DuoUniversalAuthenticatorFactory.DUO_USERNAME_CUSTOM_ATTRIBUTE, "none");
        if (!customAttributeName.equalsIgnoreCase("none")) {
            String customAttributeValue = user.getFirstAttribute(customAttributeName);

            if(!Strings.isNullOrEmpty(customAttributeValue)){
                logger.infof("Using custom attribute as username %s for user %s", customAttributeValue, username);
                username = customAttributeValue;
            }
        }

        // determine the user desire
        // if a duo state is set, assume it is the second request
        boolean firstRequest = !(authenticationFlowContext.getAuthenticationSession().getAuthNote("DUO_STATE") != null && !authenticationFlowContext.getAuthenticationSession().getAuthNote("DUO_STATE").isEmpty());

        if (firstRequest) {
            // send client to duo to authenticate
            startDuoProcess(authenticationFlowContext, username);
            return;
        }

        // handle duo response
        String loginState = authenticationFlowContext.getAuthenticationSession().getAuthNote("DUO_STATE");
        String loginUsername = authenticationFlowContext.getAuthenticationSession().getAuthNote("DUO_USERNAME");

        MultivaluedMap<String, String> queryParams = authenticationFlowContext.getUriInfo().getQueryParameters();
        if (queryParams.containsKey("state") && queryParams.containsKey("duo_code")) {
            String state = queryParams.getFirst("state");
            String duoCode = queryParams.getFirst("duo_code");

            String redirectUrl = getRedirectUrl(authenticationFlowContext, false);

            boolean authSuccess = false;
            try {
                Client duoClient = initDuoClient(authenticationFlowContext, redirectUrl);
                Token token = duoClient.exchangeAuthorizationCodeFor2FAResult(duoCode, username);

                if (token != null && token.getAuth_result() != null) {
                    if (token.getAuth_result().getStatus().equalsIgnoreCase("allow")) {
                        authSuccess = true;
                    }
                }
            } catch (DuoException e) {
                logger.warn("There was a problem exchanging the Duo token. Returning start page.", e);
                startDuoProcess(authenticationFlowContext, username);
                return;
            }

            if (!loginState.equalsIgnoreCase(state)) {
                // sanity check the session
                logger.warn("Login state did not match saved value. Returning start page.");
                startDuoProcess(authenticationFlowContext, username);
                return;
            }
            if (!username.equalsIgnoreCase(loginUsername)) {
                // sanity check the session
                logger.warnf("Duo username (%s) did not match saved value (%s). Returning start page.", loginUsername, username);
                startDuoProcess(authenticationFlowContext, username);
                return;
            }

            if (authSuccess) {
                authenticationFlowContext.success();
            } else {
                LoginFormsProvider provider = authenticationFlowContext.form().addError(new FormMessage(null, "You did not pass multifactor verification."));
                authenticationFlowContext.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, provider.createErrorPage(Response.Status.FORBIDDEN));
            }
        } else {
            // missing required information
            logger.warn("Received a Duo callback that was missing information. Starting over.");
            startDuoProcess(authenticationFlowContext, username);
        }
    }

    private void startDuoProcess(AuthenticationFlowContext authenticationFlowContext, String username) {
        AuthenticatorConfigModel authConfig = authenticationFlowContext.getAuthenticatorConfig();
        // authConfig should be safe at this point, as it will be checked in the calling method

        String redirectUrl = getRedirectUrl(authenticationFlowContext, true);
        Client duoClient;

        try {
            duoClient = initDuoClient(authenticationFlowContext, redirectUrl);
            duoClient.healthCheck();
        } catch (DuoException e) {
            // Duo is not available
            logger.warn("Duo initialization failed with exception: " + e.getMessage(), e);

            if (authConfig.getConfig().getOrDefault(DuoUniversalAuthenticatorFactory.DUO_FAIL_SAFE, "false").equalsIgnoreCase("false")) {
                // fail secure, deny login
                authenticationFlowContext.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
            } else {
                authenticationFlowContext.success();
            }

            return;
        }

        String loginState = duoClient.generateState();
        authenticationFlowContext.getAuthenticationSession().setAuthNote("DUO_STATE", loginState);
        authenticationFlowContext.getAuthenticationSession().setAuthNote("DUO_USERNAME", username);

        try {
            String startingUrl = duoClient.createAuthUrl(username, loginState);
            authenticationFlowContext.challenge(Response.seeOther(new URI(startingUrl)).build());
        } catch (DuoException | URISyntaxException e) {
            logger.warn("Authentication against Duo failed with exception: " + e.getMessage(), e);

            if (authConfig.getConfig().getOrDefault(DuoUniversalAuthenticatorFactory.DUO_FAIL_SAFE, "true").equalsIgnoreCase("true")) {
                authenticationFlowContext.success();
            } else {
                // fail secure, deny login
                authenticationFlowContext.failure(AuthenticationFlowError.INVALID_CREDENTIALS);
            }
        }
    }

    private boolean duoRequired(String duoGroups, UserModel user) {
        if (duoGroups == null || duoGroups.isBlank() || duoGroups.strip().equals("none")) {
            return true;
        }

        return user.getGroupsStream().anyMatch(g -> Arrays.asList(duoGroups.split(",")).contains(g.getName()));
    }

    @Override
    public void action(AuthenticationFlowContext authenticationFlowContext) {

    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession keycloakSession, RealmModel realmModel, UserModel userModel) {

    }

    @Override
    public void close() {

    }
}
