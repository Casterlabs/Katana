package co.casterlabs.katana.http;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;

@Getter
public class HttpResponse {
    /**
     * This response is used to signal to the server that we need to drop the
     * connection ASAP.
     */
    public static final HttpResponse NO_RESPONSE = HttpResponse.newFixedLengthResponse(HttpStatus.NO_RESPONSE, new byte[0]);
    public static final HttpResponse INTERNAL_ERROR = HttpResponse.newFixedLengthResponse(HttpStatus.INTERNAL_ERROR, new byte[0]);

    private @Getter(AccessLevel.NONE) Map<String, String> headers = new HashMap<>();

    private InputStream responseStream;
    private TransferEncoding mode;
    private HttpStatus status;
    private long length = -1;

    private HttpResponse(InputStream responseStream, TransferEncoding mode, HttpStatus status) {
        this.responseStream = responseStream;
        this.mode = mode;
        this.status = status;
    }

    /* -------------------------------- */
    /* Headers */
    /* -------------------------------- */

    public HttpResponse setMimeType(String type) {
        return this.putHeader("content-type", type);
    }

    public HttpResponse putHeader(@NonNull String key, @NonNull String value) {
        this.headers.put(key.toLowerCase(), value);
        return this;
    }

    public HttpResponse putAllHeaders(@NonNull Map<String, String> headers) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            this.headers.put(entry.getKey().toLowerCase(), entry.getValue());
        }
        return this;
    }

    public boolean hasHeader(@NonNull String key) {
        return this.headers.containsKey(key.toLowerCase());
    }

    public boolean removeHeader(@NonNull String key) {
        return this.headers.remove(key.toLowerCase()) != null;
    }

    public Map<String, String> getAllHeaders() {
        return new HashMap<>(this.headers);
    }

    /* -------------------------------- */
    /* Creating */
    /* -------------------------------- */

    public static HttpResponse newFixedLengthResponse(@NonNull HttpStatus status, @NonNull String body) {
        return newFixedLengthResponse(status, body.getBytes(StandardCharsets.UTF_8));
    }

    public static HttpResponse newFixedLengthResponse(@NonNull HttpStatus status, @NonNull byte[] body) {
        return newFixedLengthResponse(status, new ByteArrayInputStream(body), body.length);
    }

    public static HttpResponse newFixedLengthResponse(@NonNull HttpStatus status, @NonNull InputStream responseStream, long length) {
        HttpResponse response = new HttpResponse(responseStream, TransferEncoding.FIXED_LENGTH, status);

        response.length = length;

        return response;
    }

    public static HttpResponse newFixedLengthFileResponse(@NonNull HttpStatus status, @NonNull File file) throws FileNotFoundException {
        FileInputStream fin = new FileInputStream(file);

        return newFixedLengthResponse(status, fin, file.length());
    }

    public static HttpResponse newFixedLengthFileResponse(@NonNull HttpStatus status, @NonNull File file, long skip, long length) throws FileNotFoundException, IOException {
        FileInputStream fin = new FileInputStream(file);

        fin.skip(skip);

        return newFixedLengthResponse(status, fin, length);
    }

    public static HttpResponse newChunkedResponse(@NonNull HttpStatus status, @NonNull InputStream responseStream) {
        return new HttpResponse(responseStream, TransferEncoding.CHUNKED, status);
    }

    /* -------------------------------- */
    /* Misc */
    /* -------------------------------- */

    public static enum TransferEncoding {
        FIXED_LENGTH,
        CHUNKED;

    }

}
