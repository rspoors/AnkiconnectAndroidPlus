package com.kamwithk.ankiconnectandroid.routing;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.router.RouterNanoHTTPD;

import java.io.IOException;

public class Router extends RouterNanoHTTPD {
    private Context context;
    public static String contentType;

    public Router(Integer port, Context context) throws IOException {
        // Bind to loopback only. This keeps the API local to the device (e.g. Firefox/Yomitan)
        // and reduces the risk of exposing Anki actions to the LAN.
        super("127.0.0.1", port);
        this.context = context;

        contentType = new ContentType("; charset=UTF-8").getContentTypeHeader();
        addMappings();
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    public void setContext(Context context) {
        this.context = context;
    }

    @Override
    public void addMappings() {
        addRoute("/", RouteHandler.class, this.context);
        addRoute("/localaudio/(.)+", LocalAudioRouteHandler.class, this.context);
        // for some reason, none of these work, so the above is used instead
        // addRoute("/localaudio/:source/(.)+", LocalAudioRouteHandler.class, this.context);
        // addRoute("/localaudio/:source/:file", LocalAudioRouteHandler.class, this.context);
    }
}
