package co.casterlabs.katana.router.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import co.casterlabs.commons.io.streams.StreamUtil;
import co.casterlabs.katana.CertificateAutoIssuer.IssuanceException;
import co.casterlabs.katana.Katana;
import co.casterlabs.katana.Util;
import co.casterlabs.katana.router.KatanaRouter;
import co.casterlabs.katana.router.http.HttpRouter;
import co.casterlabs.katana.router.http.RakuraiTaskExecutor;
import co.casterlabs.katana.router.http.servlets.HttpServlet;
import co.casterlabs.katana.router.ui.AuthPreprocessor.AuthorizedUser;
import co.casterlabs.rakurai.json.Rson;
import co.casterlabs.rakurai.json.element.JsonArray;
import co.casterlabs.rakurai.json.element.JsonElement;
import co.casterlabs.rakurai.json.element.JsonObject;
import co.casterlabs.rakurai.json.element.JsonString;
import co.casterlabs.rakurai.json.serialization.JsonParseException;
import co.casterlabs.rhs.HttpMethod;
import co.casterlabs.rhs.HttpServer;
import co.casterlabs.rhs.HttpServerBuilder;
import co.casterlabs.rhs.HttpStatus.StandardHttpStatus;
import co.casterlabs.rhs.TLSVersion;
import co.casterlabs.rhs.protocol.api.ApiFramework;
import co.casterlabs.rhs.protocol.api.endpoints.EndpointData;
import co.casterlabs.rhs.protocol.api.endpoints.EndpointProvider;
import co.casterlabs.rhs.protocol.api.endpoints.HttpEndpoint;
import co.casterlabs.rhs.protocol.http.HttpProtocol;
import co.casterlabs.rhs.protocol.http.HttpResponse;
import co.casterlabs.rhs.protocol.http.HttpSession;
import lombok.Getter;
import lombok.SneakyThrows;
import xyz.e3ndr.fastloggingframework.logging.FastLogger;
import xyz.e3ndr.stf.SimpleTemplate;
import xyz.e3ndr.stf.parsing.STFParser;

@Getter
public class UIRouter implements KatanaRouter<UIRouterConfiguration>, EndpointProvider {
    private static final JsonArray ALL_SERVLET_TYPES = (JsonArray) Rson.DEFAULT.toJson(HttpServlet.types());
    private static final JsonArray ALL_TLS_VERSIONS = (JsonArray) Rson.DEFAULT.toJson(TLSVersion.values());
    private static final JsonArray ALL_CIPHER_SUITES;
    private static final JsonObject DEFAULT_SERVLET_CONFIGS = new JsonObject();
    static {
        JsonArray array;
        try {
            array = (JsonArray) Rson.DEFAULT.toJson(((SSLSocket) SSLSocketFactory.getDefault().createSocket()).getSupportedCipherSuites());
        } catch (IOException e) {
            e.printStackTrace();
            array = JsonArray.EMPTY_ARRAY; // ?
        }
        ALL_CIPHER_SUITES = array;

        for (String type : HttpServlet.types()) {
            HttpServlet reference = HttpServlet.create(type);
            try {
                reference.init(JsonObject.EMPTY_OBJECT);
            } catch (JsonParseException e) {
                e.printStackTrace();
            }
            DEFAULT_SERVLET_CONFIGS.put(type, Rson.DEFAULT.toJson(reference.getConfig()));
        }
    }

    // @formatter:off
    private static final SimpleTemplate TEMPLATE_LAYOUT               = STFParser.parse(load("/layout.stf"));
    private static final SimpleTemplate TEMPLATE_CONFIG_UI            = STFParser.parse(load("/router/ui/main.stf"));
	private static final SimpleTemplate TEMPLATE_CONFIG_UI_LOGINS     = STFParser.parse(load("/router/ui/logins.stf"));
    private static final SimpleTemplate TEMPLATE_CONFIG_UI_OAUTH      = STFParser.parse(load("/router/ui/oauth.stf"));
    private static final SimpleTemplate TEMPLATE_CONFIG_HTTP          = STFParser.parse(load("/router/http/main.stf"));
    private static final SimpleTemplate TEMPLATE_CONFIG_HTTP_SSL      = STFParser.parse(load("/router/http/ssl.stf"));
    private static final SimpleTemplate TEMPLATE_CONFIG_HTTP_SERVLETS = STFParser.parse(load("/router/http/servlets.stf"));

    private static final String PAGE_LOGIN   = load("/login.html");

    private static final String PAGE_MAIN    = load("/main.html");
    private static final String RESOURCE_CSS = load("/style.css");
    private static final String RESOURCE_JS  = load("/main.js");
    // @formatter:on

    private FastLogger logger;
    private Katana katana;

    private HttpServer server;
    private AuthPreprocessor auth = new AuthPreprocessor(this);
    private UIRouterConfiguration config;

    @SneakyThrows
    private static String load(String filename) {
        return StreamUtil.toString(UIRouter.class.getResource("/ui" + filename).openStream(), StandardCharsets.UTF_8);
    }

    @SneakyThrows
    public UIRouter(UIRouterConfiguration config, Katana katana) throws IOException {
        this.logger = new FastLogger(String.format("UI (%s)", config.getName()));
        this.katana = katana;

        ApiFramework framework = new ApiFramework();
        framework.instantiatePreprocessor(AuthPreprocessor.class, this.auth);
        framework.register(this);

        this.server = new HttpServerBuilder()
            .withPort(config.port)
            .withServerHeader(Katana.SERVER_DECLARATION)
            .withTaskExecutor(RakuraiTaskExecutor.INSTANCE)
            .with(new HttpProtocol(), framework.httpHandler)
            .build();

        this.loadConfig(config);
    }

    @Override
    public UIRouterConfiguration getConfig() {
        return config;
    }

    @Override
    public void loadConfig(UIRouterConfiguration config) {
        this.config = config;
    }

    @SneakyThrows
    @Override
    public void start() {
        if ((this.server != null) && !this.server.isAlive()) {
            this.server.start();
            this.logger.info("Started server on port %d.", this.server.port());
        }
    }

    @SneakyThrows
    @Override
    public void stop(boolean disconnectClients) {
        if (this.server != null) {
            this.server.stop(disconnectClients);
            this.logger.info("Stopped server on port %d.", this.server.port());
        }
    }

    @Override
    public boolean isRunning() {
        return (this.server != null) ? this.server.isAlive() : false;
    }

    /* ---------------- */
    /* Pages            */
    /* ---------------- */

    private HttpResponse renderPage(EndpointData<AuthorizedUser> data, String title, String content) {
        JsonArray navConfigs = new JsonArray();

        for (JsonElement routerElement : loadConfig()) {
            JsonObject router = routerElement.getAsObject();
            String routerType = router.getString("type").toUpperCase();
            String routerName = router.containsKey("name") ? router.getString("name") : routerType;

            JsonObject config = new JsonObject()
                .put("name", routerName)
                .put("type", routerType);

            config.put("isHTTP", "HTTP".equals(routerType));
            config.put("isUI", "UI".equals(routerType));

            navConfigs.add(config);
        }

        JsonObject vars = new JsonObject()
            .put("title", title)
            .put("content", content)
            .put("auth", Rson.DEFAULT.toJson(data.attachment()))
            .put("nav", JsonObject.singleton("routers", navConfigs));

        return HttpResponse.newFixedLengthResponse(
            StandardHttpStatus.OK,
            TEMPLATE_LAYOUT.render(vars)
        ).mime("text/html; charset=UTF-8");
    }

    @HttpEndpoint(path = ".*", priority = -100, allowedMethods = {
            HttpMethod.GET
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onGetIndex(HttpSession session, EndpointData<AuthorizedUser> data) {
        return renderPage(data, "Home", PAGE_MAIN);
    }

    @HttpEndpoint(path = "/logout")
    public HttpResponse onLogout(HttpSession session, EndpointData<AuthorizedUser> data) {
        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
            .header("Location", "/login")
            .header("Set-Cookie", String.format("%s=deleted; Path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly", AuthPreprocessor.COOKIE_NAME));
    }

    @HttpEndpoint(path = "/login")
    public HttpResponse onLoginPage(HttpSession session, EndpointData<AuthorizedUser> data) {
        if (!this.config.oauth.enabled) {
            // We don't have the option for OAuth, so just render the basic login page.
            return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
                .header("Location", "/login/basic");
        }
        return HttpResponse.newFixedLengthResponse(
            StandardHttpStatus.OK,
            PAGE_LOGIN
        ).mime("text/html; charset=UTF-8");
    }

    @HttpEndpoint(path = "/login/basic")
    public HttpResponse onBasicLoginPage(HttpSession session, EndpointData<AuthorizedUser> data) {
        return this.auth.handleBasic(session);
    }

    @SneakyThrows
    @HttpEndpoint(path = "/login/oauth")
    public HttpResponse onOAuthLoginPage(HttpSession session, EndpointData<AuthorizedUser> data) {
        if (!this.config.oauth.enabled) {
            // We don't have the option for OAuth, so just render the basic login page.
            return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
                .header("Location", "/login/basic");
        }
        return this.auth.handleOAuth(session);
    }

    @HttpEndpoint(path = "/action/reload", priority = 100, allowedMethods = {
            HttpMethod.POST
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onReload(HttpSession session, EndpointData<AuthorizedUser> data) throws IOException {
        this.katana.getLauncher().loadConfig(this.katana);
        this.katana.start();

        String backTo = session.uri().query.getSingleOrDefault("backTo", "/");

        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
            .header("Location", backTo);
    }

    @HttpEndpoint(path = "/router/:name", allowedMethods = {
            HttpMethod.GET
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onGetRouterMain(HttpSession session, EndpointData<AuthorizedUser> data) {
        return this.onGetRouterSection(session, data);
    }

    @SneakyThrows
    @HttpEndpoint(path = "/router/:name/:section", allowedMethods = {
            HttpMethod.GET
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onGetRouterSection(HttpSession session, EndpointData<AuthorizedUser> data) {
        String name = data.uriParameters().get("name");
        String section = data.uriParameters().getOrDefault("section", "main");
        JsonObject router = findInConfig(name);

        String routerType = router.getString("type").toUpperCase();
        String routerName = router.containsKey("name") ? router.getString("name") : routerType;

        SimpleTemplate template = null;
        switch (routerType) {
            case "HTTP":
                switch (section) {
                    case "ssl":
                        template = TEMPLATE_CONFIG_HTTP_SSL;
                        break;
                    case "servlets":
                        template = TEMPLATE_CONFIG_HTTP_SERVLETS;
                        break;
                    default:
                        template = TEMPLATE_CONFIG_HTTP;
                        break;
                }
                break;
            case "UI": {
                switch (section) {
                    case "logins":
                        template = TEMPLATE_CONFIG_UI_LOGINS;
                        break;
                    case "oauth":
                        template = TEMPLATE_CONFIG_UI_OAUTH;
                        break;
                    default:
                        template = TEMPLATE_CONFIG_UI;
                        break;
                }
                break;
            }
        }

        JsonObject vars = new JsonObject()
            .put("ALL_CIPHER_SUITES", ALL_CIPHER_SUITES.toString())
            .put("ALL_TLS_VERSIONS", ALL_TLS_VERSIONS.toString())
            .put("ALL_SERVLET_TYPES", ALL_SERVLET_TYPES.toString())
            .put("DEFAULT_SERVLET_CONFIGS", DEFAULT_SERVLET_CONFIGS.toString())
            .put("section", section)
            .put("name", routerName)
            .put("type", routerType)
            .put("config", router.toString());

        return renderPage(
            data,
            "Config - " + name,
            template.render(vars)
        );
    }

    @HttpEndpoint(path = "/router/:name/_delete", priority = 100, allowedMethods = {
            HttpMethod.POST
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onDeleteRouter(HttpSession session, EndpointData<AuthorizedUser> data) throws IOException {
        String routerName = data.uriParameters().get("name");

        JsonArray newConfig = new JsonArray();
        for (JsonElement existingRouterElement : loadConfig()) {
            JsonObject existingRouter = existingRouterElement.getAsObject();
            String existingRouterName = existingRouter.containsKey("name") ? existingRouter.getString("name") : existingRouter.getString("type").toUpperCase();
            if (!existingRouterName.equals(routerName)) {
                newConfig.add(existingRouterElement);
            }
        }

        Files.write(Katana.CONFIG_FILE.toPath(), newConfig.toString(true).getBytes(StandardCharsets.UTF_8));

        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
            .header("Location", "/");
    }

    @HttpEndpoint(path = "/router/:name/_reissue", priority = 100, allowedMethods = {
            HttpMethod.POST
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onReissueCertsForRouter(HttpSession session, EndpointData<AuthorizedUser> data) throws IOException, IssuanceException {
        String routerName = data.uriParameters().get("name");

        HttpRouter router = (HttpRouter) Katana.getInstance().getRouters().get(routerName);
        router.autoIssueCertificates();

        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
            .header("Location", "/");
    }

    @HttpEndpoint(path = "/router/:name/:section", allowedMethods = {
            HttpMethod.POST
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onUpdateRouterSection(HttpSession session, EndpointData<AuthorizedUser> data) throws IOException {
        String routerName = data.uriParameters().get("name");
        String section = data.uriParameters().get("section");

        JsonObject form = new JsonObject();
        for (Entry<String, List<String>> entry : session.body().urlEncoded().entrySet()) {
            // Convert to json - dot path notation.
            String[] keyPath = entry.getKey().split("\\.");

            JsonObject current = form;
            for (int i = 0; i < keyPath.length - 1; i++) {
                String k = keyPath[i];
                if (current.containsKey(k)) {
                    current = current.getObject(k);
                } else {
                    JsonObject n = new JsonObject();
                    current.put(k, n);
                    current = n;
                }
            }

            JsonElement value = entry.getValue().size() > 1 ? // is multiple values?
                Rson.DEFAULT.toJson(entry.getValue()) : // array
                new JsonString(entry.getValue().get(0));

            current.put(keyPath[keyPath.length - 1], value);
        }

//        System.out.println(session.body().string());
//        System.out.println(form.toString(true));

        String routerType = form.getString("type").toUpperCase();
        if (form.containsKey("name")) {
            routerName = form.getString("name");
        }

        JsonObject routerConfig = null;
        JsonArray mainConfig = loadConfig();
        for (JsonElement existingRouterElement : mainConfig) {
            JsonObject existingRouter = existingRouterElement.getAsObject();
            String existingRouterName = existingRouter.containsKey("name") ? existingRouter.getString("name") : existingRouter.getString("type").toUpperCase();
            if (existingRouterName.equals(routerName)) {
                routerConfig = existingRouter;
            }
        }

        if (routerConfig == null) {
            routerConfig = (JsonObject) Rson.DEFAULT.toJson(
                // Populate defaults.
                Katana.deserialize(
                    new JsonObject()
                        .put("type", routerType)
                        .put("name", routerName)
                )
            );
            mainConfig.add(routerConfig);
        }

        switch (routerType) {
            case "HTTP": {
                if (form.containsKey("port")) {
                    routerConfig.put("port", formIntegerValue(form.get("port")));
                }

                if (form.containsKey("ssl")) {
                    JsonObject formSSL = form.getObject("ssl");
                    JsonObject routerSSL = routerConfig.getObject("ssl");

                    routerSSL.put("port", formIntegerValue(formSSL.get("port")));
                    routerSSL.put("dh_size", formIntegerValue(formSSL.get("dh_size")));

                    routerSSL.put("enabled", formCheckboxValue(formSSL.get("enabled")));
                    routerSSL.put("allow_insecure", formCheckboxValue(formSSL.get("allow_insecure")));
                    routerSSL.put("force", formCheckboxValue(formSSL.get("force")));

                    routerSSL.put("certificate_file", formSSL.getString("certificate_file"));
                    routerSSL.put("private_key_file", formSSL.getString("private_key_file"));
                    routerSSL.put("trust_chain_file", formSSL.getString("trust_chain_file"));

                    routerSSL.put("enabled_cipher_suites", Rson.DEFAULT.toJson(formSSL.getObject("enabled_cipher_suites").keySet()));
                    routerSSL.put("tls", Rson.DEFAULT.toJson(formSSL.getObject("tls").keySet()));

                    JsonObject formSSLCAI = formSSL.getObject("certificate_auto_issuer");
                    JsonObject routerSSLCAI = routerSSL.getObject("certificate_auto_issuer");

                    routerSSLCAI.put("enabled", formCheckboxValue(formSSLCAI.get("enabled")));
                    routerSSLCAI.put("account_email", formSSLCAI.getString("account_email"));
                    routerSSLCAI.put("method", formSSLCAI.getString("method"));
                }

                if (form.containsKey("servlets")) {
                    JsonObject formServlets = form.getObject("servlets");
                    JsonArray routerServlets = new JsonArray();
                    routerConfig.put("servlets", routerServlets);

                    for (JsonElement servlet : formServlets.values()) {
                        JsonObject formServletConfig = servlet.getAsObject();
                        String servletType = formServletConfig.getString("type").toUpperCase();
                        JsonObject servletConfig = Rson.DEFAULT.fromJson(DEFAULT_SERVLET_CONFIGS.getObject(servletType).toString(), JsonObject.class); // Clone.

                        for (Map.Entry<String, JsonElement> entry : new ArrayList<>(servletConfig.entrySet())) {
                            String key = entry.getKey();
                            JsonElement value = entry.getValue();

                            if (!formServletConfig.containsKey(key)) {
                                continue;
                            }

                            if (value.isJsonBoolean()) {
                                servletConfig.put(key, formCheckboxValue(formServletConfig.get(key)));
                            }
                            if (value.isJsonString() || value.isJsonNull()) {
                                servletConfig.put(key, formServletConfig.getString(key));
                            }
                            if (value.isJsonArray()) {
                                servletConfig.put(key, formArrayValue(formServletConfig.get(key)));
                            }
                            if (value.isJsonNumber()) {
                                servletConfig.put(key, formIntegerValue(formServletConfig.get(key)));
                            }
                        }

                        servletConfig.put("type", servletType);
                        servletConfig.put("priority", formIntegerValue(formServletConfig.get("priority")));
                        servletConfig.put("hostnames", formArrayValue(formServletConfig.get("hostnames")));
                        servletConfig.put("cors_allowed_hosts", formArrayValue(formServletConfig.get("cors_allowed_hosts")));

                        routerServlets.add(servletConfig);
                    }
                }
                break;
            }

            case "UI": {
                if (form.containsKey("port")) {
                    routerConfig.put("port", formIntegerValue(form.get("port")));
                    routerConfig.put("is_behind_proxy", formCheckboxValue(form.get("is_behind_proxy")));
                }

                if (form.containsKey("logins")) {
                    JsonObject logins = new JsonObject();
                    for (JsonElement value : form.getObject("logins").values()) {
                        JsonObject login = value.getAsObject(); // "username", "password"
                        logins.put(login.getString("username"), login.getString("password"));
                    }
                    routerConfig.put("logins", logins);
                }

                if (form.containsKey("oauth")) {
                    JsonObject formOAuth = form.getObject("oauth");
                    JsonObject routerOAuth = routerConfig.getObject("oauth");

                    routerOAuth.put("enabled", formCheckboxValue(formOAuth.get("enabled")));
                    routerOAuth.put("client_id", formOAuth.getString("client_id"));
                    routerOAuth.put("client_secret", formOAuth.getString("client_secret"));
                    routerOAuth.put("authorization_url", formOAuth.getString("authorization_url"));
                    routerOAuth.put("token_url", formOAuth.getString("token_url"));
                    routerOAuth.put("user_info_url", formOAuth.getString("user_info_url"));
                    routerOAuth.put("redirect_url", formOAuth.getString("redirect_url"));
                    routerOAuth.put("scope", formOAuth.getString("scope"));
                    routerOAuth.put("identifier", formOAuth.getString("identifier"));
                    routerOAuth.put("allowed_user_ids", formArrayValue(formOAuth.get("allowed_user_ids")));
                }
                break;
            }
        }

        Files.write(Katana.CONFIG_FILE.toPath(), mainConfig.toString(true).getBytes(StandardCharsets.UTF_8));

        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.SEE_OTHER)
            .header("Location", "/router/" + routerName + "/" + section);
    }

    /* ---------------- */
    /* Resources        */
    /* ---------------- */

    @HttpEndpoint(path = "/main.js", allowedMethods = {
            HttpMethod.GET
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onGetResourceMain(HttpSession session, EndpointData<AuthorizedUser> data) {
        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.OK, RESOURCE_JS)
            .mime("text/javascript; charset=UTF-8");
    }

    @HttpEndpoint(path = "/style.css", allowedMethods = {
            HttpMethod.GET
    }, preprocessor = AuthPreprocessor.class)
    public HttpResponse onGetResourceCss(HttpSession session, EndpointData<AuthorizedUser> data) {
        return HttpResponse.newFixedLengthResponse(StandardHttpStatus.OK, RESOURCE_CSS)
            .mime("text/css; charset=UTF-8");
    }

    /* ---------------- */
    /* Helpers          */
    /* ---------------- */

    @SneakyThrows
    private static JsonArray loadConfig() {
        return Util.readFileAsJson(Katana.CONFIG_FILE, JsonArray.class);
    }

    private static JsonObject findInConfig(String name) {
        for (JsonElement routerElement : loadConfig()) {
            JsonObject router = routerElement.getAsObject();

            String routerName = router.containsKey("name") ? router.getString("name") : router.getString("type").toUpperCase();
            if (routerName.equals(name)) {
                return router;
            }
        }

        return null;
    }

    private static boolean formCheckboxValue(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return false;
        }

        if (e.isJsonBoolean()) {
            return e.getAsBoolean();
        }

        return e.isJsonString() && e.getAsString().equals("on");
    }

    private static int formIntegerValue(JsonElement e) {
        if (e.isJsonNumber()) {
            return e.getAsNumber().intValue();
        }

        return Integer.parseInt(e.getAsString());
    }

    private static JsonArray formArrayValue(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return JsonArray.EMPTY_ARRAY;
        }

        if (e.isJsonArray()) {
            return e.getAsArray();
        }

        return JsonArray.of(e);
    }

}
