package co.casterlabs.katana.http.websocket;

/**
 * For tagging classes with websocket listener methods. Example:
 * 
 * <pre>
 * &#64;WebsocketEndpoint(uri = "/echo")
 * public WebsocketListener onEchoRequest(WebsocketSession session) {
 *     // Do what you want, keeping in mind that returning null
 *     // will cause the connection will be dropped without a response.
 *     return new WebsocketListener() {
 * 
 *         public void onText(Websocket websocket, String message) {
 *             websocket.send(message);
 *         }
 * 
 *         public void onBinary(Websocket websocket, byte[] bytes) {
 *             websocket.send(bytes);
 *         }
 *
 *     };
 * }
 * </pre>
 */
public interface WebsocketProvider {

}
