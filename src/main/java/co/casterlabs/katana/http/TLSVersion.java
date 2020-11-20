package co.casterlabs.katana.http;

import java.lang.reflect.InvocationTargetException;

import xyz.e3ndr.reflectionlib.ReflectionLib;

public enum TLSVersion {
    TLSv1,
    TLSv1_1,
    TLSv1_2,
    TLSv1_3;

    public String getRuntimeName() {
        return this.name().replace('_', '.');
    }

    @SuppressWarnings("restriction")
    public boolean existsInRuntime() {
        try {
            ReflectionLib.invokeStaticMethod(sun.security.ssl.ProtocolVersion.class, "valueOf", this.getRuntimeName());

            return true;
        } catch (NoSuchMethodException | SecurityException | IllegalAccessException | InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException) {
                return false;
            } else {
                e.printStackTrace();
                return true;
            }
        }
    }

}
