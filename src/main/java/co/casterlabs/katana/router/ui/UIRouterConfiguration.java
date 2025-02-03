package co.casterlabs.katana.router.ui;

import co.casterlabs.katana.router.KatanaRouterConfiguration;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.annotating.JsonSerializationMethod;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.element.JsonString;
import lombok.Getter;

@Getter
@JsonClass(exposeAll = true)
public class UIRouterConfiguration implements KatanaRouterConfiguration {
    private int port = 81;

    @JsonField("is_behind_proxy")
    private boolean isBehindProxy = false;

    private JsonObject logins = JsonObject.singleton("admin", "password");

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

}
