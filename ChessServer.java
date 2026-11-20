import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Сервер-посредник: телефон (Java ME) <-> этот сервер (обычный HTTP) <-> Lichess (HTTPS).
 *
 * Запуск (нужна Java 11+):
 *   set LICHESS_TOKEN=lip_xxxxxxxx        (Windows)
 *   export LICHESS_TOKEN=lip_xxxxxxxx     (Linux / Mac)
 *   java ChessServer.java
 *
 * Необязательно: PIN=1234 (пароль, который надо ввести и в телефоне), PORT=8080.
 */
public class ChessServer {

    static final String TOKEN = System.getenv("LICHESS_TOKEN");
    static final String PIN = emptyToNull(System.getenv("PIN"));
    static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final Object LOCK = new Object();

    // ---- состояние (всё под LOCK) ----
    static String status = "idle";          // idle | seeking | playing | over
    static String gameId = null;
    static String myColor = "w";
    static String opponent = "";
    static String result = "";              // win:mate / loss:resign / draw:stalemate / aborted
    static String moves = "";               // ходы UCI через пробел
    static long wtime, btime;               // миллисекунды
    static final Map<String, String> challenges = new LinkedHashMap<>();
    static int seekNo = 0;
    static volatile CompletableFuture<HttpResponse<InputStream>> seekFuture;
    static volatile InputStream seekStream;

    public static void main(String[] args) throws Exception {
        if (TOKEN == null || TOKEN.isBlank()) {
            System.out.println("Не задан LICHESS_TOKEN. См. README.md");
            return;
        }
        // проверка токена
        HttpResponse<String> me = HTTP.send(req("/api/account").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (me.statusCode() != 200) {
            System.out.println("Токен не подошёл (код " + me.statusCode() + "). Создайте новый токен, см. README.md");
            return;
        }
        System.out.println("Вошли в Lichess как: " + str(asMap(Json.parse(me.body())), "username"));

        // поток событий (вызовы, начало партии)
        Thread ev = new Thread(() -> {
            while (true) {
                try {
                    stream("/api/stream/event", ChessServer::onEvent);
                } catch (Exception e) {
                    System.out.println("event stream: " + e);
                }
                sleep(3000);
            }
        });
        ev.setDaemon(true);
        ev.start();

        HttpServer srv = HttpServer.create(new InetSocketAddress(PORT), 0);
        srv.createContext("/", ex -> {
            String out;
            try {
                out = handle(ex.getRequestURI().getPath(), query(ex.getRequestURI().getRawQuery()));
            } catch (Exception e) {
                System.out.println("handler: " + e);
                out = "ERR";
            }
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        srv.setExecutor(Executors.newCachedThreadPool());
        srv.start();

        System.out.println("Сервер запущен. В телефоне введите один из адресов:");
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                    System.out.println("   " + a.getHostAddress() + ":" + PORT);
                }
            }
        }
    }

    // ================= команды от телефона =================

    static String handle(String path, Map<String, String> q) throws Exception {
        if (PIN != null && !PIN.equals(q.get("k"))) return "ERR";
        switch (path) {
            case "/state":
                return stateText();

            case "/seek": {
                int t = Integer.parseInt(q.getOrDefault("t", "3"));
                int i = Integer.parseInt(q.getOrDefault("i", "0"));
                if (t < 1 || t > 60 || i < 0 || i > 60) return "ERR";
                synchronized (LOCK) {
                    if (status.equals("playing")) return "ERR";
                }
                startSeek(t, i);
                return "OK";
            }

            case "/cancel": {
                synchronized (LOCK) {
                    seekNo++;
                    if (status.equals("seeking")) status = "idle";
                }
                CompletableFuture<HttpResponse<InputStream>> f = seekFuture;
                if (f != null) f.cancel(true);
                try {
                    InputStream s = seekStream;
                    if (s != null) s.close();
                } catch (IOException ignored) {
                }
                return "OK";
            }

            case "/accept":
            case "/decline": {
                String id = q.get("id");
                if (id == null || !id.matches("[A-Za-z0-9]+")) return "ERR";
                int code = post("/api/challenge/" + id + "/" + path.substring(1), "");
                synchronized (LOCK) {
                    challenges.remove(id);
                }
                return code == 200 ? "OK" : "ERR";
            }

            case "/move": {
                String m = q.get("m");
                if (m == null || !m.matches("[a-h][1-8][a-h][1-8][qrbn]?")) return "ERR";
                String id;
                synchronized (LOCK) {
                    if (!status.equals("playing")) return "ERR";
                    id = gameId;
                }
                return post("/api/board/game/" + id + "/move/" + m, "") == 200 ? "OK" : "ERR";
            }

            case "/resign": {
                String id;
                int n;
                synchronized (LOCK) {
                    if (!status.equals("playing")) return "ERR";
                    id = gameId;
                    n = moves.isBlank() ? 0 : moves.trim().split(" ").length;
                }
                // до двух ходов партию можно только отменить
                String what = n < 2 ? "abort" : "resign";
                return post("/api/board/game/" + id + "/" + what, "") == 200 ? "OK" : "ERR";
            }

            default:
                return "ERR";
        }
    }

    /** Формат ответа — простые строки "буква:значение", чтобы телефону было легко разбирать. */
    static String stateText() {
        synchronized (LOCK) {
            String[] mv = moves.isBlank() ? new String[0] : moves.trim().split(" ");
            Board b = new Board();
            for (String m : mv) b.apply(m);
            StringBuilder sb = new StringBuilder();
            sb.append("S:").append(status).append('\n');
            sb.append("B:").append(new String(b.sq)).append('\n');
            sb.append("C:").append(myColor).append('\n');
            sb.append("T:").append(mv.length % 2 == 0 ? "w" : "b").append('\n');
            sb.append("N:").append(mv.length).append('\n');
            sb.append("L:").append(mv.length > 0 ? mv[mv.length - 1] : "-").append('\n');
            sb.append("W:").append(wtime).append('\n');
            sb.append("K:").append(btime).append('\n');
            sb.append("O:").append(opponent).append('\n');
            sb.append("R:").append(result).append('\n');
            for (Map.Entry<String, String> e : challenges.entrySet()) {
                sb.append("H:").append(e.getKey()).append('|').append(e.getValue()).append('\n');
            }
            return sb.toString();
        }
    }

    // ================= Lichess =================

    static void startSeek(int t, int inc) {
        final int my;
        synchronized (LOCK) {
            status = "seeking";
            result = "";
            my = ++seekNo;
        }
        new Thread(() -> {
            try {
                HttpRequest r = req("/api/board/seek")
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "rated=false&time=" + t + "&increment=" + inc + "&color=random"))
                        .build();
                CompletableFuture<HttpResponse<InputStream>> f =
                        HTTP.sendAsync(r, HttpResponse.BodyHandlers.ofInputStream());
                seekFuture = f;
                HttpResponse<InputStream> resp = f.get();
                seekStream = resp.body();
                if (resp.statusCode() != 200) {
                    System.out.println("seek: код " + resp.statusCode() + " "
                            + new String(resp.body().readAllBytes(), StandardCharsets.UTF_8));
                } else {
                    InputStream in = resp.body();
                    while (in.read() != -1) { /* ждём, пока поиск закончится */ }
                }
            } catch (Exception e) {
                // отмена или сеть
            }
            sleep(3000);
            synchronized (LOCK) {
                if (my == seekNo && status.equals("seeking")) status = "idle";
            }
        }).start();
    }

    static void onEvent(Map<String, Object> e) {
        String type = str(e, "type");
        if (type == null) return;
        synchronized (LOCK) {
            switch (type) {
                case "gameStart": {
                    Map<String, Object> g = map(e, "game");
                    String id = str(g, "gameId");
                    if (id == null) id = str(g, "id");
                    if (id == null || (id.equals(gameId) && status.equals("playing"))) return;
                    final String gid = id;
                    gameId = gid;
                    myColor = "white".equals(str(g, "color")) ? "w" : "b";
                    Map<String, Object> opp = map(g, "opponent");
                    opponent = opp != null && str(opp, "username") != null ? str(opp, "username") : "?";
                    moves = "";
                    result = "";
                    wtime = btime = 0;
                    status = "playing";
                    challenges.clear();
                    new Thread(() -> gameLoop(gid)).start();
                    System.out.println("Партия началась: https://lichess.org/" + gid);
                    break;
                }
                case "challenge": {
                    Map<String, Object> c = map(e, "challenge");
                    Map<String, Object> variant = map(c, "variant");
                    if (variant != null && !"standard".equals(str(variant, "key"))) return;
                    String id = str(c, "id");
                    Map<String, Object> who = map(c, "challenger");
                    Map<String, Object> tc = map(c, "timeControl");
                    String name = who != null && str(who, "name") != null ? str(who, "name") : "?";
                    String show = tc != null && str(tc, "show") != null ? str(tc, "show") : "-";
                    if (id != null) challenges.put(id, name + "|" + show);
                    break;
                }
                case "challengeCanceled":
                case "challengeDeclined": {
                    String id = str(map(e, "challenge"), "id");
                    if (id != null) challenges.remove(id);
                    break;
                }
                default:
            }
        }
    }

    static void gameLoop(String id) {
        while (true) {
            synchronized (LOCK) {
                if (!id.equals(gameId) || !status.equals("playing")) return;
            }
            try {
                stream("/api/board/game/stream/" + id, e -> {
                    String type = str(e, "type");
                    Map<String, Object> st = null;
                    if ("gameFull".equals(type)) {
                        st = map(e, "state");
                        synchronized (LOCK) {
                            if (id.equals(gameId)) {
                                String w = str(map(e, "white"), "name");
                                String b = str(map(e, "black"), "name");
                                String o = myColor.equals("w") ? b : w;
                                opponent = o != null ? o : "Stockfish";
                            }
                        }
                    } else if ("gameState".equals(type)) {
                        st = e;
                    }
                    if (st != null) applyState(id, st);
                });
            } catch (Exception ex) {
                System.out.println("game stream: " + ex);
            }
            sleep(2000);
        }
    }

    static void applyState(String id, Map<String, Object> st) {
        synchronized (LOCK) {
            if (!id.equals(gameId)) return;
            String m = str(st, "moves");
            moves = m == null ? "" : m;
            wtime = num(st, "wtime");
            btime = num(st, "btime");
            String s = str(st, "status");
            if (s == null || s.equals("created") || s.equals("started")) {
                status = "playing";
                return;
            }
            status = "over";
            String winner = str(st, "winner");
            if (winner == null) {
                result = s.equals("aborted") || s.equals("noStart") ? "aborted" : "draw:" + s;
            } else {
                boolean iWon = winner.startsWith(myColor);   // "white".startsWith("w")
                result = (iWon ? "win:" : "loss:") + s;
            }
            System.out.println("Партия окончена: " + result);
        }
    }

    // ================= вспомогательное =================

    static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("https://lichess.org" + path))
                .header("Authorization", "Bearer " + TOKEN);
    }

    static int post(String path, String body) throws Exception {
        HttpRequest r = req(path)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) System.out.println("POST " + path + " -> " + resp.statusCode() + " " + resp.body());
        return resp.statusCode();
    }

    @SuppressWarnings("unchecked")
    static void stream(String path, Consumer<Map<String, Object>> handler) throws Exception {
        HttpResponse<InputStream> resp = HTTP.send(req(path).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                handler.accept((Map<String, Object>) Json.parse(line));
            }
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    static Map<String, Object> map(Map<String, Object> m, String k) {
        return m == null ? null : asMap(m.get(k));
    }

    static String str(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof String ? (String) o : null;
    }

    static long num(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    // ================= шахматная доска (только применение ходов) =================

    /** sq[0] = a8, sq[7] = h8, sq[56] = a1, sq[63] = h1. Белые — заглавные, чёрные — строчные. */
    static class Board {
        char[] sq = "rnbqkbnrpppppppp................................PPPPPPPPRNBQKBNR".toCharArray();

        static int idx(char file, char rank) {
            return (7 - (rank - '1')) * 8 + (file - 'a');
        }

        void apply(String m) {
            int f = idx(m.charAt(0), m.charAt(1));
            int t = idx(m.charAt(2), m.charAt(3));
            char p = sq[f];

            if (p == 'K' || p == 'k') {                       // рокировка
                int df = t % 8 - f % 8;
                char target = sq[t];
                boolean rookTarget = target != '.' && target != p
                        && Character.isUpperCase(target) == Character.isUpperCase(p)
                        && Character.toLowerCase(target) == 'r';
                if (Math.abs(df) == 2 || rookTarget) {        // e1g1 или "король бьёт ладью" e1h1
                    boolean kingSide = df > 0;
                    int row = f / 8 * 8;
                    char rook = Character.isUpperCase(p) ? 'R' : 'r';
                    sq[f] = '.';
                    sq[row + (kingSide ? 7 : 0)] = '.';
                    sq[row + (kingSide ? 6 : 2)] = p;
                    sq[row + (kingSide ? 5 : 3)] = rook;
                    return;
                }
            }
            if ((p == 'P' || p == 'p') && f % 8 != t % 8 && sq[t] == '.') {   // взятие на проходе
                sq[f / 8 * 8 + t % 8] = '.';
            }
            sq[t] = p;
            sq[f] = '.';
            if (m.length() > 4) {                              // превращение пешки
                char np = m.charAt(4);
                sq[t] = Character.isUpperCase(p) ? Character.toUpperCase(np) : Character.toLowerCase(np);
            }
        }
    }

    // ================= крошечный разборщик JSON =================

    static class Json {
        final String s;
        int i = 0;

        Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            return new Json(s).val();
        }

        Object val() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new HashMap<>();
                ws();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    ws();
                    String k = string();
                    ws();
                    i++; // ':'
                    m.put(k, val());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(val());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        String string() {
            i++; // открывающая кавычка
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n': b.append('\n'); break;
                        case 't': b.append('\t'); break;
                        case 'r': b.append('\r'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: b.append(n);
                    }
                } else {
                    b.append(c);
                }
            }
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
