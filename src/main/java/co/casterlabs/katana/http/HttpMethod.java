package co.casterlabs.katana.http;

// Source: https://developer.mozilla.org/en-US/docs/Web/HTTP/Methods
public enum HttpMethod {
    // Data
    GET,
    HEAD,

    // Modifications
    POST,
    PUT,
    DELETE,
    PATCH,

    // Misc
    // CONNECT, // Not Implemented.
    // TRACE, // Not Implemented.

    // Internal
    /**
     * Never used.
     */
    @Deprecated
    OPTIONS,

}
