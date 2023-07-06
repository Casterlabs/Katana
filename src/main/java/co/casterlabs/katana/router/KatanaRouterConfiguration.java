package co.casterlabs.katana.router;

import co.casterlabs.rakurai.io.http.TLSVersion;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;

public interface KatanaRouterConfiguration {

    public String getName();

    public RouterType getType();

    public static enum RouterType {
        HTTP,
        TCP
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

        public String[] convertTLS() {
            TLSVersion[] tls = this.tls;
            String[] versions = new String[tls.length];

            for (int i = 0; i != tls.length; i++) {
                versions[i] = tls[i].getRuntimeName();
            }

            return versions;
        }

        public void applyDHSize() {
            // https://www.java.com/en/configure_crypto.html
            // https://docs.oracle.com/javase/8/docs/technotes/guides/security/jsse/JSSERefGuide.html#customizing_dh_keys
            System.setProperty("jdk.tls.ephemeralDHKeySize", String.valueOf(this.dhSize));
            String disabledAlgorithmsProperty = System.getProperty("jdk.tls.disabledAlgorithms", "DH keySize");
            String[] disabledAlgorithms = disabledAlgorithmsProperty.split(",");
            boolean replacedParameter = false;

            for (int i = 0; i != disabledAlgorithms.length; i++) {
                if (disabledAlgorithms[i].startsWith("DH keySize")) {
                    replacedParameter = true;

                    disabledAlgorithms[i] = "DH keySize < " + this.dhSize;

                    break;
                }
            }

            if (replacedParameter) {
                System.setProperty("jdk.tls.disabledAlgorithms", String.join(", ", disabledAlgorithms));
            } else {
                System.setProperty("jdk.tls.disabledAlgorithms", disabledAlgorithmsProperty + ", DH keySize < " + this.dhSize);
            }
        }

    }

}
