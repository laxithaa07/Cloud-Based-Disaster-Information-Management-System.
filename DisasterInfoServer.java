import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DisasterInfoServer {
    private static final int PORT = 8080;
    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path FRONTEND = ROOT.resolve("frontend").normalize();
    private static final Path DATA_DIR = ROOT.resolve("data").normalize();
    private static final Path DATA_FILE = DATA_DIR.resolve("incidents.json");
    private static final Pattern FIELD = Pattern.compile("\\\"([a-zA-Z]+)\\\"\\s*:\\s*\\\"((?:\\\\\\\\.|[^\\\"\\\\\\\\])*)\\\"");
    private static final List<Incident> INCIDENTS = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws IOException {
        if (!Files.isDirectory(FRONTEND)) {
            System.err.println("Frontend folder not found. Run this command from the project root.");
            System.exit(1);
        }
        loadData();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/api/incidents", DisasterInfoServer::handleApi);
        server.createContext("/", DisasterInfoServer::handleStatic);
        server.start();
        System.out.println("Cloud-Based Disaster Information Management System running at http://localhost:" + PORT);
        System.out.println("Demo runs locally. Press Ctrl+C to stop.");
    }

    private static synchronized void handleApi(HttpExchange ex) throws IOException {
        commonHeaders(ex.getResponseHeaders());
        String method = ex.getRequestMethod().toUpperCase();
        if ("OPTIONS".equals(method)) {
            ex.getResponseHeaders().set("Allow", "GET, POST, PATCH, DELETE, OPTIONS");
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        try {
            switch (method) {
                case "GET" -> {
                    StringBuilder json = new StringBuilder("[");
                    for (int i = 0; i < INCIDENTS.size(); i++) {
                        if (i > 0) json.append(',');
                        json.append(INCIDENTS.get(i).toJson());
                    }
                    json.append(']');
                    send(ex, 200, json.toString(), "application/json; charset=utf-8");
                }
                case "POST" -> createIncident(ex);
                case "PATCH" -> updateIncident(ex);
                case "DELETE" -> deleteIncident(ex);
                default -> {
                    ex.getResponseHeaders().set("Allow", "GET, POST, PATCH, DELETE, OPTIONS");
                    send(ex, 405, "{\"error\":\"Method not allowed.\"}", "application/json; charset=utf-8");
                }
            }
        } catch (IllegalArgumentException e) {
            send(ex, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json; charset=utf-8");
        } catch (Exception e) {
            send(ex, 500, "{\"error\":\"Server could not complete the request.\"}", "application/json; charset=utf-8");
        }
    }

    private static void createIncident(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        var f = fields(body);
        String type = required(f, "type", 60);
        String severity = required(f, "severity", 20);
        String location = required(f, "location", 120);
        String description = required(f, "description", 1000);
        String contact = optional(f, "contact", 80);
        if (!List.of("Flood", "Earthquake", "Cyclone", "Wildfire", "Landslide", "Storm", "Other").contains(type))
            throw new IllegalArgumentException("Choose a valid disaster type.");
        if (!List.of("Low", "Moderate", "High", "Critical").contains(severity))
            throw new IllegalArgumentException("Choose a valid severity.");
        Incident item = new Incident("DIS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                type, severity, location, description, contact, "Reported", Instant.now().toString());
        INCIDENTS.add(0, item);
        saveData();
        send(ex, 201, item.toJson(), "application/json; charset=utf-8");
    }

    private static void updateIncident(HttpExchange ex) throws IOException {
        String id = queryParam(ex.getRequestURI().getRawQuery(), "id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Incident id is required.");
        var f = fields(readBody(ex));
        String status = required(f, "status", 30);
        if (!List.of("Reported", "Verifying", "Response in progress", "Resolved").contains(status))
            throw new IllegalArgumentException("Choose a valid status.");
        for (Incident item : INCIDENTS) {
            if (item.id.equals(id)) {
                item.status = status;
                saveData();
                send(ex, 200, item.toJson(), "application/json; charset=utf-8");
                return;
            }
        }
        send(ex, 404, "{\"error\":\"Incident not found.\"}", "application/json; charset=utf-8");
    }

    private static void deleteIncident(HttpExchange ex) throws IOException {
        String id = queryParam(ex.getRequestURI().getRawQuery(), "id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Incident id is required.");
        boolean removed = INCIDENTS.removeIf(item -> item.id.equals(id));
        if (!removed) {
            send(ex, 404, "{\"error\":\"Incident not found.\"}", "application/json; charset=utf-8");
            return;
        }
        saveData();
        send(ex, 200, "{\"success\":true}", "application/json; charset=utf-8");
    }

    private static String readBody(HttpExchange ex) throws IOException {
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/json"))
            throw new IllegalArgumentException("Content-Type must be application/json.");
        byte[] bytes = ex.getRequestBody().readNBytes(16385);
        if (bytes.length > 16384) throw new IllegalArgumentException("Request body is too large.");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static java.util.Map<String, String> fields(String json) {
        java.util.Map<String, String> result = new java.util.HashMap<>();
        Matcher m = FIELD.matcher(json);
        while (m.find()) result.put(m.group(1), unescape(m.group(2)));
        return result;
    }

    private static String required(java.util.Map<String, String> f, String key, int max) {
        String value = f.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " is required.");
        value = value.trim();
        if (value.length() > max) throw new IllegalArgumentException(key + " must be " + max + " characters or fewer.");
        return value;
    }

    private static String optional(java.util.Map<String, String> f, String key, int max) {
        String value = f.getOrDefault(key, "").trim();
        if (value.length() > max) throw new IllegalArgumentException(key + " must be " + max + " characters or fewer.");
        return value;
    }

    private static String queryParam(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(key))
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    private static synchronized void loadData() throws IOException {
        Files.createDirectories(DATA_DIR);
        if (!Files.exists(DATA_FILE)) {
            Files.writeString(DATA_FILE, "[]", StandardCharsets.UTF_8);
            return;
        }
        String content = Files.readString(DATA_FILE, StandardCharsets.UTF_8).trim();
        if (content.isEmpty() || content.equals("[]")) return;
        // Simple line-independent JSON object extraction for this dependency-free demo.
        Matcher objects = Pattern.compile("\\{[^{}]*}").matcher(content);
        while (objects.find()) {
            var f = fields(objects.group());
            if (f.containsKey("id") && f.containsKey("type") && f.containsKey("severity")
                    && f.containsKey("location") && f.containsKey("description")
                    && f.containsKey("status") && f.containsKey("createdAt")) {
                INCIDENTS.add(new Incident(f.get("id"), f.get("type"), f.get("severity"),
                        f.get("location"), f.get("description"), f.getOrDefault("contact", ""),
                        f.get("status"), f.get("createdAt")));
            }
        }
    }

    private static synchronized void saveData() throws IOException {
        Files.createDirectories(DATA_DIR);
        StringBuilder json = new StringBuilder("[\n");
        for (int i = 0; i < INCIDENTS.size(); i++) {
            if (i > 0) json.append(",\n");
            json.append("  ").append(INCIDENTS.get(i).toJson());
        }
        json.append("\n]\n");
        Files.writeString(DATA_FILE, json.toString(), StandardCharsets.UTF_8);
    }

    private static void handleStatic(HttpExchange ex) throws IOException {
        commonHeaders(ex.getResponseHeaders());
        String method = ex.getRequestMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            send(ex, 405, "Method not allowed", "text/plain; charset=utf-8");
            return;
        }
        String urlPath = ex.getRequestURI().getPath();
        if (urlPath.equals("/")) urlPath = "/index.html";
        Path file = FRONTEND.resolve(urlPath.substring(1)).normalize();
        if (!file.startsWith(FRONTEND) || !Files.isRegularFile(file)) {
            send(ex, 404, "Not found", "text/plain; charset=utf-8");
            return;
        }
        String name = file.getFileName().toString().toLowerCase();
        String mime = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", mime);
        if ("HEAD".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        } else {
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        }
    }

    private static void commonHeaders(Headers h) {
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'");
    }

    private static void send(HttpExchange ex, int status, String body, String mime) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.toString();
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { out.append(c); continue; }
            char n = value.charAt(++i);
            switch (n) {
                case '"', '\\', '/' -> out.append(n);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        try { out.append((char)Integer.parseInt(value.substring(i + 1, i + 5), 16)); i += 4; }
                        catch (NumberFormatException e) { out.append('u'); }
                    } else out.append('u');
                }
                default -> out.append(n);
            }
        }
        return out.toString();
    }

    private static final class Incident {
        final String id, type, severity, location, description, contact, createdAt;
        volatile String status;
        Incident(String id, String type, String severity, String location, String description,
                 String contact, String status, String createdAt) {
            this.id = id; this.type = type; this.severity = severity; this.location = location;
            this.description = description; this.contact = contact; this.status = status; this.createdAt = createdAt;
        }
        String toJson() {
            return "{\"id\":\"" + escape(id) + "\",\"type\":\"" + escape(type)
                    + "\",\"severity\":\"" + escape(severity) + "\",\"location\":\"" + escape(location)
                    + "\",\"description\":\"" + escape(description) + "\",\"contact\":\"" + escape(contact)
                    + "\",\"status\":\"" + escape(status) + "\",\"createdAt\":\"" + escape(createdAt) + "\"}";
        }
    }
}
