package co.casterlabs.katana.server.undertow;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.TrustManager;

import org.xnio.Options;
import org.xnio.Sequence;

import co.casterlabs.katana.http.HttpListener;
import co.casterlabs.katana.http.HttpResponse;
import co.casterlabs.katana.http.HttpResponse.TransferEncoding;
import co.casterlabs.katana.http.HttpServer;
import co.casterlabs.katana.http.HttpSession;
import co.casterlabs.katana.http.HttpStatus;
import co.casterlabs.katana.http.websocket.Websocket;
import co.casterlabs.katana.http.websocket.WebsocketListener;
import co.casterlabs.katana.http.websocket.WebsocketSession;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.util.HttpString;
import io.undertow.util.Protocols;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedBinaryMessage;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.StreamSourceFrameChannel;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class UndertowServer implements HttpListener, HttpHandler, WebSocketConnectionCallback {
    private static final HttpString HTTP_2_STATUS_DESCRIPTION = HttpString.tryFromString("x-http-status-description");
    private static final int BUFFER_SIZE = 4096;

    private FastLogger logger = new FastLogger();
    private Undertow undertow;
    private HttpServer server;
    private int port;

    private boolean running = false;
    private boolean secure = false;

    static {
        System.setProperty("org.jboss.logging.provider", "slf4j"); // This mutes it.
    }

    @SuppressWarnings("deprecation")
    public UndertowServer(HttpServer server, int port) {
        //@formatter:off
        this.undertow = Undertow.builder()
                .addHttpListener(port, "0.0.0.0")
                .setHandler(new BlockingHandler(Handlers.websocket(this, this)))
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setServerOption(UndertowOptions.ENABLE_SPDY, true)
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, false)
                .build();
        //@formatter:on

        this.port = port;
        this.server = server;
    }

    @SuppressWarnings("deprecation")
    public UndertowServer(HttpServer server, int port, KeyManager[] keyManagers, TrustManager[] trustManagers, String[] tls, String[] cipherSuites) {
        //@formatter:off
        this.undertow = Undertow.builder()
                .addHttpsListener(port, "0.0.0.0", keyManagers, trustManagers)

                .setHandler(new BlockingHandler(Handlers.websocket(this, this)))
                
                .setSocketOption(Options.SSL_ENABLED_CIPHER_SUITES, Sequence.of(cipherSuites))
                .setSocketOption(Options.SSL_ENABLED_PROTOCOLS, Sequence.of(tls))
               
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setServerOption(UndertowOptions.ENABLE_SPDY, true)
                .setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, false)
                
                .build();
        //@formatter:on

        this.port = port;
        this.secure = true;
        this.server = server;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        try {
            long start = System.currentTimeMillis();

            HttpSession session = new UndertowHttpSessionWrapper(exchange, this.logger, this.port);
            HttpResponse response = this.server.serveSession(session.getHost(), session, this.secure);

            if (response == null) {
                exchange.setStatusCode(HttpStatus.NOT_IMPLEMENTED.getStatusCode());
                exchange.setReasonPhrase(HttpStatus.NOT_IMPLEMENTED.getDescription());
            } else if (response.getStatus() == HttpStatus.NO_RESPONSE) {
                exchange.getConnection().close();
            } else {
                exchange.setStatusCode(response.getStatus().getStatusCode());

                // HTTP/2 doesn't have a status description, so we transfer it "manually".
                if (exchange.getProtocol().equals(Protocols.HTTP_2_0)) {
                    exchange.getResponseHeaders().add(HTTP_2_STATUS_DESCRIPTION, response.getStatus().getDescription());
                } else {
                    exchange.setReasonPhrase(response.getStatus().getDescription());
                }

                for (Map.Entry<String, String> entry : response.getAllHeaders().entrySet()) {
                    exchange.getResponseHeaders().add(HttpString.tryFromString(entry.getKey()), entry.getValue());
                }

                if (response.getMode() == TransferEncoding.FIXED_LENGTH) {
                    exchange.setResponseContentLength(response.getLength());
                }

                double time = (System.currentTimeMillis() - start) / 1000d;
                this.logger.debug("Served HTTP %s %s %s (%.2fs)", session.getMethod().name(), session.getRemoteIpAddress(), session.getHost() + session.getUri(), time);

                InputStream in = response.getResponseStream();
                OutputStream out = exchange.getOutputStream();

                byte[] buffer = new byte[BUFFER_SIZE];
                int len = 0;

                while ((len = in.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }

                in.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        WebsocketSession session = new UndertowWebsocketSessionWrapper(exchange, channel, this.logger, this.port);
        WebsocketListener listener = this.server.serveWebsocketSession(session.getHost(), session, this.secure);

        if (listener == null) {
            try {
                channel.sendClose();
            } catch (IOException ignored) {}
        } else {
            Websocket websocket = new UndertowWebsocketChannelWrapper(channel, session);

            listener.onOpen(websocket);

            channel.getReceiveSetter().set(new AbstractReceiveListener() {

                @Override
                protected void onFullTextMessage(WebSocketChannel channel, BufferedTextMessage message) {
                    listener.onText(websocket, message.getData());
                }

                @SuppressWarnings("deprecation")
                @Override
                protected void onFullBinaryMessage(WebSocketChannel channel, BufferedBinaryMessage message) {
                    for (ByteBuffer buffer : message.getData().getResource()) {
                        listener.onBinary(websocket, buffer.array());
                    }
                }

                @Override
                protected void onClose(WebSocketChannel webSocketChannel, StreamSourceFrameChannel channel) throws IOException {
                    try {
                        listener.onClose(websocket);

                        webSocketChannel.sendClose();
                    } catch (IOException ignored) {}
                }

                @Override
                protected void onError(WebSocketChannel channel, Throwable ignored) {}

            });

            channel.resumeReceives();
        }
    }

    @Override
    public void start() {
        this.undertow.start();
        this.running = true;
    }

    @Override
    public void stop() {
        this.undertow.stop();
        this.running = false;
    }

    @Override
    public int getPort() {
        return this.port;
    }

    @Override
    public boolean isAlive() {
        return this.running;
    }

}
