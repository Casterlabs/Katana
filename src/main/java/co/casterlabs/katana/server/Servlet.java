package co.casterlabs.katana.server;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

import co.casterlabs.katana.http.HttpSession;
import lombok.Getter;
import lombok.NonNull;

@Getter
public abstract class Servlet {
    private List<String> allowedHosts = new ArrayList<>();
    private List<String> hosts = new ArrayList<>();
    private ServletType type;
    private String id;

    public Servlet(@NonNull ServletType type, @NonNull String id) {
        this.type = type;
        this.id = id;
    }

    public abstract void init(JsonObject config);

    public abstract boolean serve(HttpSession session);

}
