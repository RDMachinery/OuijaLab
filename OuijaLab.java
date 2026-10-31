/*
 * OuijaLab.java — a talking board that spells messages with nobody pushing it.
 *
 * A Halloween demonstration of the IDEOMOTOR EFFECT (William Carpenter, 1852):
 * tiny, unconscious muscle movements driven by expectation.
 *
 * Several simulated sitters rest their fingers on a planchette. Each sitter's
 * hand does two things:
 *   1. TREMOR  — random physiological jitter (an Ornstein–Uhlenbeck process).
 *   2. PULL    — a faint unconscious drift toward whatever letter that sitter
 *                *expects* to come next.
 * Nobody decides to push. But each sitter privately guesses what the spirit is
 * "trying to say", and as letters appear, the guesses that no longer fit are
 * ruled out. The sitters converge on one reading and the board seems to know
 * what it is doing. Tick "Reveal the hands" to see who was really steering.
 *
 * Run:      java OuijaLab.java
 * Headless: java OuijaLab.java --headless   (runs a few séances, prints results)
 *
 * Concept: Mario Gianota.  Implementation: Claude (Anthropic).  Halloween 2026.
 * MIT License.
 */

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;

public class OuijaLab {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--headless")) {
            Headless.run();
            return;
        }
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeLater(() -> new Window().setVisible(true));
    }

    // =====================================================================
    //  The board: where every letter, number and word sits
    // =====================================================================

    enum Kind { LETTER, YES, NO, GOODBYE, PAUSE }

    static final class Target {
        final Kind kind; final char ch; final String label;
        final double x, y, r, angle;
        Target(Kind kind, char ch, String label, double x, double y, double r, double angle) {
            this.kind = kind; this.ch = ch; this.label = label;
            this.x = x; this.y = y; this.r = r; this.angle = angle;
        }
    }

    static final class Board {
        static final double W = 900, H = 600;
        final List<Target> all = new ArrayList<>();
        final Map<Character, Target> byChar = new HashMap<>();
        final Target yes, no, goodbye, pause;

        Board() {
            double cx = 450, cy = 350;
            String row1 = "ABCDEFGHIJKLM", row2 = "NOPQRSTUVWXYZ";
            for (int i = 0; i < 13; i++) {
                double th = Math.toRadians(200 + 140.0 * i / 12);
                addChar(row1.charAt(i), cx + 300 * Math.cos(th), cy + 300 * Math.sin(th), 24, th);
            }
            for (int i = 0; i < 13; i++) {
                double th = Math.toRadians(205 + 130.0 * i / 12);
                addChar(row2.charAt(i), cx + 220 * Math.cos(th), cy + 220 * Math.sin(th), 19, th);
            }
            String digits = "1234567890";
            for (int i = 0; i < 10; i++)
                addChar(digits.charAt(i), 450 + (i - 4.5) * 52, 435, 21, -Math.PI / 2);
            yes = add(new Target(Kind.YES, '\0', "YES", 150, 100, 42, 0));
            no = add(new Target(Kind.NO, '\0', "NO", 750, 100, 42, 0));
            goodbye = add(new Target(Kind.GOODBYE, '\0', "GOODBYE", 450, 532, 44, 0));
            pause = add(new Target(Kind.PAUSE, '\0', "", 450, 318, 46, 0));
        }

        private void addChar(char c, double x, double y, double r, double th) {
            Target t = add(new Target(Kind.LETTER, c, String.valueOf(c), x, y, r, th));
            byChar.put(c, t);
        }
        private Target add(Target t) { all.add(t); return t; }

        Target nearestWithin(double x, double y) {
            Target best = null; double bestD = Double.MAX_VALUE;
            for (Target t : all) {
                double d = Math.hypot(t.x - x, t.y - y);
                if (d < t.r && d < bestD) { best = t; bestD = d; }
            }
            return best;
        }
    }

    // =====================================================================
    //  What the sitters half-expect to hear from "the other side"
    // =====================================================================

    static final class Phrase {
        final String text; final Set<String> tags;
        Phrase(String text, String... tags) { this.text = text; this.tags = new HashSet<>(Arrays.asList(tags)); }
        boolean relevant(Set<String> q) { for (String t : q) if (tags.contains(t)) return true; return false; }
    }

    static final List<Phrase> PHRASES = new ArrayList<>();
    static final List<String> VOCAB = new ArrayList<>();
    static {
        String[][] table = {
            {"name", "MY NAME IS MARY", "MARY", "ELIZA", "I AM THOMAS", "THOMAS", "AGNES",
                     "MY NAME IS AGNES", "EDWARD", "NO NAME", "IT IS ME", "I FORGET"},
            {"where", "IN THE CELLAR", "BEHIND YOU", "HERE", "UNDER THE STAIRS", "IN THE WALLS",
                      "THE ATTIC", "BELOW", "IN THE DARK", "NEXT TO YOU"},
            {"when", "1888", "1912", "1666", "1944", "LONG AGO", "SOON", "TONIGHT", "MIDNIGHT"},
            {"death", "FIRE", "THE FEVER", "I DROWNED", "THE RIVER", "POISON", "I FELL", "MURDER", "COLD"},
            {"want", "HELP ME", "LET ME OUT", "LEAVE", "PEACE", "YOU", "FIND ME", "REMEMBER ME", "GO HOME"},
            {"age", "7", "12", "33", "81", "OLD", "VERY OLD"},
            {"any", "HELLO", "I AM HERE", "BEWARE", "LEAVE NOW", "HELP ME", "IT IS COLD", "WE ARE MANY",
                    "BEHIND YOU", "DO NOT LOOK", "I SEE YOU", "GET OUT", "STAY", "THE CELLAR",
                    "HAPPY HALLOWEEN", "MARY", "REMEMBER", "RUN"},
        };
        for (String[] row : table)
            for (int i = 1; i < row.length; i++) PHRASES.add(new Phrase(row[i], row[0]));
        Set<String> v = new TreeSet<>();
        for (Phrase p : PHRASES) v.addAll(Arrays.asList(p.text.split(" ")));
        VOCAB.addAll(v);
    }

    static final Set<String> YESNO_STARTERS = new HashSet<>(Arrays.asList(
        "IS", "ARE", "AM", "DO", "DOES", "DID", "WILL", "WOULD", "CAN", "COULD", "SHOULD",
        "HAVE", "HAS", "WAS", "WERE", "SHALL", "MAY"));

    // =====================================================================
    //  A sitter: a pair of hands and a private expectation
    // =====================================================================

    static final Color[] SITTER_COLOURS = {
        new Color(235, 95, 85), new Color(95, 170, 245), new Color(125, 215, 125),
        new Color(245, 205, 85), new Color(205, 125, 235), new Color(85, 225, 215)
    };

    static final class Sitter {
        final int id; final Color colour;
        double priorYes = 0.5;
        double[] pref = new double[PHRASES.size()];
        Target target;
        String expectation = "resting";
        double nx, ny;          // tremor force
        double pullX, pullY;    // unconscious pull force
        double share;           // smoothed fraction of this hand's force that is "pull"

        Sitter(int id) { this.id = id; this.colour = SITTER_COLOURS[id % SITTER_COLOURS.length]; }

        void reroll(Random rng) {
            priorYes = 0.15 + 0.7 * rng.nextDouble();
            for (int i = 0; i < pref.length; i++) pref[i] = Math.exp(1.2 * rng.nextGaussian());
        }
    }

    // =====================================================================
    //  The simulation
    // =====================================================================

    enum Mode { IDLE, OPEN, YESNO, ENDED }

    static final class Sim {
        static final double PULL_MAX = 320;   // px/s^2 per sitter at bias = 1
        static final double TREMOR_MAX = 240; // px/s^2 std dev per sitter at tremor = 1
        static final double THETA = 2.5;      // tremor mean-reversion rate (1/s)
        static final double DAMP = 5.0;       // felt-on-wood drag (1/s)
        static final double STATIC = 90;      // static friction threshold
        static final double DWELL = 0.75;     // seconds lingering to register a letter
        static final double SLOW = 70;        // must be moving slower than this to count as lingering
        static final double FOLLOW = 250;     // how strongly sitters follow the planchette's lead (px)
        static final double DRIFT = 0.6;
        static final double HOLD = 30;         // distance (px) within which a hand eases off its pull
        static final double CONFORM = 4.0;     // how much each sitter is swayed by where the others are aiming      // rate (1/s) at which each sitter re-reads the situation

        final Board board = new Board();
        final Random rng;
        final List<Sitter> sitters = new ArrayList<>();
        double bias = 0.35, tremor = 0.35;

        double px = 450, py = 318, vx, vy;
        Mode mode = Mode.IDLE;
        final StringBuilder msg = new StringBuilder();
        String question = "";
        Set<String> qtags = new HashSet<>();
        Target dwellT, lastCommit;
        double dwell, cooldown, time, sessionTime;
        final Map<Target, Double> glow = new HashMap<>();
        final List<String> transcript = new ArrayList<>();

        Sim(long seed, int n) { rng = new Random(seed); setSitterCount(n); }

        void setSitterCount(int n) {
            while (sitters.size() < n) {
                Sitter s = new Sitter(sitters.size());
                s.reroll(rng);
                sitters.add(s);
                sample(s);
            }
            while (sitters.size() > n) sitters.remove(sitters.size() - 1);
        }

        void ask(String q) {
            if (mode == Mode.OPEN || mode == Mode.YESNO) abandon();
            String Q = q.toUpperCase().replaceAll("[^A-Z0-9 ]", " ").trim().replaceAll("\\s+", " ");
            question = q.trim().isEmpty() ? "(an open séance)" : q.trim();
            msg.setLength(0);
            lastCommit = null; dwell = 0; cooldown = 0.6; sessionTime = 0;
            qtags = tagsFor(Q);
            String first = Q.isEmpty() ? "" : Q.split(" ")[0];
            mode = YESNO_STARTERS.contains(first) ? Mode.YESNO : Mode.OPEN;
            for (Sitter s : sitters) { s.reroll(rng); sample(s); }
        }

        void abandon() {
            if (mode == Mode.OPEN || mode == Mode.YESNO) {
                transcript.add("Q: " + question + "\n   " + display() + "   (the circle was broken)");
            }
            mode = Mode.IDLE;
            msg.setLength(0);
            for (Sitter s : sitters) sample(s);
        }

        static Set<String> tagsFor(String Q) {
            Set<String> w = new HashSet<>(Arrays.asList(Q.split(" ")));
            Set<String> t = new HashSet<>();
            if (w.contains("NAME") || w.contains("WHO")) t.add("name");
            if (w.contains("WHERE")) t.add("where");
            if (w.contains("WHEN") || w.contains("YEAR")) t.add("when");
            if (w.contains("DIE") || w.contains("DIED") || w.contains("DEATH") || w.contains("KILLED")) t.add("death");
            if (w.contains("WANT") || w.contains("NEED")) t.add("want");
            if (w.contains("OLD") || w.contains("AGE")) t.add("age");
            if (t.isEmpty()) t.add("any");
            return t;
        }

        String display() { return msg.toString().trim(); }

        // --- what does this sitter expect next? -----------------------------
        void sample(Sitter s) {
            Board b = board;
            String m = msg.toString();
            switch (mode) {
                case IDLE:
                case ENDED:
                    s.target = b.pause; s.expectation = "resting"; return;
                case YESNO:
                    if (hasWord(m, "YES") || hasWord(m, "NO")) {
                        s.target = b.goodbye; s.expectation = "feels the spirit has answered";
                    } else if (rng.nextDouble() < s.priorYes) {
                        s.target = b.yes; s.expectation = "secretly hopes for YES";
                    } else {
                        s.target = b.no; s.expectation = "secretly fears NO";
                    }
                    return;
                case OPEN:
                    break;
            }
            // Phrase-level expectation
            double[] w = new double[PHRASES.size()];
            double total = 0;
            for (int i = 0; i < w.length; i++) {
                Phrase p = PHRASES.get(i);
                int e = typos(p.text, m);
                if (e >= 0 && p.relevant(qtags)) { w[i] = s.pref[i] * TYPO[e] * nearness(p.text, m); total += w[i]; }
            }
            if (total == 0) {
                for (int i = 0; i < w.length; i++) {
                    int e = typos(PHRASES.get(i).text, m);
                    if (e >= 0) { w[i] = 0.3 * s.pref[i] * TYPO[e] * nearness(PHRASES.get(i).text, m); total += w[i]; }
                }
            }
            if (total > 0) {
                double r = rng.nextDouble() * total;
                int pick = 0;
                for (int i = 0; i < w.length; i++) { r -= w[i]; if (r <= 0 && w[i] > 0) { pick = i; break; } pick = i; }
                while (w[pick] == 0) pick--;
                String P = PHRASES.get(pick).text;
                if (P.length() == m.length()) {
                    s.target = b.goodbye; s.expectation = "is sure it said “" + P + "”";
                } else {
                    char c = P.charAt(m.length());
                    s.target = c == ' ' ? b.pause : b.byChar.get(c);
                    s.expectation = "expects “" + P + "”";
                }
                return;
            }
            // Nothing fits: the sitters improvise, word by word
            String trimmed = m.trim();
            int words = trimmed.isEmpty() ? 0 : trimmed.split(" ").length;
            String cur = m.substring(m.lastIndexOf(' ') + 1);
            if (m.length() > 28 || words >= 5) {
                s.target = b.goodbye; s.expectation = "wants this to be over";
                return;
            }
            List<String> longer = new ArrayList<>();
            for (String v : VOCAB) if (v.startsWith(cur) && v.length() > cur.length()) longer.add(v);
            boolean complete = !cur.isEmpty() && VOCAB.contains(cur);
            if (complete && (longer.isEmpty() || rng.nextDouble() < 0.6)) {
                s.target = b.pause; s.expectation = "reads it as “" + cur + "”";
            } else if (!longer.isEmpty()) {
                String v = longer.get(rng.nextInt(longer.size()));
                s.target = b.byChar.get(v.charAt(cur.length()));
                s.expectation = "thinks it is spelling “" + v + "”";
            } else if (cur.length() >= 2) {
                s.target = b.pause; s.expectation = "is baffled by “" + cur + "”";
            } else {
                char c = (char) ('A' + rng.nextInt(26));
                s.target = b.byChar.get(c); s.expectation = "has no idea";
            }
        }

        static final double[] TYPO = {1.0, 0.25, 0.05};

        /** How many letters of the message so far would have to be "typos" for it to be the start
         *  of this phrase? -1 if it can't be (spaces must line up). Sitters forgive a slip or two,
         *  just as real sitters read "MARZ" as MARY. */
        static int typos(String phrase, String m) {
            if (m.length() > phrase.length()) return -1;
            int e = 0;
            for (int i = 0; i < m.length(); i++) {
                char a = phrase.charAt(i), b = m.charAt(i);
                if (a == b) continue;
                if (a == ' ' || b == ' ') return -1;
                e++;
            }
            int allowed = m.length() < 3 ? 0 : (m.length() < 8 ? 1 : 2);
            return e <= allowed ? e : -1;
        }

        /** Sitters "read" where the planchette seems to be heading: a next letter it is already
         *  drifting toward feels more likely. This feedback is what builds consensus. */
        double nearness(String phrase, String m) {
            Target t;
            if (phrase.length() == m.length()) t = board.goodbye;
            else { char c = phrase.charAt(m.length()); t = c == ' ' ? board.pause : board.byChar.get(c); }
            double d = Math.hypot(t.x - px, t.y - py);
            double heading = 0;
            double sp = Math.hypot(vx, vy);
            if (sp > 20 && d > 1) heading = ((t.x - px) * vx + (t.y - py) * vy) / (d * sp);
            int others = 0;
            for (Sitter o : sitters) if (o.target == t) others++;
            return Math.exp(-d / FOLLOW) * Math.exp(1.2 * heading) * (1 + CONFORM * others);
        }

        static boolean hasWord(String m, String w) {
            for (String x : m.trim().split(" ")) if (x.equals(w)) return true;
            return false;
        }

        boolean active() { return mode == Mode.OPEN || mode == Mode.YESNO; }

        boolean canCommit(Target t) {
            if (!active()) return false;
            int n = msg.length();
            switch (t.kind) {
                case PAUSE:   return n > 0 && msg.charAt(n - 1) != ' ';
                case GOODBYE: return !display().isEmpty();
                default:      return true;
            }
        }

        void commit(Target t) {
            switch (t.kind) {
                case LETTER: msg.append(t.ch); break;
                case PAUSE:  msg.append(' '); break;
                case YES: case NO:
                    if (msg.length() > 0 && msg.charAt(msg.length() - 1) != ' ') msg.append(' ');
                    msg.append(t.label).append(' ');
                    break;
                case GOODBYE:
                    transcript.add("Q: " + question + "\n   " + display() + "  … GOODBYE");
                    mode = Mode.ENDED;
                    break;
            }
            glow.put(t, 1.0);
            lastCommit = t; dwell = 0; cooldown = 0.35;
            for (Sitter s : sitters) sample(s);
        }

        // --- physics ---------------------------------------------------------
        void step(double dt) {
            time += dt; cooldown -= dt;
            if (active()) sessionTime += dt;
            double fx = 0, fy = 0;
            double sig = tremor * TREMOR_MAX;
            double k = Math.sqrt(2 * THETA * dt);
            for (Sitter s : sitters) {
                s.nx += -THETA * s.nx * dt + sig * k * rng.nextGaussian();
                s.ny += -THETA * s.ny * dt + sig * k * rng.nextGaussian();
                double dx = s.target.x - px, dy = s.target.y - py, d = Math.hypot(dx, dy);
                double mag = bias * PULL_MAX * Math.min(1, d / HOLD);
                if (!active()) mag *= 0.5;
                s.pullX = d > 1e-6 ? mag * dx / d : 0;
                s.pullY = d > 1e-6 ? mag * dy / d : 0;
                fx += s.pullX + s.nx;
                fy += s.pullY + s.ny;
                double pm = Math.hypot(s.pullX, s.pullY), nm = Math.hypot(s.nx, s.ny);
                s.share += (pm / (pm + nm + 1e-9) - s.share) * Math.min(1, dt * 0.7);
            }
            double speed = Math.hypot(vx, vy);
            if (speed < 4 && Math.hypot(fx, fy) < STATIC) {
                vx = vy = 0;     // stuck: not enough force to overcome static friction
            } else {
                vx += (fx - DAMP * vx) * dt;
                vy += (fy - DAMP * vy) * dt;
            }
            px += vx * dt; py += vy * dt;
            if (px < 45) { px = 45; vx = -0.3 * vx; }
            if (px > Board.W - 45) { px = Board.W - 45; vx = -0.3 * vx; }
            if (py < 45) { py = 45; vy = -0.3 * vy; }
            if (py > Board.H - 45) { py = Board.H - 45; vy = -0.3 * vy; }

            for (Iterator<Map.Entry<Target, Double>> it = glow.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Target, Double> e = it.next();
                double v = e.getValue() - dt * 0.7;
                if (v <= 0) it.remove(); else e.setValue(v);
            }

            Target t = board.nearestWithin(px, py);
            if (t != dwellT) { dwellT = t; dwell = 0; }
            else if (t != null && Math.hypot(vx, vy) < SLOW) dwell += dt;
            else if (t != null) dwell = Math.max(0, dwell - dt);
            if (active()) for (Sitter s : sitters) if (rng.nextDouble() < DRIFT * dt) sample(s);
            if (t != null && cooldown <= 0 && canCommit(t)) {
                double need = (t == lastCommit) ? DWELL * 2.2 : DWELL;
                if (dwell >= need) commit(t);
            }
        }

        double averageShare() {
            if (sitters.isEmpty()) return 0;
            double s = 0; for (Sitter x : sitters) s += x.share;
            return s / sitters.size();
        }
    }

    // =====================================================================
    //  Headless mode — quick sanity check without a window
    // =====================================================================

    static final class Headless {
        static void run() {
            String[] qs = {"Is anyone there?", "What is your name?", "Where are you?",
                           "How did you die?", "What do you want?", "", "When did you die?", "How old are you?"};
            double[][] settings = {{0.35, 0.35}, {0.15, 0.35}, {0.0, 0.35}};
            for (double[] st : settings) {
                System.out.printf("%n=== bias %.2f, tremor %.2f, 4 sitters ===%n", st[0], st[1]);
                Sim sim = new Sim(1031, 4);
                sim.bias = st[0]; sim.tremor = st[1];
                for (String q : qs) {
                    sim.ask(q);
                    double t = 0;
                    while (sim.active() && t < 120) { sim.step(1 / 60.0); t += 1 / 60.0; }
                    System.out.printf("  %-22s -> %-26s (%.0fs%s, pull share %.0f%%)%n",
                        q.isEmpty() ? "(open)" : q, sim.display(), t, sim.active() ? ", unfinished" : "",
                        100 * sim.averageShare());
                    if (sim.active()) sim.abandon();
                }
            }
        }
    }

    // =====================================================================
    //  Drawing the board
    // =====================================================================

    static final class BoardPanel extends JPanel {
        final Sim sim;
        boolean reveal = false;
        final Font letterFont = new Font("Serif", Font.BOLD, 36);
        final Font letterFont2 = new Font("Serif", Font.BOLD, 30);
        final Font digitFont = new Font("Serif", Font.BOLD, 30);
        final Font wordFont = new Font("Serif", Font.BOLD, 40);
        final Font smallFont = new Font("Serif", Font.ITALIC, 15);

        BoardPanel(Sim sim) {
            this.sim = sim;
            setBackground(new Color(8, 6, 10));
            setPreferredSize(new Dimension(900, 600));
        }

        double flicker() {
            double t = sim.time;
            return 0.86 + 0.06 * Math.sin(t * 7.3) + 0.04 * Math.sin(t * 13.7 + 1.3)
                 + 0.03 * Math.sin(t * 23.1 + 0.4) + 0.02 * Math.sin(t * 2.1);
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = getWidth(), h = getHeight();
            double s = Math.min(w / Board.W, h / Board.H);
            AffineTransform base = g.getTransform();
            g.translate((w - Board.W * s) / 2, (h - Board.H * s) / 2);
            g.scale(s, s);

            double f = flicker();
            drawWood(g, f);
            drawGlyphs(g);
            drawSunMoon(g);
            if (reveal) drawTargets(g);
            drawPlanchette(g);
            if (reveal) drawHands(g);

            g.setTransform(base);
            // candle-light vignette over everything
            float rad = (float) (Math.max(w, h) * 0.75);
            Color edge = new Color(0, 0, 0, (int) Math.min(255, 235 - 60 * (f - 0.8)));
            g.setPaint(new RadialGradientPaint(new Point2D.Double(w / 2.0, h * 0.55), rad,
                new float[]{0f, 0.55f, 1f}, new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 60), edge}));
            g.fillRect(0, 0, w, h);
            g.dispose();
        }

        void drawWood(Graphics2D g, double f) {
            RoundRectangle2D boardShape = new RoundRectangle2D.Double(12, 12, Board.W - 24, Board.H - 24, 70, 70);
            g.setColor(new Color(0, 0, 0, 120));
            g.fill(new RoundRectangle2D.Double(20, 22, Board.W - 24, Board.H - 24, 70, 70));
            Color c1 = scale(new Color(205, 152, 92), f);
            Color c2 = scale(new Color(140, 88, 44), f);
            Color c3 = scale(new Color(58, 34, 16), f);
            g.setPaint(new RadialGradientPaint(new Point2D.Double(450, 300), 560,
                new float[]{0f, 0.55f, 1f}, new Color[]{c1, c2, c3}));
            g.fill(boardShape);
            // wood grain
            Shape oldClip = g.getClip();
            g.clip(boardShape);
            Random grain = new Random(7);
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 70; i++) {
                double y0 = grain.nextDouble() * Board.H;
                double amp = 3 + grain.nextDouble() * 9, ph = grain.nextDouble() * 6;
                Path2D p = new Path2D.Double();
                p.moveTo(0, y0);
                for (int x = 0; x <= Board.W; x += 30)
                    p.lineTo(x, y0 + amp * Math.sin(x / 140.0 + ph) + 2 * Math.sin(x / 37.0 + ph * 2));
                g.setColor(new Color(60, 30, 10, 18 + grain.nextInt(22)));
                g.draw(p);
            }
            g.setClip(oldClip);
            g.setStroke(new BasicStroke(4f));
            g.setColor(new Color(45, 24, 10));
            g.draw(boardShape);
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(new Color(70, 40, 18, 160));
            g.draw(new RoundRectangle2D.Double(26, 26, Board.W - 52, Board.H - 52, 56, 56));

            g.setFont(smallFont);
            g.setColor(new Color(60, 32, 14, 200));
            centre(g, "— ideomotor laboratory · est. 1852 —", 450, 575);
        }

        static Color scale(Color c, double f) {
            return new Color(clamp(c.getRed() * f), clamp(c.getGreen() * f), clamp(c.getBlue() * f));
        }
        static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }

        void drawGlyphs(Graphics2D g) {
            Board b = sim.board;
            for (Target t : b.all) {
                if (t.kind == Kind.PAUSE) continue;
                Double gl = sim.glow.get(t);
                if (gl != null) {
                    float a = (float) Math.min(1, gl);
                    g.setPaint(new RadialGradientPaint(new Point2D.Double(t.x, t.y), (float) (t.r * 2.2),
                        new float[]{0f, 1f}, new Color[]{new Color(255, 225, 150, (int) (200 * a)), new Color(255, 200, 120, 0)}));
                    g.fill(new Ellipse2D.Double(t.x - t.r * 2.2, t.y - t.r * 2.2, t.r * 4.4, t.r * 4.4));
                }
                Color ink = new Color(38, 20, 8);
                if (gl != null) ink = blend(ink, new Color(255, 245, 210), Math.min(1, gl));
                g.setColor(ink);
                if (t.kind == Kind.LETTER) {
                    boolean digit = Character.isDigit(t.ch);
                    boolean inner = !digit && t.r < 22;
                    g.setFont(digit ? digitFont : inner ? letterFont2 : letterFont);
                    AffineTransform at = g.getTransform();
                    g.translate(t.x, t.y);
                    g.rotate(t.angle + Math.PI / 2);
                    centre(g, t.label, 0, 0);
                    g.setTransform(at);
                } else {
                    g.setFont(wordFont);
                    centre(g, t.label, t.x, t.y);
                }
            }
        }

        static Color blend(Color a, Color b, double t) {
            return new Color(clamp(a.getRed() + (b.getRed() - a.getRed()) * t),
                             clamp(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                             clamp(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
        }

        void centre(Graphics2D g, String s, double x, double y) {
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, (float) (x - fm.stringWidth(s) / 2.0), (float) (y + (fm.getAscent() - fm.getDescent()) / 2.0));
        }

        void drawSunMoon(Graphics2D g) {
            Color ink = new Color(38, 20, 8);
            g.setColor(ink);
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double sx = 72, sy = 98;
            g.draw(new Ellipse2D.Double(sx - 16, sy - 16, 32, 32));
            for (int i = 0; i < 12; i++) {
                double a = i * Math.PI / 6;
                g.draw(new Line2D.Double(sx + 22 * Math.cos(a), sy + 22 * Math.sin(a), sx + 31 * Math.cos(a), sy + 31 * Math.sin(a)));
            }
            g.fill(new Ellipse2D.Double(sx - 6, sy - 4, 3.5, 3.5));
            g.fill(new Ellipse2D.Double(sx + 3, sy - 4, 3.5, 3.5));
            g.draw(new Arc2D.Double(sx - 7, sy - 4, 14, 12, 200, 140, Arc2D.OPEN));
            double mx = 830, my = 98;
            Area moon = new Area(new Ellipse2D.Double(mx - 24, my - 24, 48, 48));
            moon.subtract(new Area(new Ellipse2D.Double(mx - 12, my - 30, 46, 46)));
            g.fill(moon);
            for (double[] st : new double[][]{{800, 70}, {856, 132}, {808, 136}}) {
                double x = st[0], y = st[1];
                g.draw(new Line2D.Double(x - 5, y, x + 5, y));
                g.draw(new Line2D.Double(x, y - 5, x, y + 5));
            }
        }

        void drawTargets(Graphics2D g) {
            Target pz = sim.board.pause;
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1, new float[]{4, 5}, 0));
            g.setColor(new Color(40, 20, 8, 110));
            g.draw(new Ellipse2D.Double(pz.x - pz.r, pz.y - pz.r, pz.r * 2, pz.r * 2));
            g.setFont(smallFont);
            centre(g, "pause", pz.x, pz.y + pz.r + 12);
        }

        Shape planchetteBody() {
            Path2D p = new Path2D.Double();
            p.moveTo(0, -64);
            p.curveTo(26, -40, 54, -2, 47, 25);
            p.curveTo(42, 45, 18, 52, 0, 52);
            p.curveTo(-18, 52, -42, 45, -47, 25);
            p.curveTo(-54, -2, -26, -40, 0, -64);
            p.closePath();
            Area a = new Area(p);
            a.subtract(new Area(new Ellipse2D.Double(-14, -14, 28, 28)));
            return a;
        }

        void drawPlanchette(Graphics2D g) {
            AffineTransform at = g.getTransform();
            double tilt = Math.max(-0.3, Math.min(0.3, sim.vx / 700));
            g.translate(sim.px, sim.py);
            g.rotate(tilt);
            Shape body = planchetteBody();
            AffineTransform sh = g.getTransform();
            g.translate(7, 10);
            g.setColor(new Color(0, 0, 0, 90));
            g.fill(body);
            g.setTransform(sh);
            g.setPaint(new GradientPaint(-40, -60, new Color(250, 240, 220, 225), 40, 50, new Color(205, 190, 165, 225)));
            g.fill(body);
            g.setColor(new Color(60, 40, 25));
            g.setStroke(new BasicStroke(2.2f));
            g.draw(body);
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(120, 90, 50));
            g.draw(new Ellipse2D.Double(-14, -14, 28, 28));
            g.setTransform(at);
        }

        void drawHands(Graphics2D g) {
            List<Sitter> ss = sim.sitters;
            int n = ss.size();
            for (int i = 0; i < n; i++) {
                Sitter s = ss.get(i);
                double a = Math.PI / 2 + 2 * Math.PI * i / n + 0.3;
                double fx = sim.px + 30 * Math.cos(a), fy = sim.py + 30 * Math.sin(a);
                Color c = s.colour;
                Target t = s.target;
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1, new float[]{6, 6}, 0));
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 120));
                g.draw(new Line2D.Double(fx, fy, t.x, t.y));
                g.setStroke(new BasicStroke(2.5f));
                double rr = t.r + 4 + 3 * i;
                g.draw(new Ellipse2D.Double(t.x - rr, t.y - rr, rr * 2, rr * 2));
                // force arrows: thick = expectation pull, thin = tremor
                arrow(g, fx, fy, s.pullX * 0.15, s.pullY * 0.15, c, 3.2f);
                arrow(g, fx, fy, s.nx * 0.15, s.ny * 0.15, new Color(c.getRed(), c.getGreen(), c.getBlue(), 150), 1.4f);
                g.setColor(c);
                g.fill(new Ellipse2D.Double(fx - 6, fy - 6, 12, 12));
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(1f));
                g.draw(new Ellipse2D.Double(fx - 6, fy - 6, 12, 12));
            }
        }

        void arrow(Graphics2D g, double x, double y, double dx, double dy, Color c, float w) {
            double len = Math.hypot(dx, dy);
            if (len < 2) return;
            g.setColor(c);
            g.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double ex = x + dx, ey = y + dy;
            g.draw(new Line2D.Double(x, y, ex, ey));
            double ux = dx / len, uy = dy / len, hs = 4 + w * 1.5;
            Path2D head = new Path2D.Double();
            head.moveTo(ex, ey);
            head.lineTo(ex - ux * hs - uy * hs * 0.6, ey - uy * hs + ux * hs * 0.6);
            head.lineTo(ex - ux * hs + uy * hs * 0.6, ey - uy * hs - ux * hs * 0.6);
            head.closePath();
            g.fill(head);
        }
    }

    // =====================================================================
    //  The sitters' panel: what they claim vs what they're doing
    // =====================================================================

    static final class SitterPanel extends JPanel {
        final Sim sim; final BoardPanel board;
        SitterPanel(Sim sim, BoardPanel board) {
            this.sim = sim; this.board = board;
            setBackground(new Color(18, 14, 20));
            setPreferredSize(new Dimension(330, 250));
        }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int y = 22;
            g.setFont(new Font("Serif", Font.BOLD, 15));
            g.setColor(new Color(225, 205, 170));
            g.drawString(board.reveal ? "THE SITTERS — REVEALED" : "THE SITTERS", 12, y);
            y += 12;
            Font name = new Font("SansSerif", Font.BOLD, 12), body = new Font("SansSerif", Font.PLAIN, 12);
            for (Sitter s : sim.sitters) {
                y += 20;
                g.setColor(s.colour);
                g.fillOval(12, y - 10, 11, 11);
                g.setFont(name);
                g.drawString("Sitter " + (s.id + 1), 30, y);
                g.setFont(body);
                g.setColor(new Color(200, 190, 180));
                if (board.reveal) {
                    // bar showing how much of this hand's force is expectation
                    int bx = 100, bw = 150;
                    g.setColor(new Color(60, 50, 60));
                    g.fillRect(bx, y - 9, bw, 9);
                    g.setColor(s.colour);
                    g.fillRect(bx, y - 9, (int) (bw * s.share), 9);
                    g.setColor(new Color(200, 190, 180));
                    g.drawString(String.format("%2.0f%% pull", 100 * s.share), bx + bw + 8, y);
                    y += 16;
                    g.drawString(clip(g, s.expectation, getWidth() - 40), 30, y);
                } else {
                    g.drawString("“I'm definitely not pushing it.”", 100, y);
                }
            }
            y += 26;
            g.setFont(new Font("Serif", Font.ITALIC, 13));
            g.setColor(new Color(170, 155, 140));
            if (board.reveal) {
                g.drawString(String.format("About %.0f%% of each hand's force is expectation;", 100 * sim.averageShare()), 12, y);
                g.drawString("the rest is random tremor. Tremor points every", 12, y + 16);
                g.drawString("which way and cancels out. Expectation doesn't.", 12, y + 32);
            } else {
                g.drawString("Tick “Reveal the hands” to see who is really", 12, y);
                g.drawString("steering.", 12, y + 16);
            }
        }
        static String clip(Graphics2D g, String s, int w) {
            FontMetrics fm = g.getFontMetrics();
            if (fm.stringWidth(s) <= w) return s;
            while (s.length() > 1 && fm.stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
            return s + "…";
        }
    }

    // =====================================================================
    //  The window
    // =====================================================================

    static final class Window extends JFrame {
        final Sim sim = new Sim(System.nanoTime(), 4);
        final BoardPanel board = new BoardPanel(sim);
        final SitterPanel sitterPanel = new SitterPanel(sim, board);
        final JLabel message = new JLabel(" ");
        final JLabel status = new JLabel(" ");
        final JTextArea transcript = new JTextArea();
        final JTextField question = new JTextField("Is anyone there?");
        int speed = 1;
        int shownTranscript = -1;
        String shownMsg = null;

        static final Color BG = new Color(18, 14, 20), FG = new Color(225, 210, 185);

        Window() {
            super("Ouija Lab — the ideomotor effect");
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(BG);
            root.add(board, BorderLayout.CENTER);
            root.add(side(), BorderLayout.EAST);
            root.add(controls(), BorderLayout.SOUTH);
            setContentPane(root);
            pack();
            setMinimumSize(new Dimension(1000, 640));
            setLocationRelativeTo(null);

            new javax.swing.Timer(16, e -> tick()).start();
        }

        JPanel side() {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBackground(BG);
            p.setBorder(new EmptyBorder(10, 8, 10, 10));
            p.setPreferredSize(new Dimension(340, 600));

            JLabel head = new JLabel("THE BOARD SPELLS");
            head.setFont(new Font("Serif", Font.BOLD, 15));
            head.setForeground(new Color(180, 160, 130));
            message.setFont(new Font("Serif", Font.BOLD, 30));
            message.setForeground(new Color(255, 235, 190));
            status.setFont(new Font("Serif", Font.ITALIC, 14));
            status.setForeground(new Color(170, 155, 140));
            for (JComponent c : new JComponent[]{head, message, status}) c.setAlignmentX(LEFT_ALIGNMENT);
            p.add(head);
            p.add(Box.createVerticalStrut(4));
            p.add(message);
            p.add(Box.createVerticalStrut(4));
            p.add(status);
            p.add(Box.createVerticalStrut(10));
            sitterPanel.setAlignmentX(LEFT_ALIGNMENT);
            sitterPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 290));
            sitterPanel.setPreferredSize(new Dimension(330, 290));
            p.add(sitterPanel);
            p.add(Box.createVerticalStrut(8));

            JLabel th = new JLabel("TRANSCRIPT");
            th.setFont(new Font("Serif", Font.BOLD, 15));
            th.setForeground(new Color(180, 160, 130));
            th.setAlignmentX(LEFT_ALIGNMENT);
            p.add(th);
            transcript.setEditable(false);
            transcript.setLineWrap(true);
            transcript.setWrapStyleWord(true);
            transcript.setFont(new Font("Serif", Font.PLAIN, 14));
            transcript.setBackground(new Color(26, 20, 28));
            transcript.setForeground(FG);
            transcript.setBorder(new EmptyBorder(6, 6, 6, 6));
            JScrollPane sp = new JScrollPane(transcript);
            sp.setBorder(BorderFactory.createLineBorder(new Color(50, 40, 50)));
            sp.setAlignmentX(LEFT_ALIGNMENT);
            p.add(sp);
            return p;
        }

        JPanel controls() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setBackground(BG);
            p.setBorder(new EmptyBorder(6, 10, 10, 10));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 4, 3, 4);
            c.anchor = GridBagConstraints.WEST;

            JButton ask = new JButton("Ask");
            JButton clear = new JButton("Break the circle");
            ActionListener doAsk = e -> sim.ask(question.getText());
            ask.addActionListener(doAsk);
            question.addActionListener(doAsk);
            clear.addActionListener(e -> sim.abandon());
            question.setColumns(28);
            question.setFont(new Font("Serif", Font.PLAIN, 16));

            c.gridx = 0; c.gridy = 0; p.add(label("Ask the spirits:"), c);
            c.gridx = 1; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1; p.add(question, c);
            c.gridwidth = 1; c.fill = GridBagConstraints.NONE; c.weightx = 0;
            c.gridx = 4; p.add(ask, c);
            c.gridx = 5; p.add(clear, c);

            JSlider bias = slider((int) Math.round(sim.bias * 100), v -> sim.bias = v / 100.0);
            JSlider tremor = slider((int) Math.round(sim.tremor * 100), v -> sim.tremor = v / 100.0);
            JSpinner sitters = new JSpinner(new SpinnerNumberModel(4, 1, 6, 1));
            sitters.addChangeListener(e -> sim.setSitterCount((Integer) sitters.getValue()));
            JComboBox<String> spd = new JComboBox<>(new String[]{"1×", "2×", "4×"});
            spd.addActionListener(e -> speed = new int[]{1, 2, 4}[spd.getSelectedIndex()]);
            JCheckBox reveal = new JCheckBox("Reveal the hands");
            reveal.setForeground(FG);
            reveal.setOpaque(false);
            reveal.addActionListener(e -> board.reveal = reveal.isSelected());

            c.gridy = 1;
            c.gridx = 0; p.add(label("Unconscious bias:"), c);
            c.gridx = 1; p.add(bias, c);
            c.gridx = 2; p.add(label("Tremor:"), c);
            c.gridx = 3; p.add(tremor, c);
            JPanel small = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            small.setOpaque(false);
            small.add(label("Sitters:")); small.add(sitters);
            small.add(label("Speed:")); small.add(spd);
            small.add(reveal);
            c.gridx = 4; c.gridwidth = 2; p.add(small, c);
            return p;
        }

        JLabel label(String s) {
            JLabel l = new JLabel(s);
            l.setForeground(FG);
            l.setFont(new Font("Serif", Font.PLAIN, 15));
            return l;
        }

        JSlider slider(int v, java.util.function.IntConsumer onChange) {
            JSlider s = new JSlider(0, 100, v);
            s.setOpaque(false);
            s.setPreferredSize(new Dimension(170, 26));
            s.addChangeListener(e -> onChange.accept(s.getValue()));
            return s;
        }

        void tick() {
            for (int i = 0; i < speed; i++) sim.step(1 / 60.0);
            String m = sim.display();
            if (!m.equals(shownMsg)) {
                shownMsg = m;
                message.setText(m.isEmpty() ? " " : "<html>" + m + "</html>");
            }
            switch (sim.mode) {
                case IDLE:  status.setText("Hands on the planchette. Ask a question."); break;
                case OPEN:
                case YESNO: status.setText(m.isEmpty() ? "The planchette begins to stir…" : "It is still moving…"); break;
                case ENDED: status.setText("GOODBYE. The spirit has departed."); break;
            }
            if (sim.transcript.size() != shownTranscript) {
                shownTranscript = sim.transcript.size();
                StringBuilder sb = new StringBuilder();
                for (int i = sim.transcript.size() - 1; i >= 0; i--) sb.append(sim.transcript.get(i)).append("\n\n");
                transcript.setText(sb.toString());
                transcript.setCaretPosition(0);
            }
            board.repaint();
            sitterPanel.repaint();
        }
    }
}
