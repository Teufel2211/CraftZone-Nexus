package core.common.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import core.common.CoreCommon;
import core.common.logging.LoggingCore;
import core.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loader-agnostic dashboard HTTP service.
 */
public final class DashboardService {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile boolean initialized;
    private static volatile HttpServer server;
    private static volatile RuntimeSnapshot snapshot = RuntimeSnapshot.empty();
    private static volatile ControlBridge controlBridge;

    private DashboardService() {}

    public static void init() {
        if (initialized) return;
        initialized = true;
        var cfg = ConfigManager.getConfig();
        boolean enabled = cfg != null && cfg.systems != null && cfg.systems.dashboard && dashboardEnabled(cfg);
        if (!enabled) {
            LOGGER.info("Common DashboardService initialized (enabled=false)");
            return;
        }

        String host = dashboardHost(cfg);
        int port = dashboardPort(cfg);
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
            http.createContext("/", DashboardService::handleIndex);
            http.createContext("/api/status", DashboardService::handleStatus);
            http.createContext("/api/players", DashboardService::handlePlayers);
            http.createContext("/api/events", DashboardService::handleEvents);
            http.createContext("/api/action/command", DashboardService::handleActionCommand);
            http.setExecutor(null);
            http.start();
            server = http;
            LOGGER.info("Dashboard started at http://{}:{}/", host, port);
        } catch (IOException e) {
            LOGGER.error("Failed to start dashboard at {}:{}", host, port, e);
        }
    }

    public static void shutdown() {
        HttpServer current = server;
        if (current != null) {
            current.stop(0);
            server = null;
        }
    }

    public static void updateSnapshot(boolean online, String motd, String version, int maxPlayers, List<String> players) {
        snapshot = new RuntimeSnapshot(
            online,
            safe(motd, "Minecraft Server"),
            safe(version, "unknown"),
            Math.max(0, maxPlayers),
            players == null ? List.of() : List.copyOf(players),
            Instant.now().toString()
        );
    }

    public static void ingestLogEvent(LoggingCore.RecentLogEntry entry) {
        // No-op hook for future dashboard-specific enrichments. Events are already read from LoggingCore buffer.
        if (entry == null) return;
    }

    public static void setControlBridge(ControlBridge bridge) {
        controlBridge = bridge;
    }

    private static void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        String watermark = CoreCommon.watermarkEnabled() ? CoreCommon.getWatermark() : "";
        String html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <meta name="viewport" content="width=device-width,initial-scale=1"/>
              <title>Core Dashboard</title>
              <style>
                :root{--bg:#0e1116;--card:#161b22;--text:#e6edf3;--muted:#8b949e;--accent:#3fb950;}
                body{margin:0;font-family:Segoe UI,system-ui,sans-serif;background:linear-gradient(160deg,#0e1116,#111827);color:var(--text);}
                .wrap{max-width:1100px;margin:20px auto;padding:0 12px;}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:12px;}
                .card{background:var(--card);border:1px solid #293142;border-radius:14px;padding:14px;}
                .title{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.08em;}
                .value{font-size:22px;font-weight:700;margin-top:6px}
                table{width:100%;border-collapse:collapse;font-size:13px}
                th,td{padding:7px;border-bottom:1px solid #293142;text-align:left;vertical-align:top}
                input{background:#0f141b;border:1px solid #293142;color:var(--text);padding:8px;border-radius:8px}
                button{background:var(--accent);border:0;color:#04110a;padding:8px 10px;border-radius:8px;font-weight:700;cursor:pointer}
                .row{display:flex;gap:8px;align-items:center;margin-bottom:12px}
                .wm{position:fixed;right:12px;bottom:10px;color:var(--muted);font-size:12px;opacity:.8}
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="row">
                  <input id="token" placeholder="Dashboard token"/>
                  <button onclick="refreshAll()">Load</button>
                </div>
                <div class="grid">
                  <div class="card"><div class="title">Online</div><div id="online" class="value">-</div></div>
                  <div class="card"><div class="title">Players</div><div id="players" class="value">-</div></div>
                  <div class="card"><div class="title">Version</div><div id="version" class="value">-</div></div>
                  <div class="card"><div class="title">MOTD</div><div id="motd" class="value" style="font-size:16px">-</div></div>
                </div>
                <div class="card" style="margin-top:12px"><div class="title">Online Players</div><pre id="playersList">[]</pre></div>
                <div class="card" style="margin-top:12px"><div class="title">Recent Events</div><table><thead><tr><th>Time</th><th>Channel</th><th>Message</th></tr></thead><tbody id="events"></tbody></table></div>
              </div>
              <div class="wm">__WATERMARK__</div>
              <script>
                const headers=()=>{const t=document.getElementById('token').value.trim();return t?{'Authorization':'Bearer '+t}:{}};
                async function loadStatus(){const r=await fetch('/api/status',{headers:headers()});return r.json();}
                async function loadPlayers(){const r=await fetch('/api/players',{headers:headers()});return r.json();}
                async function loadEvents(){const r=await fetch('/api/events',{headers:headers()});return r.json();}
                async function refreshAll(){
                  const [s,p,e]=await Promise.all([loadStatus(),loadPlayers(),loadEvents()]);
                  document.getElementById('online').textContent=s.online?'Yes':'No';
                  document.getElementById('players').textContent=(s.players??0)+' / '+(s.maxPlayers??0);
                  document.getElementById('version').textContent=s.version??'-';
                  document.getElementById('motd').textContent=s.motd??'-';
                  document.getElementById('playersList').textContent=JSON.stringify(p.players??[],null,2);
                  const tbody=document.getElementById('events');tbody.innerHTML='';
                  for (const ev of (e.events??[])) {
                    const tr=document.createElement('tr');
                    tr.innerHTML='<td>'+new Date(ev.timestamp).toLocaleTimeString()+'</td><td>'+ev.channel+'</td><td>'+ev.message+'</td>';
                    tbody.appendChild(tr);
                  }
                }
              </script>
            </body>
            </html>
            """;
        sendText(exchange, 200, "text/html; charset=utf-8", html.replace("__WATERMARK__", escapeHtml(watermark)));
    }

    private static void handleStatus(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }
        RuntimeSnapshot s = snapshot;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", s.online);
        out.put("motd", s.motd);
        out.put("version", s.version);
        out.put("maxPlayers", s.maxPlayers);
        out.put("players", s.players.size());
        out.put("timestamp", s.timestamp);
        out.put("watermark", CoreCommon.watermarkEnabled() ? CoreCommon.getWatermark() : "");
        sendJson(exchange, 200, out);
    }

    private static void handlePlayers(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }
        sendJson(exchange, 200, Map.of("players", snapshot.players));
    }

    private static void handleEvents(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }
        var cfg = ConfigManager.getConfig();
        int limit = cfg != null && cfg.dashboard != null ? Math.max(20, Math.min(800, cfg.dashboard.eventLimit)) : 200;
        List<Map<String, Object>> events = new ArrayList<>();
        for (LoggingCore.RecentLogEntry e : LoggingCore.getRecentLogs(limit)) {
            events.add(Map.of("timestamp", e.timestamp, "channel", e.channel, "message", e.message));
        }
        sendJson(exchange, 200, Map.of("events", events));
    }

    private static void handleActionCommand(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            sendJson(exchange, 401, Map.of("error", "unauthorized"));
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
            return;
        }
        JsonObject body = readBodyJson(exchange.getRequestBody());
        String command = body.has("command") ? safe(body.get("command").getAsString(), "") : "";
        if (command.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "missing_command"));
            return;
        }
        if (command.startsWith("/")) command = command.substring(1);

        ControlBridge bridge = controlBridge;
        if (bridge == null) {
            sendJson(exchange, 503, Map.of("error", "bridge_unavailable"));
            return;
        }
        boolean ok = bridge.executeConsoleCommand(command);
        sendJson(exchange, ok ? 200 : 500, Map.of("ok", ok, "command", command));
    }

    private static boolean authorized(HttpExchange exchange) {
        var cfg = ConfigManager.getConfig();
        String token = cfg != null && cfg.dashboard != null ? safe(cfg.dashboard.token, "") : "";
        if (token.isBlank()) return true;
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return token.equals(auth.substring("Bearer ".length()).trim());
        }
        String queryToken = extractQueryParam(exchange.getRequestURI(), "token");
        return token.equals(queryToken);
    }

    private static String extractQueryParam(URI uri, String key) {
        if (uri == null || uri.getRawQuery() == null) return "";
        String[] parts = uri.getRawQuery().split("&");
        for (String part : parts) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            String k = part.substring(0, i);
            if (k.equals(key)) return part.substring(i + 1);
        }
        return "";
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        sendText(exchange, status, "application/json; charset=utf-8", GSON.toJson(body));
    }

    private static void sendText(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        } finally {
            exchange.close();
        }
    }

    private static JsonObject readBodyJson(InputStream body) {
        try {
            String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) return new JsonObject();
            var parsed = GSON.fromJson(text, JsonObject.class);
            return parsed == null ? new JsonObject() : parsed;
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private static boolean dashboardEnabled(ConfigManager.Config cfg) {
        if (cfg == null) return false;
        boolean modern = cfg.dashboard != null && cfg.dashboard.enabled;
        boolean legacy = cfg.logging != null && cfg.logging.dashboardEnabled;
        return modern || legacy;
    }

    private static String dashboardHost(ConfigManager.Config cfg) {
        if (cfg != null && cfg.dashboard != null && cfg.dashboard.host != null && !cfg.dashboard.host.isBlank()) return cfg.dashboard.host;
        if (cfg != null && cfg.logging != null && cfg.logging.dashboardHost != null && !cfg.logging.dashboardHost.isBlank()) return cfg.logging.dashboardHost;
        return "127.0.0.1";
    }

    private static int dashboardPort(ConfigManager.Config cfg) {
        if (cfg != null && cfg.dashboard != null && cfg.dashboard.port > 0) return cfg.dashboard.port;
        if (cfg != null && cfg.logging != null && cfg.logging.dashboardPort > 0) return cfg.logging.dashboardPort;
        return 8787;
    }

    private static String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String escapeHtml(String value) {
        return safe(value, "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private record RuntimeSnapshot(
        boolean online,
        String motd,
        String version,
        int maxPlayers,
        List<String> players,
        String timestamp
    ) {
        static RuntimeSnapshot empty() {
            return new RuntimeSnapshot(false, "Minecraft Server", "unknown", 20, List.of(), Instant.now().toString());
        }
    }

    public interface ControlBridge {
        boolean executeConsoleCommand(String command);
    }
}
