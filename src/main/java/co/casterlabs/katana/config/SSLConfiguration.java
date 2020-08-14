package co.casterlabs.katana.config;

import lombok.ToString;

@ToString
public class SSLConfiguration {
    public boolean enabled = false;

    public boolean allow_insecure = true;
    public boolean force = false;

    public String certificate;
    public String private_key;
    public String chain;

}
