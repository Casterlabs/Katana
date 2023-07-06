package co.casterlabs.katana.router.tcp;

import co.casterlabs.katana.router.KatanaRouterConfiguration;
import co.casterlabs.rakurai.json.annotating.JsonClass;
import co.casterlabs.rakurai.json.annotating.JsonField;
import co.casterlabs.rakurai.json.annotating.JsonSerializationMethod;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonString;
import lombok.Getter;

@Getter
@JsonClass(exposeAll = true)
public class TcpRouterConfiguration implements KatanaRouterConfiguration {
    private String name = "www";

    private int port = 80;

    @JsonField("ssl")
    private SSLConfiguration SSL = new SSLConfiguration();

    private String forwardTo = "example.com";
    private int forwardToPort = 60;

    @Override
    public RouterType getType() {
        return RouterType.HTTP;
    }

    @JsonSerializationMethod("type")
    private JsonElement $serialize_type() {
        return new JsonString(this.getType().name());
    }

}
