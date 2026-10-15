package pl.botmod;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Zapamietuje ustawienia i liste serwerow w config/botmod.properties. */
public final class BotConfig {
    public static String address = "";
    public static String nick = "Bot";
    public static String password = "BotHaslo123";
    public static int count = 1;
    public static double delay = 4.5;
    public static final List<String> servers = new ArrayList<>();

    private BotConfig() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("botmod.properties");
    }

    public static void load() {
        Path f = file();
        if (!Files.exists(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            address = p.getProperty("address", address);
            nick = p.getProperty("nick", nick);
            password = p.getProperty("password", password);
            try { count = Math.max(1, Math.min(50, Integer.parseInt(p.getProperty("count", "1")))); } catch (NumberFormatException ignored) {}
            try { delay = Math.max(0, Double.parseDouble(p.getProperty("delay", "4.5"))); } catch (NumberFormatException ignored) {}
            servers.clear();
            for (String s : p.getProperty("servers", "").split("\\|")) {
                if (!s.isBlank()) servers.add(s.trim());
            }
        } catch (IOException ignored) {}
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("address", address);
        p.setProperty("nick", nick);
        p.setProperty("password", password);
        p.setProperty("count", String.valueOf(count));
        p.setProperty("delay", String.valueOf(delay));
        p.setProperty("servers", String.join("|", servers));
        try (OutputStream out = Files.newOutputStream(file())) {
            p.store(out, "Bot Mod");
        } catch (IOException ignored) {}
    }
}
