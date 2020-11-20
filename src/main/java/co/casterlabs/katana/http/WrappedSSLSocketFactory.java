package co.casterlabs.katana.http;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

import co.casterlabs.katana.config.SSLConfiguration;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.fastloggingframework.logging.LogLevel;

public class WrappedSSLSocketFactory extends SSLServerSocketFactory {
    private SSLServerSocketFactory wrappedFactory;
    private String[] cipherSuites;

    public WrappedSSLSocketFactory(SSLServerSocketFactory factory, SSLConfiguration ssl) {
        this.wrappedFactory = factory;

        if ((ssl.enabled_cipher_suites == null) || (ssl.enabled_cipher_suites.length == 0)) {
            this.cipherSuites = this.wrappedFactory.getSupportedCipherSuites();
        } else {
            List<String> enabled = Arrays.asList(ssl.enabled_cipher_suites);
            List<String> supported = new ArrayList<>();

            for (String def : this.wrappedFactory.getSupportedCipherSuites()) {
                if (enabled.contains(def)) {
                    supported.add(def);
                } else {
                    FastLogger.logStatic(LogLevel.DEBUG, "Disabled Cipher Suite: %s.", def);
                }
            }

            FastLogger.logStatic(LogLevel.DEBUG, "Using the following Cipher Suites: %s.", supported);

            this.cipherSuites = supported.toArray(new String[0]);
        }
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return this.cipherSuites;
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return this.cipherSuites;
    }

    @Override
    public ServerSocket createServerSocket() throws IOException {
        SSLServerSocket socket = (SSLServerSocket) this.wrappedFactory.createServerSocket();

        socket.setEnabledCipherSuites(this.cipherSuites);

        return socket;
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) this.wrappedFactory.createServerSocket(port);

        socket.setEnabledCipherSuites(this.cipherSuites);

        return socket;
    }

    @Override
    public ServerSocket createServerSocket(int port, int backlog) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) this.wrappedFactory.createServerSocket(port, backlog);

        socket.setEnabledCipherSuites(this.cipherSuites);

        return socket;
    }

    @Override
    public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
        SSLServerSocket socket = (SSLServerSocket) this.wrappedFactory.createServerSocket(port, backlog, ifAddress);

        socket.setEnabledCipherSuites(this.cipherSuites);

        return socket;
    }

}
