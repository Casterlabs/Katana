package co.casterlabs.katana.config;

import lombok.ToString;

@ToString
public class SSLConfiguration {
    public boolean enabled = false;

    public boolean allow_insecure = true;
    public boolean force = false;

    public String keystore;
    public String keystore_password;
    public String key_password;
}
