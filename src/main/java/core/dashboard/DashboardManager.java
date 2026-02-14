package core.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import core.config.ConfigManager;
import core.logging.LoggingManager;
import core.util.Safe;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public final class DashboardManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("core");
    private static final Gson GSON = new GsonBuilder().create();
    private static HttpServer httpServer;
    private static MinecraftServer server;
    private static long startedAtMs;

    private DashboardManager() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> Safe.run("DashboardManager.start", () -> start(s)));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Safe.run("DashboardManager.stop", DashboardManager::stop));
    }

    private static void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        startedAtMs = System.currentTimeMillis();
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || !cfg.logging.dashboardEnabled) {
            LOGGER.info("Dashboard disabled in config.");
            return;
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(cfg.logging.dashboardHost, cfg.logging.dashboardPort), 0);
            httpServer.createContext("/", DashboardManager::handleIndex);
            httpServer.createContext("/api/status", DashboardManager::handleStatus);
            httpServer.createContext("/api/players", DashboardManager::handlePlayers);
            httpServer.createContext("/api/events", DashboardManager::handleEvents);
            httpServer.createContext("/api/events.csv", DashboardManager::handleEventsCsv);
            httpServer.createContext("/api/summary", DashboardManager::handleSummary);
            httpServer.createContext("/api/metrics", DashboardManager::handleMetrics);
            httpServer.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "core-dashboard-http");
                t.setDaemon(true);
                return t;
            }));
            httpServer.start();
            LOGGER.info("Dashboard started at http://{}:{}/", cfg.logging.dashboardHost, cfg.logging.dashboardPort);
        } catch (IOException e) {
            LOGGER.error("Failed to start dashboard HTTP server", e);
        }
    }

    private static void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
            LOGGER.info("Dashboard stopped.");
        }
        server = null;
    }

    private static void handleIndex(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        String html = """
            <!doctype html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Core Dashboard</title>
            <style>
            :root{--bg:#0b0f16;--panel:#141a24;--panel2:#10151f;--text:#e5edf7;--muted:#8da2ba;--line:#263243;--ok:#27d17f;--err:#ff5f6d;--chip:#1d2635}
            *{box-sizing:border-box} body{margin:0;font-family:Segoe UI,system-ui,sans-serif;background:radial-gradient(1200px 600px at 20% -10%,#1b2638 0%,var(--bg) 55%);color:var(--text)}
            .wrap{max-width:1200px;margin:24px auto;padding:0 16px}
            .top{display:flex;justify-content:space-between;align-items:center;margin-bottom:14px}
            .title{font-size:24px;font-weight:700}
            .subtitle{color:var(--muted);font-size:13px}
            .grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-bottom:12px}
            .kpi{background:linear-gradient(180deg,var(--panel),var(--panel2));border:1px solid var(--line);border-radius:10px;padding:12px}
            .kpi .label{font-size:12px;color:var(--muted)} .kpi .value{font-size:21px;font-weight:700;margin-top:4px}
            .ok{color:var(--ok)} .err{color:var(--err)}
            .card{background:linear-gradient(180deg,var(--panel),var(--panel2));border:1px solid var(--line);border-radius:10px;padding:12px;margin-bottom:12px}
            .row{display:grid;grid-template-columns:1fr 1fr;gap:12px}
            table{width:100%;border-collapse:collapse} th,td{padding:8px;border-bottom:1px solid var(--line);text-align:left;font-size:13px}
            th{color:var(--muted);font-weight:600}
            .toolbar{display:flex;gap:8px;align-items:center;margin-bottom:10px}
            select,input{background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px 8px}
            .mono{font-family:Consolas,Monaco,monospace}
            @media(max-width:960px){.grid{grid-template-columns:repeat(2,minmax(0,1fr))}.row{grid-template-columns:1fr}}
            </style></head><body>
            <div class='wrap'>
              <div class='top'>
                <div>
                  <div class='title'>Core Mod Dashboard</div>
                  <div class='subtitle'>Live server telemetry</div>
                </div>
                <div class='subtitle' id='lastUpdate'>updated: -</div>
              </div>
              <div class='grid'>
                <div class='kpi'><div class='label'>Server</div><div class='value' id='kOnline'>-</div></div>
                <div class='kpi'><div class='label'>Players</div><div class='value' id='kPlayers'>-</div></div>
                <div class='kpi'><div class='label'>Version</div><div class='value' id='kVersion'>-</div></div>
                <div class='kpi'><div class='label'>MOTD</div><div class='value' id='kMotd'>-</div></div>
                <div class='kpi'><div class='label'>Uptime</div><div class='value' id='kUptime'>-</div></div>
                <div class='kpi'><div class='label'>Events (last 200)</div><div class='value' id='kEventRate'>-</div></div>
                <div class='kpi'><div class='label'>Memory</div><div class='value' id='kMemory'>-</div></div>
                <div class='kpi'><div class='label'>Threads</div><div class='value' id='kThreads'>-</div></div>
              </div>
              <div class='row'>
                <div class='card'>
                  <b>Players Online</b>
                  <table id='playersTable'>
                    <thead><tr><th>Name</th><th>World</th><th>Pos</th></tr></thead>
                    <tbody><tr><td colspan='3'>loading...</td></tr></tbody>
                  </table>
                </div>
                <div class='card'>
                  <b>Event Channels</b>
                  <table id='channelsTable'>
                    <thead><tr><th>Channel</th><th>Count</th></tr></thead>
                    <tbody><tr><td colspan='2'>loading...</td></tr></tbody>
                  </table>
                  <br/>
                  <b>Raw Status</b>
                  <pre id='statusRaw' class='mono'>{}</pre>
                </div>
              </div>
              <div class='card'>
                <div class='toolbar'>
                  <b style='margin-right:auto'>Recent Events</b>
                  <input id='search' placeholder='search text'/>
                  <select id='channel'>
                    <option value='all'>all channels</option>
                    <option value='event'>event</option>
                    <option value='chat'>chat</option>
                    <option value='command'>command</option>
                    <option value='private'>private</option>
                  </select>
                  <input id='limit' type='number' min='10' max='500' step='10' value='80'/>
                  <button id='exportJsonBtn' style='background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px 10px'>Export JSON</button>
                  <button id='exportCsvBtn' style='background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px 10px'>Export CSV</button>
                  <button id='pauseBtn' style='background:#0e141e;color:var(--text);border:1px solid var(--line);border-radius:8px;padding:6px 10px'>Pause</button>
                </div>
                <table id='eventsTable'>
                  <thead><tr><th>Time</th><th>Channel</th><th>Message</th></tr></thead>
                  <tbody><tr><td colspan='3'>loading...</td></tr></tbody>
                </table>
              </div>
            </div>
            <script>
            let paused = false;
            const token = new URLSearchParams(window.location.search).get('token');
            function withToken(path){
              if(!token) return path;
              return path + (path.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
            }
            function esc(v){return String(v??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
            function fmtDate(v){
              if(!v) return '-';
              try{
                const d = typeof v === 'number' ? new Date(v) : new Date(v);
                return d.toLocaleString();
              }catch(_){return String(v);}
            }
            async function load(){
              const limit = Math.max(10, Math.min(500, Number(document.getElementById('limit').value || 80)));
              if(paused) return;
              const [s,p,e,m,x]=await Promise.all([
                fetch(withToken('/api/status')),
                fetch(withToken('/api/players')),
                fetch(withToken('/api/events?limit=' + limit)),
                fetch(withToken('/api/summary')),
                fetch(withToken('/api/metrics'))
              ]);
              const status = await s.json();
              const players = await p.json();
              const events = await e.json();
              const summary = await m.json();
              const metrics = await x.json();

              if(status.error){throw new Error('status: ' + status.error);}
              if(players.error){throw new Error('players: ' + players.error);}
              if(events.error){throw new Error('events: ' + events.error);}
              if(summary.error){throw new Error('summary: ' + summary.error);}
              if(metrics.error){throw new Error('metrics: ' + metrics.error);}

              document.getElementById('kOnline').textContent = status.online ? 'ONLINE' : 'OFFLINE';
              document.getElementById('kOnline').className = 'value ' + (status.online ? 'ok' : 'err');
              document.getElementById('kPlayers').textContent = (status.players ?? 0) + ' / ' + (status.maxPlayers ?? 0);
              document.getElementById('kVersion').textContent = status.version ?? '-';
              document.getElementById('kMotd').textContent = status.motd ?? '-';
              document.getElementById('kUptime').textContent = summary.uptime ?? '-';
              document.getElementById('kEventRate').textContent = (summary.totalEventsLast200 ?? 0).toString();
              document.getElementById('kMemory').textContent = (metrics.heapUsedMB ?? 0) + ' / ' + (metrics.heapMaxMB ?? 0) + ' MB';
              document.getElementById('kThreads').textContent = String(metrics.threadCount ?? '-');
              document.getElementById('lastUpdate').textContent = 'updated: ' + fmtDate(status.timestamp);
              document.getElementById('statusRaw').textContent = JSON.stringify(status, null, 2);

              const pBody = document.querySelector('#playersTable tbody');
              const plist = Array.isArray(players.players) ? players.players : [];
              if(plist.length === 0){
                pBody.innerHTML = "<tr><td colspan='3'>No players online</td></tr>";
              } else {
                pBody.innerHTML = plist.map(x =>
                  "<tr><td>" + esc(x.name) + "</td><td>" + esc(x.world) + "</td><td>" +
                  [Math.round(x.x||0),Math.round(x.y||0),Math.round(x.z||0)].join(', ') + "</td></tr>"
                ).join('');
              }

              const channel = document.getElementById('channel').value;
              const search = (document.getElementById('search').value || '').toLowerCase();
              let ev = Array.isArray(events.events) ? events.events : [];
              if(channel !== 'all'){ ev = ev.filter(x => (x.channel || '') === channel); }
              if(search){ ev = ev.filter(x => String(x.message || '').toLowerCase().includes(search)); }
              const eBody = document.querySelector('#eventsTable tbody');
              if(ev.length === 0){
                eBody.innerHTML = "<tr><td colspan='3'>No events</td></tr>";
              } else {
                eBody.innerHTML = ev.map(x =>
                  "<tr><td>" + esc(fmtDate(x.timestamp)) + "</td><td><span style='background:var(--chip);padding:2px 6px;border-radius:999px'>" + esc(x.channel) + "</span></td><td class='mono'>" + esc(x.message) + "</td></tr>"
                ).join('');
              }

              const chBody = document.querySelector('#channelsTable tbody');
              const counts = summary.channelCounts || {};
              const rows = Object.entries(counts).sort((a,b)=>b[1]-a[1]);
              if(rows.length === 0){
                chBody.innerHTML = "<tr><td colspan='2'>No channel data</td></tr>";
              } else {
                chBody.innerHTML = rows.map(r => "<tr><td>" + esc(r[0]) + "</td><td>" + esc(r[1]) + "</td></tr>").join('');
              }
            }
            document.getElementById('channel').addEventListener('change', load);
            document.getElementById('limit').addEventListener('change', load);
            document.getElementById('search').addEventListener('input', load);
            document.getElementById('exportJsonBtn').addEventListener('click', () => {
              const limit = Math.max(10, Math.min(500, Number(document.getElementById('limit').value || 80)));
              const channel = document.getElementById('channel').value;
              const search = encodeURIComponent(document.getElementById('search').value || '');
              window.open(withToken('/api/events?limit=' + limit + '&channel=' + encodeURIComponent(channel) + '&search=' + search), '_blank');
            });
            document.getElementById('exportCsvBtn').addEventListener('click', () => {
              const limit = Math.max(10, Math.min(500, Number(document.getElementById('limit').value || 80)));
              const channel = document.getElementById('channel').value;
              const search = encodeURIComponent(document.getElementById('search').value || '');
              window.open(withToken('/api/events.csv?limit=' + limit + '&channel=' + encodeURIComponent(channel) + '&search=' + search), '_blank');
            });
            document.getElementById('pauseBtn').addEventListener('click', () => {
              paused = !paused;
              document.getElementById('pauseBtn').textContent = paused ? 'Resume' : 'Pause';
              if(!paused) load();
            });
            load(); setInterval(load, 3000);
            </script></body></html>
            """;
        send(ex, 200, "text/html; charset=utf-8", html);
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new HashMap<>();
        out.put("online", server != null);
        out.put("timestamp", Instant.now().toString());
        if (server != null) {
            out.put("motd", server.getServerMotd());
            out.put("players", server.getCurrentPlayerCount());
            out.put("maxPlayers", server.getMaxPlayerCount());
            out.put("version", server.getVersion());
        }
        sendJson(ex, out);
    }

    private static void handlePlayers(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Map<String, Object> out = new HashMap<>();
        if (server == null) {
            out.put("players", List.of());
            sendJson(ex, out);
            return;
        }
        List<Map<String, Object>> players = server.getPlayerManager().getPlayerList().stream().map(p -> {
            Map<String, Object> row = new HashMap<>();
            row.put("name", p.getName().getString());
            row.put("uuid", p.getUuidAsString());
            row.put("world", p.getEntityWorld().getRegistryKey().getValue().toString());
            row.put("x", p.getX());
            row.put("y", p.getY());
            row.put("z", p.getZ());
            return row;
        }).toList();
        out.put("players", players);
        sendJson(ex, out);
    }

    private static void handleEvents(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        var query = query(ex);
        int limit = parseInt(query.get("limit"), 50);
        String channel = query.getOrDefault("channel", "all");
        String search = query.getOrDefault("search", "");
        var entries = filterEvents(limit, channel, search).stream().map(e -> {
            Map<String, Object> row = new HashMap<>();
            row.put("timestamp", e.timestamp);
            row.put("channel", e.channel);
            row.put("message", e.message);
            return row;
        }).toList();
        Map<String, Object> out = new HashMap<>();
        out.put("events", entries);
        sendJson(ex, out);
    }

    private static void handleEventsCsv(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "text/plain", "method_not_allowed");
            return;
        }
        var query = query(ex);
        int limit = parseInt(query.get("limit"), 100);
        String channel = query.getOrDefault("channel", "all");
        String search = query.getOrDefault("search", "");
        var entries = filterEvents(limit, channel, search);

        StringBuilder sb = new StringBuilder("timestamp,channel,message\n");
        for (var e : entries) {
            sb.append(e.timestamp).append(',')
              .append(csv(e.channel)).append(',')
              .append(csv(e.message)).append('\n');
        }
        send(ex, 200, "text/csv; charset=utf-8", sb.toString());
    }

    private static void handleSummary(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }

        Map<String, Object> out = new HashMap<>();
        out.put("uptime", formatUptime(System.currentTimeMillis() - startedAtMs));
        Map<String, Integer> counts = LoggingManager.getRecentChannelCounts(200);
        out.put("channelCounts", counts);
        out.put("totalEventsLast200", counts.values().stream().mapToInt(Integer::intValue).sum());
        sendJson(ex, out);
    }

    private static void handleMetrics(HttpExchange ex) throws IOException {
        if (!authorized(ex)) return;
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 405, "application/json", "{\"error\":\"method_not_allowed\"}");
            return;
        }
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        Map<String, Object> out = new HashMap<>();
        out.put("heapUsedMB", used / (1024 * 1024));
        out.put("heapMaxMB", max / (1024 * 1024));
        out.put("threadCount", Thread.getAllStackTraces().size());
        out.put("onlinePlayers", server == null ? 0 : server.getCurrentPlayerCount());
        out.put("maxPlayers", server == null ? 0 : server.getMaxPlayerCount());
        sendJson(ex, out);
    }

    private static String formatUptime(long ms) {
        long totalSec = Math.max(0L, ms / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        return h + "h " + m + "m " + s + "s";
    }

    private static List<LoggingManager.RecentLogEntry> filterEvents(int limit, String channel, String search) {
        String wantedChannel = channel == null ? "all" : channel.trim().toLowerCase();
        String needle = search == null ? "" : search.trim().toLowerCase();
        return LoggingManager.getRecentLogs(limit).stream()
            .filter(e -> "all".equals(wantedChannel) || e.channel.equalsIgnoreCase(wantedChannel))
            .filter(e -> needle.isEmpty() || e.message.toLowerCase().contains(needle))
            .toList();
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null ? fallback : Integer.parseInt(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String q = ex.getRequestURI().getQuery();
        if (q == null || q.isBlank()) return out;
        for (String p : q.split("&")) {
            int i = p.indexOf('=');
            if (i <= 0) continue;
            String k = p.substring(0, i);
            String v = p.substring(i + 1);
            try {
                out.put(k, URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                out.put(k, v);
            }
        }
        return out;
    }

    private static String csv(String s) {
        if (s == null) return "";
        String esc = s.replace("\"", "\"\"");
        return "\"" + esc + "\"";
    }

    private static boolean authorized(HttpExchange ex) throws IOException {
        var cfg = ConfigManager.getConfig();
        if (cfg == null || cfg.logging == null || cfg.logging.dashboardToken == null || cfg.logging.dashboardToken.isBlank()) {
            return true;
        }
        String cfgToken = cfg.logging.dashboardToken.trim();
        String headerToken = trimToNull(ex.getRequestHeaders().getFirst("X-Core-Token"));
        String queryToken = trimToNull(getQueryToken(ex));
        String authHeader = trimToNull(ex.getRequestHeaders().getFirst("Authorization"));
        String bearerToken = authHeader != null && authHeader.toLowerCase().startsWith("bearer ")
            ? trimToNull(authHeader.substring(7))
            : null;

        if (cfgToken.equals(headerToken) || cfgToken.equals(queryToken) || cfgToken.equals(bearerToken)) {
            return true;
        }
        send(ex, 401, "application/json", "{\"error\":\"unauthorized\"}");
        return false;
    }

    private static String trimToNull(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t;
    }

    private static String getQueryToken(HttpExchange ex) {
        String q = ex.getRequestURI().getQuery();
        if (q == null || q.isBlank()) return null;
        for (String part : q.split("&")) {
            if (part.startsWith("token=")) {
                try {
                    return URLDecoder.decode(part.substring("token=".length()), StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                    return part.substring("token=".length());
                }
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange ex, Object obj) throws IOException {
        send(ex, 200, "application/json; charset=utf-8", GSON.toJson(obj));
    }

    private static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
    }
}
