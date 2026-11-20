package com.databridge.app.http;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.nio.file.Files;
import java.nio.file.Path;

/** Serving the built UI next to the API, and the security headers every response carries. */
public final class WebHandlers {

    /**
     * Content Security Policy for the UI: only this origin; inline styles are needed by the
     * component library's CSS-in-JS; images may be data: or blob: URLs (downloads, icons).
     */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
            + "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    private WebHandlers() { }

    /** /api and /health go to the API; everything else is a file of the built UI, or its index.html. */
    public static HttpHandler withUi(HttpHandler api, Path uiDir) {
        Path index = uiDir.resolve("index.html");
        if (!Files.isRegularFile(index)) {
            throw new IllegalArgumentException("server.uiDir has no index.html: " + uiDir.toAbsolutePath()
                    + " (build the UI with: node node_modules/vite/bin/vite.js build, then point at its dist folder)");
        }
        PathResourceManager files = new PathResourceManager(uiDir, 1024 * 1024, true, false, false);
        ResourceHandler indexHandler = new ResourceHandler(files).setWelcomeFiles("index.html").setDirectoryListingEnabled(false);
        // An unknown path inside the app (e.g. /checks/123 after a reload) gets the app itself.
        HttpHandler spa = ex -> {
            ex.setRelativePath("/index.html");
            noCache(ex);
            indexHandler.handleRequest(ex);
        };
        ResourceHandler static_ = new ResourceHandler(files, spa).setWelcomeFiles("index.html").setDirectoryListingEnabled(false);
        return ex -> {
            String p = ex.getRelativePath();
            if (p.startsWith("/api/") || p.equals("/api") || p.equals("/health")) {
                api.handleRequest(ex);
                return;
            }
            if (p.startsWith("/assets/")) {
                // Vite puts a content hash in every asset name: safe to cache for good.
                ex.getResponseHeaders().put(Headers.CACHE_CONTROL, "public, max-age=31536000, immutable");
            } else {
                noCache(ex);
            }
            static_.handleRequest(ex);
        };
    }

    /** Headers that make browsers refuse framing, sniffing, referrer leaks and foreign content. */
    public static HttpHandler secured(HttpHandler next, boolean https) {
        return ex -> {
            var h = ex.getResponseHeaders();
            h.put(new HttpString("Content-Security-Policy"), CSP);
            h.put(new HttpString("X-Frame-Options"), "DENY");
            h.put(Headers.X_CONTENT_TYPE_OPTIONS, "nosniff");
            h.put(new HttpString("Referrer-Policy"), "no-referrer");
            h.put(new HttpString("Permissions-Policy"), "camera=(), microphone=(), geolocation=(), payment=()");
            h.put(new HttpString("Cross-Origin-Opener-Policy"), "same-origin");
            if (https) h.put(Headers.STRICT_TRANSPORT_SECURITY, "max-age=31536000");
            next.handleRequest(ex);
        };
    }

    private static void noCache(HttpServerExchange ex) {
        ex.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-cache");
    }
}
