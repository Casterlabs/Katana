package co.casterlabs.katana.router.ui;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import co.casterlabs.katana.router.ui.AuthPreprocessor.AuthorizedUser;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rhs.HttpStatus.StandardHttpStatus;
import co.casterlabs.rhs.protocol.HeaderValue;
import co.casterlabs.rhs.protocol.api.preprocessors.Preprocessor;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@RequiredArgsConstructor
public class AuthPreprocessor implements Preprocessor.Http<AuthorizedUser> {
    public static final String COOKIE_NAME = "katana_oidc";

    private static final AuthorizedUser INVALID_USER = new AuthorizedUser(0L, "invalid");

    private static final int BRUTE_FORCE_THRESHOLD = 5;
    private static final long BRUTE_FORCE_TIMEOUT = TimeUnit.MINUTES.toMillis(5);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final OkHttpClient okhttp = new OkHttpClient();

    private final UIRouter router;

    private Map<String, AuthorizedUser> authCookies = new HashMap<>();
    private Map<String, List<LoginAttempt>> loginAttempts = new HashMap<>();

    @Override
    public void preprocess(HttpSession session, PreprocessorContext<HttpResponse, AuthorizedUser> context) {
        try {
            String foundAuthCookie = null;
            for (HeaderValue cookie : session.headers().getSingleOrDefault("Cookie", HeaderValue.EMPTY).delimited(";")) {
                if (!cookie.raw().startsWith(COOKIE_NAME + '=')) continue;
                foundAuthCookie = cookie.raw().substring(COOKIE_NAME.length() + 1);
                break;
            }

            if (foundAuthCookie != null) {
                AuthorizedUser user = this.authCookies.getOrDefault(foundAuthCookie, INVALID_USER);
                if (System.currentTimeMillis() < user.expiresAt) {
                    context.attachment(user);
                    return; // Allow!
                } else {
                    // Expired or invalid. We'll remove it and fall down below:
                    this.authCookies.remove(foundAuthCookie);
                }
            }

            // Invalid cookie. Attempt to authenticate:
            context.respondEarly(
                HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
                    .header("Location", "/login")
            );
        } catch (Throwable t) {
            this.router.getLogger().fatal("An error occurred whilst preprocessing authentication:\n%s", t);
            context.respondEarly(
                HttpResponse.newFixedLengthResponse(
                    StandardHttpStatus.INTERNAL_ERROR,
                    "<!DOCTYPE html>"
                        + "<html>"
                        + "<h1>Internal error</h1>"
                        + "<h2>This issue has been logged to the console.</h2>"
                        + "</html>"
                ).mime("text/html;charset=UTF-8")
            );
        }
    }

    public HttpResponse handleOAuth(HttpSession session) throws IOException {
        // We'll look for the OAuth ?code query information first, because we might've
        // already started the OAuth flow.
        if (session.uri().query.containsKey("code")) {
            String postPayload = Map.of(
                "client_id", this.router.getConfig().oauth.clientId,
                "client_secret", this.router.getConfig().oauth.clientSecret,
                "grant_type", "authorization_code",
                "redirect_uri", this.router.getConfig().oauth.redirectUrl + "/login/oauth",
                "code", session.uri().query.get("code").get(0)
            )
                .entrySet()
                .stream()
                .map(e -> String.format("%s=%s", e.getKey(), e.getValue()))
                .reduce((a, b) -> a + "&" + b)
                .get();

            // Post it to the token endpoint.
            JsonObject tokenResponse = sendJsonRequest(
                new Request.Builder()
                    .url(this.router.getConfig().oauth.tokenUrl)
                    .post(RequestBody.create(postPayload, MediaType.parse("application/x-www-form-urlencoded")))
                    .build()
            );

            String accessToken = tokenResponse.getString("access_token");

            JsonObject userInfo = sendJsonRequest(
                new Request.Builder()
                    .url(this.router.getConfig().oauth.userInfoUrl)
                    .header("Authorization", String.format("Bearer %s", accessToken))
                    .build()
            );

            String identifier = userInfo.getString(this.router.getConfig().oauth.identifier);
            long expiresIn = tokenResponse.getNumber("expires_in").longValue();

            if (this.router.getConfig().oauth.allowedUserIds.contains(identifier)) {
                return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
                    .header("Set-Cookie", issueCookie(expiresIn, identifier))
                    .header("Location", "/");
            } else {
                return HttpResponse.newFixedLengthResponse(
                    StandardHttpStatus.UNAUTHORIZED,
                    "<!DOCTYPE html>"
                        + "<html>"
                        + "<h1>Your account is not allowed to log in</h1>"
                        + "<h2>Your user identifier is: " + identifier + "</h2>"
                        + "<a href=\"/login\">Try again</a>"
                        + "</html>"
                ).mime("text/html;charset=UTF-8");
            }
        }

        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
            .header(
                "Location",
                String.format(
                    "%s?response_type=code&client_id=%s&redirect_uri=%s&scope=%s&state=%s",
                    this.router.getConfig().oauth.authorizationUrl,
                    this.router.getConfig().oauth.clientId,
                    this.router.getConfig().oauth.redirectUrl + "/login/oauth",
                    this.router.getConfig().oauth.scope,
                    UUID.randomUUID().toString()
                )
            );
    }

    /**
     * Has to be accessible for the /login/basic endpoint.
     */
    public HttpResponse handleBasic(HttpSession session) {
        String requestIpAddress = this.router.getConfig().isBehindProxy ? //
            session.headers().getSingle("X-Forwarded-For").raw() : //
            session.remoteNetworkAddress();

        List<LoginAttempt> attempts = this.loginAttempts.get(requestIpAddress);
        if (attempts == null) {
            attempts = new LinkedList<>();
            this.loginAttempts.put(requestIpAddress, attempts);
        }

        // Filter out attempts older than 5 minutes
        attempts.removeIf(attempt -> System.currentTimeMillis() - attempt.at > BRUTE_FORCE_TIMEOUT);

        if (attempts.size() >= BRUTE_FORCE_THRESHOLD) {
            return HttpResponse.newFixedLengthResponse(
                StandardHttpStatus.TOO_MANY_REQUESTS,
                "<!DOCTYPE html>"
                    + "<html>"
                    + "<h1>You are being rate-limited for too many failed login attempts.</h1>"
                    + "<h2>Try again in 5 minutes.</h2>"
                    + "</html>"
            ).mime("text/html;charset=UTF-8");
        }

        String authorization = session.headers().getSingleOrDefault("Authorization", HeaderValue.EMPTY).raw();
        if (authorization.startsWith("Basic ")) {
            String[] split = new String(
                Base64.getDecoder().decode(
                    authorization.substring("Basic ".length())
                )
            ).split(":");

            if (split.length == 2) {
                String username = split[0];
                String password = split[1];

                if (this.router.getConfig().logins.getOrDefault(username, "").equals(password)) {
                    final long EXPIRES_IN_SECONDS = TimeUnit.HOURS.toSeconds(4);
                    return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
                        .header("Set-Cookie", issueCookie(EXPIRES_IN_SECONDS, username))
                        .header("Location", "/");
                }
            }

            // Reject! We'll keep track of this attempt in effort to prevent brute force
            // attacks. (With a soft cap to avoid memory exhaustion attacks.)
            attempts.add(new LoginAttempt());
            while (attempts.size() > 100) {
                attempts.remove(0);
            }
        }

        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.UNAUTHORIZED)
            .header("WWW-Authenticate", "Basic realm=\"Katana\", charset=\"UTF-8\"");
    }

    private String issueCookie(long expiresInSeconds, String username) {
        final String VALID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890";
        char[] tokenCh = new char[24];
        for (int idx = 0; idx < tokenCh.length; idx++) { // length of the random string.
            int chIdx = SECURE_RANDOM.nextInt(VALID_CHARS.length());
            tokenCh[idx] = VALID_CHARS.charAt(chIdx);
        }
        String token = new String(tokenCh);

        this.authCookies.put(token, new AuthorizedUser(System.currentTimeMillis() + expiresInSeconds, username));
        return String.format("%s=%s; Max-Age=%d; Path=/; SameSite=Lax; HttpOnly", COOKIE_NAME, token, expiresInSeconds);
    }

    private static class LoginAttempt {
        private long at = System.currentTimeMillis();
    }

    private static JsonObject sendJsonRequest(Request req) throws IOException {
        try (Response response = okhttp.newCall(req).execute()) {
            String json = response.body().string();
            return Rson.DEFAULT.fromJson(json, JsonObject.class);
        }
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @JsonClass(exposeAll = true)
    public static class AuthorizedUser {
        public long expiresAt;
        public String name;
    }

}
