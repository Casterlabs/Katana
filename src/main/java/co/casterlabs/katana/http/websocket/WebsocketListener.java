package co.casterlabs.katana.http.websocket;

public interface WebsocketListener {

    default void onFrame(Websocket websocket, WebsocketFrame frame) {
        if (frame.getFrameType() == WebsocketFrameType.TEXT) {
            this.onText(websocket, frame.getAsText());
        } else {
            this.onBinary(websocket, frame.getBytes());
        }
    }

    default void onOpen(Websocket websocket) {}

    default void onText(Websocket websocket, String message) {}

    default void onBinary(Websocket websocket, byte[] bytes) {}

    default void onClose(Websocket websocket) {}

}
