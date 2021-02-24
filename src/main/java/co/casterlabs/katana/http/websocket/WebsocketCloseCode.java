package co.casterlabs.katana.http.websocket;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WebsocketCloseCode {
    NORMAL(1000),
    GOING_AWAY(1001),
    TOO_LARGE(1009),;

    private int code;

}
