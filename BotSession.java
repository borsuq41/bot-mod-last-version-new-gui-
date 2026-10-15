package pl.botmod;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;

/**
 * Bot (klient protokolu Minecraft 1.21.4, protokol 769) dla serwerow offline-mode.
 * Nie uzywa klas Minecrafta - tylko gniazdo TCP.
 *
 * WAZNE: numery pakietow fazy PLAY nie sa wpisane na sztywno. Pierwszy pakiet po konfiguracji to zawsze
 * "Login" - jego numer sluzy jako punkt odniesienia, a reszta pakietow jest liczona wzgledem niego.
 * Dzieki temu drobne przesuniecia numeracji miedzy wersjami nie psuja bota.
 */
public final class BotSession implements Runnable {
    public interface Sink { void log(int color, String msg); }

    public static final int GREEN = 0x55FF55, RED = 0xFF5555, YELLOW = 0xFFFF55, GRAY = 0xAAAAAA,
            WHITE = 0xFFFFFF, AQUA = 0x55FFFF, GOLD = 0xFFAA00;

    static final int PROTOCOL = 769;

    // login (clientbound)
    static final int L_DISCONNECT = 0x00, L_ENCRYPTION = 0x01, L_SUCCESS = 0x02, L_COMPRESSION = 0x03, L_PLUGIN_REQ = 0x04;
    // configuration (clientbound)
    static final int C_COOKIE_REQ = 0x00, C_DISCONNECT = 0x02, C_FINISH = 0x03, C_KEEPALIVE = 0x04,
            C_PING = 0x05, C_ADD_PACK = 0x09, C_KNOWN_PACKS = 0x0E;

    // play (clientbound): przesuniecia wzgledem pakietu Login
    static final int DEFAULT_LOGIN_ID = 0x2C;
    static final int OFF_CHUNK_BATCH_FINISHED = -32, OFF_DISCONNECT = -15, OFF_KEEPALIVE = -5, OFF_PING = 11,
            OFF_PLAYER_CHAT = 15, OFF_POSITION = 22, OFF_SUBTITLE = 62, OFF_TITLE = 64,
            OFF_START_CONFIG = 68, OFF_SYSTEM_CHAT = 71;

    // serverbound play
    static final int S_CHAT_COMMAND = 0x05, S_CHAT = 0x07, S_CHUNK_BATCH_RECEIVED = 0x09, S_ACK_CONFIG = 0x0E,
            S_KEEPALIVE = 0x1A, S_PONG = 0x29, S_TELEPORT_CONFIRM = 0x00;

    enum State { LOGIN, CONFIG, PLAY }

    private final String name;
    private final String password;
    private final String host;
    private final int port;
    private final boolean explicitPort;
    private final Sink log;

    private volatile boolean running = true;
    private volatile boolean connected = false;
    private volatile State state = State.LOGIN;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private final Object writeLock = new Object();
    private int compression = -1;
    private int anchor = DEFAULT_LOGIN_ID;
    private boolean firstPlay = false;
    private long lastAuth = 0;
    private String lastTitle = "";
    private final Random random = new Random();

    public BotSession(String name, String address, String password, Sink log) {
        this.name = name;
        this.password = password == null ? "" : password;
        this.log = log;
        String h = address.trim();
        int p = 25565;
        boolean explicit = false;
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(':') == colon) {
            try {
                p = Integer.parseInt(h.substring(colon + 1).trim());
                h = h.substring(0, colon).trim();
                explicit = true;
            } catch (NumberFormatException ignored) {}
        }
        this.host = h;
        this.port = p;
        this.explicitPort = explicit;
    }

    public String name() { return name; }
    public boolean isRunning() { return running; }
    public boolean isInGame() { return connected && running && state == State.PLAY; }

    public void start() {
        Thread t = new Thread(this, "BotMod-" + name);
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    /** Wysyla wiadomosc na czat (albo komende, jesli zaczyna sie od "/"). */
    public void chat(String text) {
        if (!isInGame() || text == null || text.isBlank()) return;
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (text.startsWith("/")) {
                writeString(o, text.substring(1));
                sendPacket(S_CHAT_COMMAND, o.toByteArray());
            } else {
                DataOutputStream d = new DataOutputStream(o);
                writeString(o, text);
                d.writeLong(System.currentTimeMillis());
                d.writeLong(random.nextLong());
                d.writeBoolean(false);   // brak podpisu
                writeVarInt(o, 0);       // message count
                o.write(new byte[3]);    // acknowledged (20 bitow)
                sendPacket(S_CHAT, o.toByteArray());
            }
        } catch (IOException e) {
            log.log(RED, "Nie udalo sie wyslac wiadomosci: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        try {
            InetSocketAddress target = explicitPort ? new InetSocketAddress(host, port) : resolveSrv(host, port);
            log.log(GRAY, "Lacze z " + target.getHostString() + ":" + target.getPort() + " ...");
            socket = new Socket();
            socket.connect(target, 10_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(45_000);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new BufferedOutputStream(socket.getOutputStream());
            connected = true;

            ByteArrayOutputStream hs = new ByteArrayOutputStream();
            writeVarInt(hs, PROTOCOL);
            writeString(hs, host);
            new DataOutputStream(hs).writeShort(port);
            writeVarInt(hs, 2);
            sendPacket(0x00, hs.toByteArray());

            ByteArrayOutputStream ls = new ByteArrayOutputStream();
            writeString(ls, name);
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            DataOutputStream lsd = new DataOutputStream(ls);
            lsd.writeLong(uuid.getMostSignificantBits());
            lsd.writeLong(uuid.getLeastSignificantBits());
            sendPacket(0x00, ls.toByteArray());

            while (running) {
                Pkt p = readPacket();
                try {
                    handle(p);
                } catch (EOFException e) {
                    // zly/krotszy pakiet - to NIE jest zamkniecie polaczenia, ignorujemy
                    log.log(YELLOW, "Pominieto pakiet 0x" + Integer.toHexString(p.id) + " (nieoczekiwany format)");
                }
            }
        } catch (EOFException e) {
            if (running) log.log(RED, "Serwer zamknal polaczenie.");
        } catch (java.net.SocketTimeoutException e) {
            if (running) log.log(RED, "Brak odpowiedzi serwera (timeout).");
        } catch (Exception e) {
            if (running) log.log(RED, "Blad: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running = false;
            connected = false;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            log.log(GRAY, "Bot rozlaczony.");
        }
    }

    // ------------------------------------------------------------------ obsluga pakietow

    private void handle(Pkt p) throws IOException {
        switch (state) {
            case LOGIN -> handleLogin(p);
            case CONFIG -> handleConfig(p);
            case PLAY -> handlePlay(p);
        }
    }

    private void handleLogin(Pkt p) throws IOException {
        DataInputStream b = p.stream();
        switch (p.id) {
            case L_DISCONNECT -> {
                log.log(RED, "Wyrzucony: " + jsonText(readString(b)));
                running = false;
            }
            case L_ENCRYPTION -> {
                log.log(RED, "Serwer jest w trybie premium (online-mode) - bot bez konta nie wejdzie.");
                running = false;
            }
            case L_COMPRESSION -> compression = readVarInt(b);
            case L_PLUGIN_REQ -> {
                int id = readVarInt(b);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                writeVarInt(o, id);
                o.write(0);
                sendPacket(0x02, o.toByteArray());
            }
            case L_SUCCESS -> {
                log.log(GRAY, "Zalogowano, trwa konfiguracja...");
                sendPacket(0x03, new byte[0]);
                state = State.CONFIG;
                sendBrandAndInfo();
            }
            default -> {}
        }
    }

    private void handleConfig(Pkt p) throws IOException {
        DataInputStream b = p.stream();
        switch (p.id) {
            case C_COOKIE_REQ -> {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                writeString(o, readString(b));
                o.write(0);
                sendPacket(0x01, o.toByteArray());
            }
            case C_DISCONNECT -> {
                log.log(RED, "Wyrzucony: " + nbtText(b));
                running = false;
            }
            case C_KEEPALIVE -> sendPacket(0x04, p.body);
            case C_PING -> sendPacket(0x05, p.body);
            case C_KNOWN_PACKS -> {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                writeVarInt(o, 0);
                sendPacket(0x07, o.toByteArray());
            }
            case C_ADD_PACK -> {
                byte[] uuid = new byte[16];
                b.readFully(uuid);
                log.log(GRAY, "Serwer wyslal paczke zasobow - bot ja akceptuje.");
                for (int status : new int[]{3, 0}) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    o.write(uuid);
                    writeVarInt(o, status);
                    sendPacket(0x06, o.toByteArray());
                }
            }
            case C_FINISH -> {
                sendPacket(0x03, new byte[0]);
                log.log(GRAY, "Konfiguracja zakonczona, wchodze do swiata...");
                state = State.PLAY;
                firstPlay = true;
            }
            default -> {}
        }
    }

    private void handlePlay(Pkt p) throws IOException {
        byte[] b = p.body;

        if (firstPlay) {
            firstPlay = false;
            if (looksLikeLogin(b)) {
                anchor = p.id;
                log.log(GREEN, "Bot " + name + " wszedl na serwer!");
                return;
            }
            log.log(YELLOW, "Uwaga: pierwszy pakiet gry (0x" + Integer.toHexString(p.id)
                    + ") nie wyglada na Login - uzywam domyslnych numerow.");
        }

        switch (p.id - anchor) {
            case OFF_KEEPALIVE -> {
                if (b.length == 8) sendPacket(S_KEEPALIVE, b);
            }
            case OFF_PING -> {
                if (b.length == 4) sendPacket(S_PONG, b);
            }
            case OFF_CHUNK_BATCH_FINISHED -> {
                if (b.length >= 1 && b.length <= 3) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    new DataOutputStream(o).writeFloat(2.0f);
                    sendPacket(S_CHUNK_BATCH_RECEIVED, o.toByteArray());
                }
            }
            case OFF_DISCONNECT -> {
                String t = nbtStrict(b, 0);
                if (t != null) {
                    log.log(RED, "Wyrzucony: " + t);
                    running = false;
                }
            }
            case OFF_POSITION -> {
                DataInputStream s = p.stream();
                int tp = readVarInt(s);
                double x = s.readDouble(), y = s.readDouble(), z = s.readDouble();
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                writeVarInt(o, tp);
                sendPacket(S_TELEPORT_CONFIRM, o.toByteArray());
                log.log(GRAY, "Pozycja: " + (long) Math.floor(x) + " " + (long) Math.floor(y) + " " + (long) Math.floor(z));
            }
            case OFF_SYSTEM_CHAT -> {
                String t = nbtStrict(b, 1);
                if (t != null && b[b.length - 1] == 0 && !t.isBlank()) {
                    log.log(WHITE, t);
                    autoAuth(t);
                }
            }
            case OFF_PLAYER_CHAT -> {
                String t = parsePlayerChat(b);
                if (t != null) log.log(WHITE, t);
            }
            case OFF_TITLE -> {
                String t = nbtStrict(b, 0);
                if (t != null && !t.isBlank() && !t.equals(lastTitle)) {
                    lastTitle = t;
                    log.log(YELLOW, "[Tytul] " + t);
                }
            }
            case OFF_SUBTITLE -> {
                String t = nbtStrict(b, 0);
                if (t != null && !t.isBlank()) log.log(GOLD, "[Podtytul] " + t);
            }
            case OFF_START_CONFIG -> {
                if (b.length == 0) {
                    log.log(GRAY, "Serwer przelacza bota (rekonfiguracja)...");
                    sendPacket(S_ACK_CONFIG, new byte[0]);
                    state = State.CONFIG;
                }
            }
            default -> {}
        }
    }

    /** Wykrywa pakiet Login po jego ksztalcie: int entityId, bool hardcore, VarInt liczba wymiarow, string... */
    private static boolean looksLikeLogin(byte[] b) {
        if (b.length < 30) return false;
        int hardcore = b[4];
        int dims = b[5] & 0xFF;
        int firstLen = b[6] & 0xFF;
        return (hardcore == 0 || hardcore == 1) && dims >= 1 && dims <= 16 && firstLen >= 3 && firstLen <= 100;
    }

    /** Automatyczna rejestracja / logowanie na serwerach z pluginem autoryzacji. */
    private void autoAuth(String text) {
        if (password.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAuth < 3000) return;
        String low = text.toLowerCase(Locale.ROOT);
        if (low.contains("/register")) {
            lastAuth = now;
            String extra = "";
            Matcher m = Pattern.compile("/register\\s+\\S+\\s+\\S+\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(text);
            if (m.find() && !m.group(1).startsWith("<") && !m.group(1).startsWith("[")) extra = " " + m.group(1);
            String cmd = "/register " + password + " " + password + extra;
            log.log(AQUA, "> " + cmd);
            chat(cmd);
        } else if (low.contains("/login")) {
            lastAuth = now;
            log.log(AQUA, "> /login ***");
            chat("/login " + password);
        }
    }

    private String parsePlayerChat(byte[] b) {
        try {
            DataInputStream s = new DataInputStream(new ByteArrayInputStream(b));
            s.skipNBytes(16);
            readVarInt(s);
            if (s.readBoolean()) s.skipNBytes(256);
            String msg = readString(s);
            s.readLong();
            s.readLong();
            int prev = readVarInt(s);
            for (int i = 0; i < prev; i++) {
                if (readVarInt(s) == 0) s.skipNBytes(256);
            }
            if (s.readBoolean()) nbtRead(s);
            int filter = readVarInt(s);
            if (filter == 2) s.skipNBytes(readVarInt(s) * 8L);
            if (readVarInt(s) == 0) return null;
            String sender = nbtRead(s);
            if (s.readBoolean()) nbtRead(s);
            if (s.available() != 0) return null;
            return "<" + sender + "> " + msg;
        } catch (Exception e) {
            return null;
        }
    }

    private void sendBrandAndInfo() throws IOException {
        ByteArrayOutputStream brand = new ByteArrayOutputStream();
        writeString(brand, "minecraft:brand");
        writeString(brand, "vanilla");
        sendPacket(0x02, brand.toByteArray());

        ByteArrayOutputStream ci = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ci);
        writeString(ci, "pl_pl");
        d.writeByte(8);
        writeVarInt(ci, 0);
        d.writeBoolean(true);
        d.writeByte(0x7F);
        writeVarInt(ci, 1);
        d.writeBoolean(false);
        d.writeBoolean(true);
        writeVarInt(ci, 0);
        sendPacket(0x00, ci.toByteArray());
    }

    // ------------------------------------------------------------------ I/O

    private static final class Pkt {
        final int id;
        final byte[] body;
        Pkt(int id, byte[] body) { this.id = id; this.body = body; }
        DataInputStream stream() { return new DataInputStream(new ByteArrayInputStream(body)); }
    }

    private Pkt readPacket() throws IOException {
        int len = readVarInt(in);
        byte[] data = new byte[len];
        in.readFully(data);
        if (compression >= 0) {
            ByteArrayInputStream bis = new ByteArrayInputStream(data);
            int dataLen = readVarInt(bis);
            byte[] rest = bis.readAllBytes();
            if (dataLen != 0) {
                Inflater inf = new Inflater();
                inf.setInput(rest);
                byte[] buf = new byte[dataLen];
                int off = 0;
                try {
                    while (off < dataLen) {
                        int n = inf.inflate(buf, off, dataLen - off);
                        if (n == 0 && (inf.finished() || inf.needsInput())) break;
                        off += n;
                    }
                } catch (java.util.zip.DataFormatException e) {
                    throw new IOException("Blad dekompresji", e);
                } finally {
                    inf.end();
                }
                data = buf;
            } else {
                data = rest;
            }
        }
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        int id = readVarInt(bis);
        return new Pkt(id, bis.readAllBytes());
    }

    private void sendPacket(int id, byte[] body) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        writeVarInt(p, id);
        p.write(body);
        byte[] raw = p.toByteArray();

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        if (compression < 0) {
            writeVarInt(frame, raw.length);
            frame.write(raw);
        } else if (raw.length < compression) {
            writeVarInt(frame, raw.length + 1);
            writeVarInt(frame, 0);
            frame.write(raw);
        } else {
            Deflater def = new Deflater();
            def.setInput(raw);
            def.finish();
            ByteArrayOutputStream comp = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!def.finished()) {
                int n = def.deflate(buf);
                comp.write(buf, 0, n);
            }
            def.end();
            ByteArrayOutputStream inner = new ByteArrayOutputStream();
            writeVarInt(inner, raw.length);
            inner.write(comp.toByteArray());
            writeVarInt(frame, inner.size());
            frame.write(inner.toByteArray());
        }
        synchronized (writeLock) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    // ------------------------------------------------------------------ pomocnicze

    static int readVarInt(InputStream s) throws IOException {
        int value = 0, shift = 0, b;
        do {
            b = s.read();
            if (b < 0) throw new EOFException();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt za duzy");
        } while ((b & 0x80) != 0);
        return value;
    }

    static void writeVarInt(OutputStream s, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            s.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        s.write(value);
    }

    static void writeString(OutputStream s, String str) throws IOException {
        byte[] b = str.getBytes(StandardCharsets.UTF_8);
        writeVarInt(s, b.length);
        s.write(b);
    }

    static String readString(DataInputStream s) throws IOException {
        int len = readVarInt(s);
        if (len < 0 || len > 1 << 20) throw new IOException("Zly rozmiar napisu");
        byte[] b = new byte[len];
        s.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static String jsonText(String json) {
        Matcher m = Pattern.compile("\"(?:text|translate)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        List<String> parts = new ArrayList<>();
        while (m.find()) parts.add(m.group(1).replace("\\n", " ").replace("\\\"", "\""));
        return parts.isEmpty() ? json : String.join(" ", parts);
    }

    /** Tekst (komponent NBT jak w pakietach 1.20.3+); rzuca wyjatek przy bledzie. */
    static String nbtRead(DataInputStream s) throws IOException {
        int type = s.readByte();
        List<String> out = new ArrayList<>();
        readNbt(s, type, null, out, 0);
        return String.join(" ", out).trim();
    }

    private static String nbtText(DataInputStream s) {
        try {
            return nbtRead(s);
        } catch (Exception e) {
            return "(nie udalo sie odczytac tekstu)";
        }
    }

    /** Parsuje caly pakiet jako [NBT tekst][trailing bajtow]; zwraca null jesli pakiet tak nie wyglada. */
    static String nbtStrict(byte[] body, int trailing) {
        try {
            if (body.length == 0) return null;
            int first = body[0];
            if (first != 8 && first != 10) return null;
            DataInputStream s = new DataInputStream(new ByteArrayInputStream(body));
            String t = nbtRead(s);
            if (s.available() != trailing) return null;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private static void readNbt(DataInputStream s, int type, String name, List<String> out, int depth) throws IOException {
        if (depth > 32) throw new IOException("NBT zbyt gleboki");
        switch (type) {
            case 0 -> {}
            case 1 -> s.readByte();
            case 2 -> s.readShort();
            case 3 -> s.readInt();
            case 4 -> s.readLong();
            case 5 -> s.readFloat();
            case 6 -> s.readDouble();
            case 7 -> s.skipNBytes(s.readInt());
            case 8 -> {
                String v = s.readUTF();
                if (name == null || name.equals("text") || name.equals("translate")) out.add(v);
            }
            case 9 -> {
                int t = s.readByte();
                int n = s.readInt();
                if (n < 0 || n > 100_000) throw new IOException("zla lista NBT");
                for (int i = 0; i < n; i++) readNbt(s, t, null, out, depth + 1);
            }
            case 10 -> {
                while (true) {
                    int t = s.readByte();
                    if (t == 0) break;
                    String k = s.readUTF();
                    readNbt(s, t, k, out, depth + 1);
                }
            }
            case 11 -> s.skipNBytes(s.readInt() * 4L);
            case 12 -> s.skipNBytes(s.readInt() * 8L);
            default -> throw new IOException("Nieznany typ NBT " + type);
        }
    }

    /** Rozwiazuje rekord SRV (_minecraft._tcp) - tak jak robi to gra. */
    private static InetSocketAddress resolveSrv(String host, int defaultPort) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            InitialDirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
            Attribute a = attrs.get("srv");
            if (a != null && a.size() > 0) {
                String[] parts = a.get(0).toString().split(" ");
                int p = Integer.parseInt(parts[2]);
                String t = parts[3];
                if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
                return new InetSocketAddress(t, p);
            }
        } catch (Exception ignored) {}
        return new InetSocketAddress(host, defaultPort);
    }
}
