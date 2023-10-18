package co.casterlabs.katana.router;

import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rhs.session.TLSVersion;

public interface KatanaRouterConfiguration {

    public String getName();

    public RouterType getType();

    public static enum RouterType {
        HTTP,
    }

    @JsonClass(exposeAll = true)
    public static class SSLConfiguration {
        public boolean enabled = false;

        public TLSVersion[] tls = TLSVersion.values();
        @JsonField("enabled_cipher_suites")
        public String[] enabledCipherSuites = {
                "TLS_ECDHE_PSK_WITH_AES_128_CCM_SHA256",
                "TLS_ECDHE_PSK_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_PSK_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_PSK_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
                "TLS_DHE_PSK_WITH_AES_256_CCM",
                "TLS_DHE_PSK_WITH_AES_128_CCM",
                "TLS_DHE_RSA_WITH_AES_256_CCM",
                "TLS_DHE_RSA_WITH_AES_128_CCM",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_AES_128_CCM_SHA256",
                "TLS_CHACHA20_POLY1305_SHA256",
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256",
                "TLS_DHE_PSK_WITH_AES_256_GCM_SHA384",
                "TLS_DHE_PSK_WITH_AES_128_GCM_SHA256",
                "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
        }; // Null = All Available

        @JsonField("dh_size")
        public int dhSize = 2048;

        @JsonField("keystore_password")
        public String keystorePassword = "";
        public String keystore = "";

    }

}
