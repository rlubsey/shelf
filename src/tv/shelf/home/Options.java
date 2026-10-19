package tv.shelf.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Options, styled like the home screen: the blurred wallpaper behind a frosted card, settings
 * grouped into sections, and tvOS-style rows (a white pill with dark text when focused). Each
 * setting row cycles with ◀ ▶ or OK and saves at once.
 */
public final class Options extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int INK = 0xFF111114;

    private final List<Runnable> refreshers = new ArrayList<>();
    private Config cfg;
    private ImageView backdrop;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(MATCH, MATCH));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x99000000, 0x59000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout split = new LinearLayout(this);
        split.setPadding(dp(64), dp(48), dp(48), dp(40));
        root.addView(split, new FrameLayout.LayoutParams(MATCH, MATCH));

        // left: title and status
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(0, dp(8), dp(40), 0);
        TextView title = Ui.text(this, 40, Ui.TEXT, Ui.LIGHT);
        title.setText("Options");
        side.addView(title);
        TextView version = Ui.text(this, 14, Ui.MUTED, Ui.MEDIUM);
        version.setText("Shelf " + Updater.installedName(this));
        version.setPadding(dp(2), dp(6), 0, dp(22));
        side.addView(version);
        status = Ui.text(this, 13, Ui.MUTED, Ui.MEDIUM);
        status.setLineSpacing(0, 1.45f);
        side.addView(status);
        split.addView(side, new LinearLayout.LayoutParams(0, MATCH, 0.36f));

        // right: the frosted card of settings
        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.round(0x99141519, dp(26)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(14), dp(10), dp(14), dp(18));
        scroll.addView(list);
        card.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        split.addView(card, new LinearLayout.LayoutParams(0, MATCH, 0.64f));

        list.addView(section("Appearance"));
        list.addView(choice(Config.BACKGROUND));
        list.addView(choice(Config.DIM));
        list.addView(choice(Config.TILES));
        list.addView(choice(Config.LABELS));

        list.addView(section("Home screen"));
        list.addView(choice(Config.RECENT));
        list.addView(choice(Config.RECENT_MEDIA));
        list.addView(action("Clear watch history", null, () -> new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Clear watch history?")
                .setMessage("Removes the titles and pictures Shelf saved from what you watched. Apps you used stay in the row.")
                .setPositiveButton("Clear", (d, w) -> {
                    History.clear(this);
                    Toast.makeText(this, "Watch history cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show()));
        list.addView(choice(Config.TEMP));
        list.addView(action("Weather location", () -> Weather.city(cfg), this::pickCity));
        list.addView(choice(Config.CLOCK));
        list.addView(action("Apps on home screen", null, () -> pick(Catalog.APPS)));
        list.addView(action("Inputs in the menu", null, () -> pick(Catalog.INPUTS)));

        list.addView(section("Sports"));
        list.addView(choice(Config.SPORTS));
        list.addView(choice(Config.ALERTS));
        list.addView(action("Leagues", () -> leaguesSummary(), this::pickLeagues));
        list.addView(action("Favorite teams", () -> teamsSummary(), this::pickTeams));

        list.addView(section("System"));
        list.addView(action("Check for updates", () -> Updater.installedName(this), () -> Updater.check(this)));
        list.addView(action("Reset order, names and hidden tiles", null, this::reset));
        list.addView(action("Open Amazon's home screen", null, () -> {
            Guard.amazon(this);
            finish();
        }));

        setContentView(root);
        refresh();
        loadBackdrop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    private void loadBackdrop() {
        Context app = getApplicationContext();
        new Thread(Ui.safe(() -> {
            Wall.Pair p = Wall.load(app, cfg);
            runOnUiThread(() -> {
                if (!isDestroyed() && p != null) backdrop.setImageBitmap(p.soft);
            });
        }), "shelf-options-wall").start();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
        boolean homeOk = android.provider.Settings.canDrawOverlays(this);
        boolean recentOk = Recent.allowed(this);
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
        dot(s, homeOk, homeOk ? "Home button: ready" : "Home button: blocked. Run ./install.sh from your Mac");
        if ("on".equals(cfg.get(Config.RECENT))) {
            s.append('\n');
            dot(s, recentOk, recentOk ? "Recently used: ready" : "Recently used: needs access. Run ./install.sh --setup");
            if ("titles".equals(cfg.get(Config.RECENT_MEDIA))) {
                boolean mediaOk = Recent.mediaAllowed(this);
                s.append('\n');
                dot(s, mediaOk, mediaOk ? "What you watched: ready" : "What you watched: needs access. Run ./install.sh --setup");
            }
        }
        s.append("\n\nReorder apps: hold OK on a tile, choose Move, then use the arrows. The first row is your dock.");
        s.append("\n\nScores from ESPN, weather from Open-Meteo. What you watched is kept on this TV only. No accounts, no tracking, no ads.");
        status.setText(s);
    }

    private static void dot(android.text.SpannableStringBuilder s, boolean ok, String text) {
        int at = s.length();
        s.append("●  ").append(text);
        s.setSpan(new android.text.style.ForegroundColorSpan(ok ? 0xFF34C759 : 0xFFFFB340),
                at, at + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ---------------------------------------------------------------- rows

    private TextView section(String name) {
        TextView t = Ui.text(this, 12, Ui.FAINT, Ui.MEDIUM);
        t.setText(name.toUpperCase(java.util.Locale.ROOT));
        t.setLetterSpacing(0.14f);
        t.setPadding(dp(16), dp(20), 0, dp(6));
        return t;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(13), dp(16), dp(13));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, Ui.round(0xFFFFFFFF, dp(14)));
        bg.addState(new int[0], Ui.round(0x00000000, dp(14)));
        row.setBackground(bg);
        row.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.015f : 1f).setDuration(120).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(2);
        row.setLayoutParams(lp);
        return row;
    }

    /** Text that turns dark on the white focus pill. */
    private TextView text(float sp, int idle) {
        TextView v = Ui.text(this, sp, idle, Ui.MEDIUM);
        v.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_focused}, {}}, new int[] {INK, idle}));
        v.setDuplicateParentStateEnabled(true);
        return v;
    }

    private View choice(Config.Choice c) {
        LinearLayout row = row();
        TextView name = text(17, Ui.TEXT);
        name.setText(c.title);
        TextView value = text(16, Ui.MUTED);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(v -> step(c, 1));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return step(c, -1);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return step(c, 1);
            return false;
        });
        Runnable show = () -> {
            String text = c.name(cfg.get(c));
            if (c == Config.RECENT && "on".equals(cfg.get(c)) && !Recent.allowed(this)) text += " (needs access)";
            value.setText(row.isFocused() ? "‹  " + text + "  ›" : text);
            boolean off = c == Config.DIM && Wall.key(cfg) == null
                    || c == Config.RECENT_MEDIA && !"on".equals(cfg.get(Config.RECENT))
                    || c == Config.ALERTS && !"on".equals(cfg.get(Config.SPORTS));
            row.setAlpha(off ? 0.45f : 1f);
        };
        View.OnFocusChangeListener zoom = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, has) -> {
            zoom.onFocusChange(v, has);
            show.run();
        });
        refreshers.add(show);
        return row;
    }

    private boolean step(Config.Choice c, int by) {
        int i = Arrays.asList(c.values).indexOf(cfg.get(c));
        int n = c.values.length;
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        refresh();
        return true;
    }

    private View action(String label, Supplier<String> summary, Runnable run) {
        LinearLayout row = row();
        TextView name = text(17, Ui.TEXT);
        name.setText(label);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView value = text(16, Ui.MUTED);
        value.setMaxWidth(dp(260));
        value.setSingleLine();
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        TextView chevron = text(18, Ui.MUTED);
        chevron.setText("  ›");
        row.addView(chevron);
        row.setOnClickListener(v -> run.run());
        if (summary != null) refreshers.add(() -> value.setText(summary.get()));
        return row;
    }

    private String leaguesSummary() {
        List<Sports.League> chosen = Sports.chosen(cfg);
        if (chosen.isEmpty()) return "None";
        StringBuilder s = new StringBuilder();
        for (Sports.League l : chosen) s.append(s.length() == 0 ? "" : ", ").append(l.abbr);
        return s.toString();
    }

    private String teamsSummary() {
        int n = Sports.favorites(cfg).size();
        return n == 0 ? "None" : n + (n == 1 ? " team" : " teams");
    }

    // ---------------------------------------------------------------- pickers

    private void pick(String kind) {
        List<Item> items = Catalog.APPS.equals(kind)
                ? Catalog.apps(this)
                : Catalog.inputs(this, (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE));
        CharSequence[] names = new CharSequence[items.size()];
        boolean[] on = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = Catalog.label(cfg, items.get(i));
            on[i] = Catalog.shown(cfg, kind, items.get(i));
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.APPS.equals(kind) ? "Apps on home screen" : "Inputs in the menu")
                .setMultiChoiceItems(names, on, (d, which, checked) ->
                        Catalog.setShown(cfg, kind, items.get(which), checked))
                .setPositiveButton("Done", null)
                .show();
    }

    /** Type a city, pick the right match, and the weather follows it. */
    private void pickCity() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setHint("City, e.g. Kingston");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Weather location")
                .setView(box)
                .setPositiveButton("Search", (dialog, w) -> searchCity(field.getText().toString()))
                .setNeutralButton("Kingston, Jamaica", (dialog, w) -> saveCity(
                        new Weather.Place(Weather.DEFAULT_CITY, Weather.DEFAULT_LAT, Weather.DEFAULT_LON)))
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void searchCity(String name) {
        if (name.trim().isEmpty()) return;
        Toast.makeText(this, "Searching…", Toast.LENGTH_SHORT).show();
        new Thread(Ui.safe(() -> {
            List<Weather.Place> found = Weather.search(name);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (found.isEmpty()) {
                    Toast.makeText(this, "No place called \"" + name.trim() + "\" found", Toast.LENGTH_LONG).show();
                    return;
                }
                CharSequence[] labels = new CharSequence[found.size()];
                for (int i = 0; i < found.size(); i++) labels[i] = found.get(i).label;
                new AlertDialog.Builder(this, Ui.DIALOG)
                        .setTitle("Which one?")
                        .setItems(labels, (d, which) -> saveCity(found.get(which)))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }), "shelf-city").start();
    }

    private void saveCity(Weather.Place p) {
        cfg.set(Weather.CITY, p.label);
        cfg.set(Weather.LAT, Double.toString(p.lat));
        cfg.set(Weather.LON, Double.toString(p.lon));
        refresh();
    }

    private void pickLeagues() {
        Sports.League[] all = Sports.LEAGUES;
        CharSequence[] names = new CharSequence[all.length];
        boolean[] on = new boolean[all.length];
        List<Sports.League> chosen = Sports.chosen(cfg);
        for (int i = 0; i < all.length; i++) {
            names[i] = all[i].name;
            on[i] = chosen.contains(all[i]);
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Sports leagues")
                .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    List<String> ids = new ArrayList<>();
                    for (int i = 0; i < all.length; i++) {
                        if (on[i]) ids.add(all[i].id);
                    }
                    Sports.setChosen(cfg, ids);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Loads every team in the chosen leagues (network), then shows them as a checklist. */
    private void pickTeams() {
        List<Sports.League> leagues = Sports.chosen(cfg);
        if (leagues.isEmpty()) {
            Toast.makeText(this, "Pick leagues first", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Loading teams…", Toast.LENGTH_SHORT).show();
        new Thread(Ui.safe(() -> {
            List<Sports.Team> teams = new ArrayList<>();
            for (Sports.League l : leagues) teams.addAll(Sports.teams(l));
            runOnUiThread(() -> showTeams(teams));
        }), "shelf-teams").start();
    }

    private void showTeams(List<Sports.Team> teams) {
        if (isFinishing() || isDestroyed()) return;
        if (teams.isEmpty()) {
            Toast.makeText(this, "Couldn't load teams. Check the TV's internet connection.", Toast.LENGTH_LONG).show();
            return;
        }
        Set<String> favorites = new HashSet<>(Sports.favorites(cfg));
        CharSequence[] names = new CharSequence[teams.size()];
        boolean[] on = new boolean[teams.size()];
        for (int i = 0; i < teams.size(); i++) {
            Sports.Team t = teams.get(i);
            names[i] = t.name + "  ·  " + t.league.abbr;
            on[i] = favorites.contains(t.key());
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Favorite teams")
                .setMultiChoiceItems(names, on, (d, which, checked) -> {
                    if (checked) favorites.add(teams.get(which).key());
                    else favorites.remove(teams.get(which).key());
                })
                .setPositiveButton("Done", (d, w) -> {
                    cfg.setList(Sports.TEAMS_KEY, new ArrayList<>(favorites));
                    refresh();
                })
                .setNeutralButton("Clear all", (d, w) -> {
                    cfg.set(Sports.TEAMS_KEY, null);
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void reset() {
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Reset the home screen layout?")
                .setMessage("Order, names and hidden tiles go back to default. Colors and sizes stay.")
                .setPositiveButton("Reset", (d, w) -> cfg.removePrefix("name.", "apps.", "inputs.", Recent.HIDE_KEY))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
