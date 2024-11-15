package co.casterlabs.katana.router.http;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import co.casterlabs.katana.Util;
import co.casterlabs.katana.router.http.servlets.HttpServlet;
import co.casterlabs.rhs.HttpMethod;
import co.casterlabs.rhs.HttpStatus;
import co.casterlabs.rhs.protocol.http.HeaderValue;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;

public class HttpUtil {
    private static final String ERROR_HTML = "<!DOCTYPE html><html><head><title>$RESPONSECODE</title></head><body><h1>$RESPONSECODE</h1><p>$DESCRIPTION</p><br/><p><i>Running Casterlabs Katana, $ADDRESS</i></p></body></html>";

    private static final String ALLOWED_METHODS;

    static {
        List<String> methods = new ArrayList<>();
        for (HttpMethod method : HttpMethod.values()) {
            methods.add(method.name());
        }

        ALLOWED_METHODS = String.join(", ", methods);
    }

    public static void handleCors(Collection<HttpServlet> servlets, HttpSession session, HttpResponse response) {
        HeaderValue originHeader = session.headers().getSingle("Origin");

        if (originHeader == null) return;

        String[] split = originHeader.raw().split("://");

        if (split.length == 2) {
            String protocol = split[0];
            String referer = split[1].split("/")[0]; // Strip protocol and uri

            for (HttpServlet servlet : servlets) {
                if (Util.regexContains(servlet.getCorsAllowedHosts(), referer)) {
                    response.header("Access-Control-Allow-Origin", protocol + "://" + referer);
                    response.header("Access-Control-Allow-Methods", ALLOWED_METHODS);
                    response.header("Access-Control-Allow-Headers", "Authorization, *");
//                    session.getLogger().debug("Added CORS declaration.");
                    break;
                }
            }
        }
    }

    public static HttpResponse errorResponse(HttpSession session, HttpStatus status, String description) {
        return HttpResponse.newFixedLengthResponse(
            status,
            ERROR_HTML
                .replace("$RESPONSECODE", String.valueOf(status.statusCode()))
                .replace("$DESCRIPTION", description)
                .replace("$ADDRESS", String.format("%s:%d", session.uri().host, session.serverPort()))
        )
            .mime("text/html");
    }

}
