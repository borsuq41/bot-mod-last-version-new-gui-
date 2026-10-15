package pl.botmod;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Trzyma boty i konsole - zostaje w pamieci po zamknieciu GUI. */
public final class BotManager {
    public static final class Line {
        public final String time;
        public final String text;
        public final int color;
        Line(String time, String text, int color) { this.time = time; this.text = text; this.color = color; }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final List<Line> LINES = new ArrayList<>();
    private static final List<BotSession> BOTS = new ArrayList<>();
    private static Thread starter;
    private static int planned = 0;

    private BotManager() {}

    public static synchronized void log(int color, String msg) {
        LINES.add(new Line(LocalTime.now().format(TIME), msg, color));
        while (LINES.size() > 500) LINES.remove(0);
    }

    public static synchronized List<Line> lines() { return new ArrayList<>(LINES); }

    public static synchronized void clear() { LINES.clear(); }

    public static synchronized boolean isActive() {
        if (starter != null && starter.isAlive()) return true;
        for (BotSession b : BOTS) if (b.isRunning()) return true;
        return false;
    }

    public static synchronized int inGame() {
        int n = 0;
        for (BotSession b : BOTS) if (b.isInGame()) n++;
        return n;
    }

    public static synchronized int running() {
        int n = 0;
        for (BotSession b : BOTS) if (b.isRunning()) n++;
        return n;
    }

    public static synchronized int planned() { return planned; }

    /** Uruchamia boty jeden po drugim (serwery blokuja zbyt szybkie wejscia z jednego IP). */
    public static synchronized void start(String nick, int count, double delaySec, String address, String password) {
        if (isActive()) {
            log(BotSession.YELLOW, "Boty juz dzialaja - najpierw je rozlacz.");
            return;
        }
        BOTS.clear();
        planned = count;
        final long delayMs = Math.max(0, (long) (delaySec * 1000));
        final boolean many = count > 1;

        starter = new Thread(() -> {
            for (int i = 0; i < count; i++) {
                String suffix = many ? String.valueOf(i + 1) : "";
                String base = nick.length() + suffix.length() > 16 ? nick.substring(0, 16 - suffix.length()) : nick;
                String name = base + suffix;
                String tag = many ? "[" + name + "] " : "";
                BotSession s = new BotSession(name, address, password, (c, m) -> log(c, tag + m));
                synchronized (BotManager.class) { BOTS.add(s); }
                s.start();
                if (i < count - 1) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException e) { return; }
                }
            }
        }, "BotMod-starter");
        starter.setDaemon(true);
        starter.start();
        log(BotSession.GRAY, "Uruchamiam " + count + (count == 1 ? " bota" : " botow") + " na " + address);
    }

    public static synchronized void stopAll() {
        if (starter != null) starter.interrupt();
        for (BotSession b : BOTS) b.stop();
    }

    public static synchronized void sendChat(String text) {
        int n = 0;
        for (BotSession b : BOTS) if (b.isInGame()) { b.chat(text); n++; }
        if (n == 0) log(BotSession.YELLOW, "Zaden bot nie jest w grze - nie ma komu wyslac.");
    }
}
