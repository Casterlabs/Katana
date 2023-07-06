package co.casterlabs.katana.router.tcp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import co.casterlabs.katana.Katana;
import co.casterlabs.katana.router.KatanaRouter;
import co.casterlabs.rakurai.io.IOUtil;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;

public class TcpRouter implements KatanaRouter<TcpRouterConfiguration> {
    private static final int BUFFER_SIZE = 16 * 1024 * 1024; // 16mb.

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private FastLogger logger;
    private Katana katana;

    private TcpRouterConfiguration config;

    private List<Socket> connectedClients = new LinkedList<>();
    private ServerSocket serverSocket;

    public TcpRouter(TcpRouterConfiguration config, Katana katana) {
        this.logger = new FastLogger(String.format("TcpServer (%s)", config.getName()));
        this.katana = katana;
        this.loadConfig(config);
    }

    @Override
    public void loadConfig(TcpRouterConfiguration config) {
        this.config = config;
    }

    private void doRead() {
        Socket targetSocket = null;

        try {
            Socket clientSocket = this.serverSocket.accept();
            this.connectedClients.add(clientSocket);

            FastLogger sessionLogger = this.logger.createChild("Connection: " + formatAddress(clientSocket));
            sessionLogger.debug("New connection...");

            clientSocket.setTcpNoDelay(true);
            sessionLogger.trace("Set TCP_NODELAY.");

            // TODO Connect to target.
            targetSocket = new Socket(this.config.getForwardTo(), this.config.getForwardToPort());

            // Read thread.
            this.executor.execute(() -> {
                try {
                    byte[] buf = new byte[BUFFER_SIZE];
                    InputStream in = clientSocket.getInputStream();

                    while (clientSocket.isConnected()) {
                        in.read(buf);

                    }
                } catch (Throwable e) {
                    if (!shouldIgnoreThrowable(e)) {
                        sessionLogger.fatal("An error occurred whilst handling request:\n%s", e);
                    }
                } finally {
                    Thread.interrupted(); // Clear interrupt status.

                    try {
                        clientSocket.close();
                    } catch (IOException e) {
                        sessionLogger.severe("An error occurred whilst closing the socket:\n%s", e);
                    }

                    this.connectedClients.remove(clientSocket);
                    sessionLogger.debug("Closed.");
                }
            });
        } catch (Throwable t) {
            this.logger.severe("An error occurred whilst accepting a new connection:\n%s", t);
        }
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @SneakyThrows
    @Override
    public void start() {
        if (this.isAlive()) return;

        try {
            if (this.config.getSSL() == null) {
                this.serverSocket = new ServerSocket();
            } else {
                File keystoreFile = new File(this.config.getSSL().keystore);

                if (!keystoreFile.exists()) {
                    this.logger.severe("Unable to find SSL certificate file.");
                    return;
                }

                SSLContext sslContext = SSLContext.getInstance("TLS");

                KeyStore keyStore = KeyStore.getInstance("JKS");
                try (FileInputStream fis = new FileInputStream(keystoreFile)) {
                    keyStore.load(fis, this.config.getSSL().keystorePassword.toCharArray());
                }

                KeyManagerFactory keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                keyManager.init(keyStore, this.config.getSSL().keystorePassword.toCharArray());

                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(keyStore);

                sslContext.init(keyManager.getKeyManagers(), tmf.getTrustManagers(), null);

                SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
                SSLServerSocket socket = (SSLServerSocket) factory.createServerSocket();

                List<String> cipherSuitesToUse;

                if (this.config.getSSL().enabledCipherSuites == null) {
                    cipherSuitesToUse = Arrays.asList(factory.getSupportedCipherSuites());
                } else {
                    List<String> enabledCipherSuites = Arrays.asList(this.config.getSSL().enabledCipherSuites);

                    // Go through the list and make sure that the JVM supports the suite.
                    List<String> supported = new LinkedList<>();
                    for (String suite : factory.getSupportedCipherSuites()) {
                        if (enabledCipherSuites.contains(suite)) {
                            supported.add(suite);
                        } else {
                            this.logger.debug("Disabled Cipher Suite: %s.", suite);
                        }
                    }

                    for (String suite : enabledCipherSuites) {
                        if (!supported.contains(suite)) {
                            this.logger.warn("Unsupported Cipher Suite: %s.", suite);
                        }
                    }

                    cipherSuitesToUse = supported;
                }

                // If the certificate doesn't support EC algs, then we disable them.
                {
                    boolean ECsupported = false;

                    for (String alias : Collections.list(keyStore.aliases())) {
                        Certificate certificate = keyStore.getCertificate(alias);
                        if (certificate instanceof X509Certificate) {
                            X509Certificate x509Certificate = (X509Certificate) certificate;
                            String publicKeyAlgorithm = x509Certificate.getPublicKey().getAlgorithm();
                            if (publicKeyAlgorithm.equals("EC")) {
                                ECsupported = true;
                                break;
                            }
                        }
                    }

                    if (!ECsupported) {
                        Iterator<String> it = cipherSuitesToUse.iterator();
                        boolean warnedECunsupported = false;

                        while (it.hasNext()) {
                            String cipherSuite = it.next();
                            if (cipherSuite.contains("_ECDHE_") || cipherSuite.contains("_ECDH_")) {
                                it.remove();

                                if (!warnedECunsupported) {
                                    warnedECunsupported = true;
                                    this.logger.warn("Elliptic-Curve Cipher Suites are not supported as your certificate does not use the EC public key algorithm.");
                                }
                            }
                        }
                    }
                }

                this.logger.info("Using the following Cipher Suites: %s.", cipherSuitesToUse);
                socket.setEnabledCipherSuites(cipherSuitesToUse.toArray(new String[0]));

                socket.setEnabledProtocols(this.config.getSSL().convertTLS());
                socket.setUseClientMode(false);
                socket.setWantClientAuth(false);
                socket.setNeedClientAuth(false);

                this.config.getSSL().applyDHSize();

                this.serverSocket = socket;
            }

            this.serverSocket.setReuseAddress(true);
            this.serverSocket.bind(new InetSocketAddress("0.0.0.0", this.config.getPort()));
        } catch (Exception e) {
            this.serverSocket = null;
            throw new IOException("Unable to start server", e);
        }

        Thread acceptThread = new Thread(() -> {
            while (this.serverSocket.isBound()) {
                this.doRead();
            }
        });
        acceptThread.setName("KatanaTcpServer - " + this.config.getPort());
        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    @SneakyThrows
    @Override
    public void stop() {
        if (!this.isAlive()) return;

        try {
            this.serverSocket.close();

            new ArrayList<>(this.connectedClients)
                .forEach(IOUtil::safeClose);
            this.connectedClients.clear();
        } finally {
            this.serverSocket = null;
        }
    }

    public boolean isAlive() {
        return this.serverSocket != null;
    }

    private static String formatAddress(Socket clientSocket) {
        String address = //
            ((InetSocketAddress) clientSocket.getRemoteSocketAddress())
                .getAddress()
                .toString()
                .replace("/", "");

        if (address.indexOf(':') != -1) {
            // Better Format for ipv6 addresses :^)
            address = '[' + address + ']';
        }

        address += ':';
        address += clientSocket.getPort();

        return address;
    }

    private static boolean shouldIgnoreThrowable(Throwable t) {
        if (t instanceof InterruptedException) return true;
        if (t instanceof SSLHandshakeException) return true;

        String message = t.getMessage();
        if (message == null) return false;
        message = message.toLowerCase();

        if (message.contains("socket closed") ||
            message.contains("socket is closed") ||
            message.contains("read timed out") ||
            message.contains("connection or inbound has closed") ||
            message.contains("connection reset") ||
            message.contains("received fatal alert: internal_error") ||
            message.contains("socket write error")) return true;

        return false;
    }

}
