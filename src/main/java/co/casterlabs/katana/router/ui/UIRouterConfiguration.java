package co.casterlabs.katana.router.ui;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import co.casterlabs.katana.router.KatanaRouterConfiguration;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.annotating.JsonSerializationMethod;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonString;

@JsonClass(exposeAll = true)
public class UIRouterConfiguration implements KatanaRouterConfiguration {
    public int port = 81;

    @JsonField("is_behind_proxy")
    public boolean isBehindProxy = false;

    public Map<String, String> logins = Map.of("admin", "password");

    public UIOAuthConfiguration oauth = new UIOAuthConfiguration();

    @Override
    public String getName() {
        return "UI";
    }

    @Override
    public RouterType getType() {
        return RouterType.UI;
    }

    @JsonSerializationMethod("type")
    private JsonElement $serialize_type() {
        return new JsonString(this.getType().name());
    }

    @JsonClass(exposeAll = true)
    public static class UIOAuthConfiguration {
        public boolean enabled = false;

        @JsonField("client_id")
        public String clientId;
        @JsonField("client_secret")
        public String clientSecret;

        @JsonField("authorization_url")
        public String authorizationUrl;
        @JsonField("token_url")
        public String tokenUrl;
        @JsonField("user_info_url")
        public String userInfoUrl;

        @JsonField("redirect_url")
        public String redirectUrl;

        public String scope;

        public String identifier = "preferred_username";

        @JsonField("allowed_user_ids")
        public Set<String> allowedUserIds = new HashSet<>();

    }

}
