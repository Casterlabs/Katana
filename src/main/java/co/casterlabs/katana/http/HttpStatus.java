package co.casterlabs.katana.http;

import org.jetbrains.annotations.Nullable;

import lombok.Getter;
import lombok.NonNull;

// Source: https://en.wikipedia.org/wiki/List_of_HTTP_status_codes
@Getter
@NonNull
public enum HttpStatus {
    // 100: Continue
    // 101: Switching Protocols
    PROCESSING("Processing", 102), // (WebDAV)
    // 103: Checkpoint/Early Hints

    // Success
    OK("OK", 200),
    CREATED("Created", 201),
    ACCEPTED("Accepted", 202),
    NON_AUTHORITATIVE_INFORMATION("Non-Authoritative Information", 203),
    NO_CONTENT("No Content", 204),
    RESET_CONTENT("Reset Content", 205),
    PARTIAL_CONTENT("Partial Content", 206),
    MULTI_STATUS("Multi-Status", 207), // (WebDAV)
    ALREADY_REPORTED("Already Reported", 208), // (WebDAV)
    THIS_IS_FINE("This Is Fine", 218), // (Sora/Apache Web Server)
    INSTANCE_MANIPULATION_USED("Instance Manipulation Used", 226),

    // Redirection
    MULTIPLE_CHOICES("Multiple Choices", 300),
    MOVED_PERMANENTLY("Moved Permanently", 301),
    FOUND("Found", 302),
    SEE_OTHER("See Other", 303),
    NOT_MODIFIED("Not Modified", 304),
    USE_PROXY("Use Proxy", 305),
    // 306: Unused/Switch Proxy
    TEMPORARY_REDIRECT("Temporary Redirect", 307),
    PERMANENT_REDIRECT("Permanent Redirect", 308),

    // Client Error
    BAD_REQUEST("Bad Request", 400),
    UNAUTHORIZED("Unauthorized", 401),
    PAYMENT_REQUIRED("Payment Required", 402), // Reserved, but some APIs use it for paid rate limit increases.
    FORBIDDEN("Forbidden", 403),
    NOT_FOUND("Not Found", 404),
    METHOD_NOT_ALLOWED("Method Not Allowed", 405),
    NOT_ACCEPTABLE("Not Acceptable", 406),
    PROXY_AUTHENTICAION_REQUIRED("Proxy Authentication Required", 407),
    REQUEST_TIMEOUT("Request Timeout", 408),
    CONFLICT("Conflict", 409),
    GONE("Gone", 410),
    LENGTH_REQUIRED("Length Required", 411),
    PRECONDITION_FAILED("Precondition Failed", 412),
    PAYLOAD_TOO_LARGE("Payload Too Large", 413),
    REQUEST_URI_TOO_LONG("Request URI Too Long", 414),
    UNSUPPORTED_MEDIA_TYPE("Unsupported Media Type", 415),
    RANGE_NOT_SATISFIABLE("Requested Range Not Satisfiable", 416),
    EXPECTATION_FAILED("Expectation Failed", 417),
    IM_A_TEAPOT("I'm A Teapot", 418), // :D https://en.wikipedia.org/wiki/HTTP_418
    ENHANCE_YOUR_CALM("Enhance Your Calm", 420), // (Twitter)
    UNPROCESSABLE_ENTITY("Unprocessable Entity", 422), // (WebDAV)
    LOCKED("Locked", 423), // (WebDAV)
    FAILED_DEPENDENCY("Failed Dependency", 424), // (WebDAV)
    UPGRADE_REQUIRED("Upgrade Required", 426),
    PRECONDITION_REQUIRED("Precondition Required", 428),
    TOO_MANY_REQUESTS("Too Many Requests", 429),
    LOGIN_TIMEOUT("Login Timeout", 440), // (Microsoft IIS)
    NO_RESPONSE("No Response", 444), // (Sora/Nginx)
    RETRY_WITH("Retry With", 449), // (Microsoft IIS)
    UNAVAILABLE_FOR_LEAGAL_REASONS("Unavailable For Legal Reasons", 451),
    REQUEST_HEADER_TOO_LARGE("Request Header Too Large", 494), // (Nginx)
    HTTP_REQUEST_SENT_TO_HTTPS_PORT("HTTP Request Sent To HTTPS Port", 497), // (Nginx)
    INVALID_TOKEN("Invalid Token", 498), // (Esri)

    // Server Error
    INTERNAL_ERROR("Internal Server Error", 500),
    NOT_IMPLEMENTED("Not Implemented", 501),
    BAD_GATEWAY("Bad Gateway", 502),
    SERVICE_UNAVAILABLE("Service Unavailable", 503),
    GATEWAY_TIMEOUT("Gateway Timeout", 504),
    UNSUPPORTED_HTTP_VERSION("HTTP Version Not Supported", 505),
    VARIANT_ALSO_NEGOTIATES("Variant Also Negotiates", 506),
    INSUFFICIENT_STORATE("Insufficient Storage", 507), // (WebDAV)
    LOOP_DETECTED("Loop Detected", 508), // (WebDAV)
    BANDWIDTH_LIMIT_EXCEEDED("Badnwidth Limit Exceeded", 509), // (Apache Web Server / cPanel)
    NOT_EXTENDED("Not Extended", 510),
    NETWORK_AUTHENTICATION_REQUIRED("Network Authentication Required", 511),
    NETWORK_READ_TIMEOUT_ERROR("Network Read Timeout Error", 598), // Some proxies use it.
    NETWORK_CONNECT_TIMEOUT_ERROR("Network Connect Timeout Error", 599), // Some proxies use it.

    ;

    // TODO make sure this is always tied to the max status code value.
    private static final HttpStatus[] STATUS_BY_CODE = new HttpStatus[600];

    static {
        for (HttpStatus status : HttpStatus.values()) {
            STATUS_BY_CODE[status.statusCode] = status;
        }
    }

    private String statusString;
    private String description;
    private int statusCode;

    private HttpStatus(String description, int statusCode) {
        this.statusString = statusCode + " " + description;
        this.description = description;
        this.statusCode = statusCode;
    }

    public static @Nullable HttpStatus lookup(int code) {
        try {
            return STATUS_BY_CODE[code];
        } catch (ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return this.statusString;
    }

}
