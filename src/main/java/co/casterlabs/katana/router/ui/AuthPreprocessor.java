package co.casterlabs.katana.router.ui;

import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import co.casterlabs.rhs.HttpStatus.StandardHttpStatus;
import co.casterlabs.rhs.protocol.HeaderValue;
import co.casterlabs.rhs.protocol.api.preprocessors.Preprocessor;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class AuthPreprocessor implements Preprocessor.Http<Void> {
    private static final int BRUTE_FORCE_THRESHOLD = 5;
    private static final long BRUTE_FORCE_TIMEOUT = TimeUnit.MINUTES.toMillis(5);

    private final UIRouter router;

    private Map<String, List<LoginAttempt>> loginAttempts = new HashMap<>();

    @Override
    public void preprocess(HttpSession session, PreprocessorContext<HttpResponse, Void> context) {
        String requestIpAddress = this.router.getConfig().isBehindProxy() ? //
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
            context.respondEarly(
                HttpResponse.newFixedLengthResponse(
                    StandardHttpStatus.TOO_MANY_REQUESTS,
                    "<!DOCTYPE html>"
                        + "<html>"
                        + "<h1>You are being rate-limited for too many failed login attempts.</h1>"
                        + "<h2>Try again in 5 minutes.</h2>"
                        + "</html>"
                ).mime("text/html;charset=UTF-8")
            );
            return;
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

                if (this.router.getConfig().getLogins().containsKey(username) && this.router.getConfig().getLogins().getString(username).equals(password)) {
                    return; // Allow
                }
            }

            // Reject! We'll keep track of this attempt in effort to prevent brute force
            // attacks. (With a soft cap to avoid memory exhaustion attacks.)
            attempts.add(new LoginAttempt());
            while (attempts.size() > 100) {
                attempts.remove(0);
            }
        }

        context.respondEarly(
            HttpResponse.newFixedLengthResponse(StandardHttpStatus.UNAUTHORIZED)
                .header("WWW-Authenticate", "Basic realm=\"Katana\"")
        );
    }

    private static class LoginAttempt {
        private long at = System.currentTimeMillis();
    }

}
