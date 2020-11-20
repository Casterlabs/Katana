package co.casterlabs.katana.config;

import co.casterlabs.katana.http.TLSVersion;
import lombok.ToString;

@ToString
public class SSLConfiguration {
    public boolean enabled = false;

    public TLSVersion[] tls = TLSVersion.values();
    public String[] enabled_cipher_suites = null; // Null = All Available
    public boolean allow_insecure = true;
    public boolean force = false;
    public int dh_size = 2048;

    public String keystore_password = "";
    public String key_password = "";
    public String keystore = "";

}
