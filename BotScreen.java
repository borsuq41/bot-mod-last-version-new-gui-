package pl.botmod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class BotScreen extends Screen {
    private static final int BG = 0xF00E1217;
    private static final int PANEL_BORDER = 0xFF2B323C;
    private static final int FIELD_BG = 0xFF171C23;
    private static final int ACCENT = 0xFF3B82F6;
    private static final int LABEL = 0xFF8B95A5;

    private record Btn(int x, int y, int w, int h, String label, int color, Runnable action, BooleanSupplier enabled) {}
    private record Box(int x, int y, int w, int h) {}
    private record Lbl(int x, int y, String text) {}
    private record Piece(String time, String text, int color) {}

    private TextFieldWidget ipField, nickField, countField, delayField, passField, chatField;
    private final List<TextFieldWidget> fields = new ArrayList<>();
    private final List<Btn> buttons = new ArrayList<>();
    private final List<Box> boxes = new ArrayList<>();
    private final List<Lbl> labels = new ArrayList<>();

    private int px, py, pw, ph, pad = 10;
    private int consoleX, consoleY, consoleW, consoleH;
    private int scroll = 0;

    public BotScreen() {
        super(Text.literal("Bot Mod"));
    }

    // ------------------------------------------------------------------ uklad

    @Override
    protected void init() {
        fields.clear();
        buttons.clear();
        boxes.clear();
        labels.clear();

        if (BotConfig.address.isEmpty()) {
            ServerInfo info = MinecraftClient.getInstance().getCurrentServerEntry();
            if (info != null) BotConfig.address = info.address;
        }

        pw = Math.min(this.width - 16, 480);
        px = (this.width - pw) / 2;
        py = 8;
        ph = this.height - 16;
        int ix = px + pad;
        int iw = pw - 2 * pad;
        int gap = 4;
        int fh = 18;
        int y = py + 26;

        // --- adres serwera + lista
        int small = 20;
        int ipW = iw - 4 * (small + gap);
        labels.add(new Lbl(ix, y, "ADRES SERWERA"));
        ipField = field(ix, y + 10, ipW, fh, "np. serwer.pl lub 1.2.3.4:25565", 255, BotConfig.address);
        int bx = ix + ipW + gap;
        buttons.add(new Btn(bx, y + 10, small, fh, "<", 0xFF2A323D, this::prevServer, () -> true));
        buttons.add(new Btn(bx + small + gap, y + 10, small, fh, ">", 0xFF2A323D, this::nextServer, () -> true));
        buttons.add(new Btn(bx + 2 * (small + gap), y + 10, small, fh, "+", 0xFF1F7A45, this::saveServer, () -> true));
        buttons.add(new Btn(bx + 3 * (small + gap), y + 10, small, fh, "x", 0xFF8A2E2E, this::removeServer, () -> true));
        y += 10 + fh + 8;

        // --- nick / ilosc / odstep / haslo
        int avail = iw - 3 * gap;
        int nickW = (int) (avail * 0.34);
        int countW = (int) (avail * 0.14);
        int delayW = (int) (avail * 0.18);
        int passW = avail - nickW - countW - delayW;
        int x = ix;
        labels.add(new Lbl(x, y, "NICK"));
        nickField = field(x, y + 10, nickW, fh, "Nick", 16, BotConfig.nick);
        nickField.setTextPredicate(s -> s.matches("[A-Za-z0-9_]{0,16}"));
        x += nickW + gap;
        labels.add(new Lbl(x, y, "ILOSC"));
        countField = field(x, y + 10, countW, fh, "1", 2, String.valueOf(BotConfig.count));
        countField.setTextPredicate(s -> s.matches("\\d{0,2}"));
        x += countW + gap;
        labels.add(new Lbl(x, y, "ODSTEP (s)"));
        delayField = field(x, y + 10, delayW, fh, "4.5", 4, trimNum(BotConfig.delay));
        delayField.setTextPredicate(s -> s.matches("\\d{0,2}([.,]\\d?)?"));
        x += delayW + gap;
        labels.add(new Lbl(x, y, "HASLO (auto /register)"));
        passField = field(x, y + 10, passW, fh, "haslo", 32, BotConfig.password);
        y += 10 + fh + 8;

        // --- przyciski glowne
        int bh = 20;
        int sendW = (int) ((iw - 2 * gap) * 0.42);
        int stopW = (int) ((iw - 2 * gap) * 0.34);
        int clrW = iw - 2 * gap - sendW - stopW;
        buttons.add(new Btn(ix, y, sendW, bh, "Wyslij boty", 0xFF1F9D55, this::onStart, () -> !BotManager.isActive()));
        buttons.add(new Btn(ix + sendW + gap, y, stopW, bh, "Rozlacz wszystkie", 0xFFD9534F, BotManager::stopAll, BotManager::isActive));
        buttons.add(new Btn(ix + sendW + stopW + 2 * gap, y, clrW, bh, "Wyczysc", 0xFF2A323D, () -> { BotManager.clear(); scroll = 0; }, () -> true));
        y += bh + 8;

        // --- czat (na dole)
        int chatY = py + ph - pad - fh;
        int sendBtnW = 70;
        chatField = field(ix, chatY, iw - sendBtnW - gap, fh, "Wiadomosc lub /komenda (wysle kazdy bot)", 256, "");
        buttons.add(new Btn(ix + iw - sendBtnW, chatY, sendBtnW, fh, "Wyslij", 0xFF2A323D, this::sendChat, () -> BotManager.inGame() > 0));

        // --- konsola
        labels.add(new Lbl(ix, y, "KONSOLA"));
        consoleX = ix;
        consoleY = y + 10;
        consoleW = iw;
        consoleH = Math.max(40, chatY - 6 - consoleY);
    }

    private TextFieldWidget field(int x, int y, int w, int h, String placeholder, int max, String text) {
        boxes.add(new Box(x, y, w, h));
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x + 5, y + (h - 8) / 2, w - 10, 10, Text.literal(placeholder));
        f.setDrawsBackground(false);
        f.setMaxLength(max);
        f.setText(text);
        f.setPlaceholder(Text.literal(placeholder));
        addSelectableChild(f);
        fields.add(f);
        return f;
    }

    private static String trimNum(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // ------------------------------------------------------------------ akcje

    private void readFields() {
        BotConfig.address = ipField.getText().trim();
        BotConfig.nick = nickField.getText().trim();
        BotConfig.password = passField.getText();
        try { BotConfig.count = Math.max(1, Math.min(50, Integer.parseInt(countField.getText().trim()))); } catch (NumberFormatException e) { BotConfig.count = 1; }
        try { BotConfig.delay = Math.max(0, Double.parseDouble(delayField.getText().trim().replace(',', '.'))); } catch (NumberFormatException e) { BotConfig.delay = 4.5; }
    }

    private void onStart() {
        readFields();
        BotConfig.save();
        if (!BotConfig.nick.matches("[A-Za-z0-9_]{1,16}")) {
            BotManager.log(BotSession.RED, "Zly nick (1-16 znakow: litery, cyfry, _).");
            return;
        }
        if (BotConfig.address.isEmpty()) {
            BotManager.log(BotSession.RED, "Wpisz adres serwera.");
            return;
        }
        scroll = 0;
        BotManager.start(BotConfig.nick, BotConfig.count, BotConfig.delay, BotConfig.address, BotConfig.password);
    }

    private void sendChat() {
        String t = chatField.getText();
        if (t.isBlank()) return;
        BotManager.sendChat(t);
        chatField.setText("");
    }

    private void saveServer() {
        String a = ipField.getText().trim();
        if (a.isEmpty()) return;
        if (!BotConfig.servers.contains(a)) BotConfig.servers.add(a);
        BotConfig.save();
        BotManager.log(BotSession.GRAY, "Zapisano serwer: " + a);
    }

    private void removeServer() {
        String a = ipField.getText().trim();
        if (BotConfig.servers.remove(a)) {
            BotConfig.save();
            BotManager.log(BotSession.GRAY, "Usunieto serwer: " + a);
        }
    }

    private void cycleServer(int dir) {
        List<String> s = BotConfig.servers;
        if (s.isEmpty()) {
            BotManager.log(BotSession.YELLOW, "Brak zapisanych serwerow - wpisz adres i kliknij +.");
            return;
        }
        int i = s.indexOf(ipField.getText().trim());
        i = i < 0 ? (dir > 0 ? 0 : s.size() - 1) : Math.floorMod(i + dir, s.size());
        ipField.setText(s.get(i));
    }

    private void prevServer() { cycleServer(-1); }
    private void nextServer() { cycleServer(1); }

    // ------------------------------------------------------------------ rysowanie

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x99000000);
        ctx.fill(px, py, px + pw, py + ph, BG);
        ctx.drawBorder(px, py, pw, ph, PANEL_BORDER);

        // naglowek
        ctx.drawTextWithShadow(this.textRenderer, "BotMod", px + pad, py + 9, 0xFFFFFF);
        int titleW = this.textRenderer.getWidth("BotMod");
        String addr = ipField.getText().trim();
        if (!addr.isEmpty()) {
            String shown = this.textRenderer.trimToWidth(addr, pw / 3);
            ctx.drawTextWithShadow(this.textRenderer, "· " + shown, px + pad + titleW + 6, py + 9, LABEL | 0xFF000000);
        }
        int in = BotManager.inGame();
        int run = BotManager.running();
        int total = BotManager.planned();
        String status = run == 0 ? "Rozlaczony" : "Na serwerze " + in + "/" + Math.max(total, run);
        int dot = run == 0 ? 0xFF6B7280 : (in > 0 ? 0xFF22C55E : 0xFFEAB308);
        int sw = this.textRenderer.getWidth(status);
        int sx = px + pw - pad - sw;
        ctx.fill(sx - 12, py + 11, sx - 6, py + 17, dot);
        ctx.drawTextWithShadow(this.textRenderer, status, sx, py + 9, 0xFFE5E7EB);
        ctx.fill(px + 1, py + 22, px + pw - 1, py + 23, PANEL_BORDER);

        // etykiety
        for (Lbl l : labels) ctx.drawTextWithShadow(this.textRenderer, l.text(), l.x(), l.y(), LABEL | 0xFF000000);

        // pola
        for (int i = 0; i < boxes.size(); i++) {
            Box b = boxes.get(i);
            ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), FIELD_BG);
            ctx.drawBorder(b.x(), b.y(), b.w(), b.h(), fields.get(i).isFocused() ? ACCENT : PANEL_BORDER);
        }
        for (TextFieldWidget f : fields) f.render(ctx, mouseX, mouseY, delta);

        // przyciski
        for (Btn b : buttons) {
            boolean on = b.enabled().getAsBoolean();
            boolean hover = on && inRect(mouseX, mouseY, b.x(), b.y(), b.w(), b.h());
            int col = on ? (hover ? lighten(b.color()) : b.color()) : 0xFF1C2128;
            ctx.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), col);
            ctx.drawBorder(b.x(), b.y(), b.w(), b.h(), on ? 0x33FFFFFF : 0x22FFFFFF);
            int tx = b.x() + b.w() / 2;
            int ty = b.y() + (b.h() - 8) / 2;
            ctx.drawCenteredTextWithShadow(this.textRenderer, Text.literal(b.label()), tx, ty, on ? 0xFFFFFF : 0x6B7280);
        }

        renderConsole(ctx);
    }

    private void renderConsole(DrawContext ctx) {
        ctx.fill(consoleX, consoleY, consoleX + consoleW, consoleY + consoleH, 0xFF0A0D11);
        ctx.drawBorder(consoleX, consoleY, consoleW, consoleH, PANEL_BORDER);

        List<BotManager.Line> lines = BotManager.lines();
        if (lines.isEmpty()) {
            ctx.drawTextWithShadow(this.textRenderer, "Tu pojawia sie konsola botow.", consoleX + 6, consoleY + 6, 0xFF4B5563);
            return;
        }

        int timeW = this.textRenderer.getWidth("00:00:00") + 6;
        int maxW = consoleW - 14 - timeW;
        List<Piece> wrapped = new ArrayList<>();
        for (BotManager.Line l : lines) {
            String rest = l.text;
            boolean first = true;
            do {
                String part = this.textRenderer.trimToWidth(rest, maxW);
                if (part.isEmpty()) part = rest.substring(0, 1);
                wrapped.add(new Piece(first ? l.time : "", part, l.color));
                rest = rest.substring(part.length());
                first = false;
            } while (!rest.isEmpty());
        }

        int lineH = this.textRenderer.fontHeight + 2;
        int visible = Math.max(1, (consoleH - 8) / lineH);
        int maxScroll = Math.max(0, wrapped.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int from = Math.max(0, wrapped.size() - visible - scroll);
        int y = consoleY + 5;
        for (int i = from; i < Math.min(wrapped.size(), from + visible); i++) {
            Piece p = wrapped.get(i);
            if (!p.time().isEmpty()) {
                ctx.drawTextWithShadow(this.textRenderer, p.time(), consoleX + 6, y, 0xFF5B6573);
            }
            ctx.drawTextWithShadow(this.textRenderer, p.text(), consoleX + 6 + timeW, y, p.color() | 0xFF000000);
            y += lineH;
        }

        if (maxScroll > 0) {
            int trackH = consoleH - 4;
            int thumbH = Math.max(10, trackH * visible / wrapped.size());
            int thumbY = consoleY + 2 + (trackH - thumbH) * (maxScroll - scroll) / maxScroll;
            ctx.fill(consoleX + consoleW - 5, thumbY, consoleX + consoleW - 2, thumbY + thumbH, 0xFF3B4452);
        }
    }

    private static boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static int lighten(int argb) {
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 28);
        int b = Math.min(255, (argb & 0xFF) + 28);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ------------------------------------------------------------------ wejscie

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Btn b : buttons) {
                if (b.enabled().getAsBoolean() && inRect(mouseX, mouseY, b.x(), b.y(), b.w(), b.h())) {
                    b.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (inRect(mouseX, mouseY, consoleX, consoleY, consoleW, consoleH)) {
            scroll += (int) Math.signum(verticalAmount) * 3;
            if (scroll < 0) scroll = 0;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && chatField.isFocused()) {
            sendChat();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        readFields();
        BotConfig.save();
        super.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
