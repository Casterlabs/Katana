package co.casterlabs.katana.http;

public enum TLSVersion {
    TLSv1,
    TLSv1_1,
    TLSv1_2,
    TLSv1_3;

    public String getRuntimeName() {
        return this.name().replace('_', '.');
    }

}
