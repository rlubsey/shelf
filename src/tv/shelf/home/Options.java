package tv.shelf.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.widget.Toast;

/** Every setting is a row: left/right or OK cycles it, and the change is saved at once. */
public final class Options extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<Runnable> refreshers = new ArrayList<>();
    private Config cfg;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF0C0C0E);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(list);

        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        title.setText("Options");
        title.setPadding(dp(16), 0, 0, dp(20));
        list.addView(title);

        for (Config.Choice c : Config.CHOICES) list.addView(choice(c));
        list.addView(gap());
        list.addView(action("Apps on home screen", () -> pick(Catalog.APPS)));
        list.addView(action("Inputs on home screen", () -> pick(Catalog.INPUTS)));
        list.addView(action("Sports leagues", this::pickLeagues));
        list.addView(action("Favorite teams", this::pickTeams));
        list.addView(action("Check for updates", () -> Updater.check(this)));
        list.addView(action("Reset order, names and hidden tiles", this::reset));
        list.addView(action("Open Amazon's home screen", () -> {
            Guard.amazon(this);
            finish();
        }));

        TextView about = Ui.text(this, 13, Ui.FAINT, Ui.MEDIUM);
        about.setLineSpacing(0, 1.4f);
        about.setPadding(dp(16), dp(28), 0, 0);
        boolean homeOk = android.provider.Settings.canDrawOverlays(this);
        about.setText("Shelf " + Updater.installedName(this) + "  ·  updates from github.com/" + Updater.REPO
                + (homeOk ? "\nHome button: ready"
                        : "\nHome button: blocked by Fire OS. Run ./install.sh from your Mac to fix it")
                + "\nReorder apps: hold OK on a tile (or press ☰), choose Move, then use the arrows"
                + "\nThe first row is your dock: move apps into it to keep them on top"
                + "\nScores: ESPN public data, every minute during live games. Internet is used only for scores and team logos"
                + "\nScore alerts: kick-off, goals, half-time and full-time for favorite teams, shown over any app"
                + "\nSettings file: " + cfg.dir + "/" + Config.FILE
                + "\nBuilt-in photo: replace res/drawable-nodpi/wallpaper.jpg and rebuild to change it"
                + "\nImage file: wallpaper.jpg in the folder above (if your TV lets you copy it there)"
                + "\nNo tracking, no ads, no accounts.");
        list.addView(about);

        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private View choice(Config.Choice c) {
        LinearLayout row = row();
        TextView name = label(c.title);
        TextView value = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
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
            boolean focused = row.isFocused();
            String text = c.name(cfg.get(c));
            if (c == Config.ACCENT) {
                SpannableString s = new SpannableString("●  " + text);
                s.setSpan(new ForegroundColorSpan(cfg.accent()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setText(focused ? "‹  " : "");
                value.append(s);
                if (focused) value.append("  ›");
            } else {
                value.setText(focused ? "‹  " + text + "  ›" : text);
            }
            value.setTextColor(focused ? Ui.TEXT : Ui.MUTED);
            String bg = cfg.get(Config.BACKGROUND);
            boolean off = c == Config.DIM && !"image".equals(bg) && !"photo".equals(bg);
            row.setAlpha(off ? 0.4f : 1f);
        };
        row.setOnFocusChangeListener((v, has) -> show.run());
        refreshers.add(show);
        refreshers.add(() -> style(row));
        return row;
    }

    private boolean step(Config.Choice c, int by) {
        int i = Arrays.asList(c.values).indexOf(cfg.get(c));
        int n = c.values.length;
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        refresh();
        return true;
    }

    private View action(String text, Runnable run) {
        LinearLayout row = row();
        row.addView(label(text), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView chevron = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        chevron.setText("›");
        row.addView(chevron);
        row.setOnClickListener(v -> run.run());
        refreshers.add(() -> style(row));
        return row;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(600), WRAP);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private void style(View row) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused},
                Ui.round((cfg.accent() & 0x00FFFFFF) | 0x33000000, dp(10)));
        bg.addState(new int[0], Ui.round(0x00000000, dp(10)));
        row.setBackground(bg);
    }

    private TextView label(String text) {
        TextView v = Ui.text(this, 16, Ui.TEXT, Ui.MEDIUM);
        v.setText(text);
        return v;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(18)));
        return v;
    }

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
                .setTitle(Catalog.APPS.equals(kind) ? "Apps on home screen" : "Inputs on home screen")
                .setMultiChoiceItems(names, on, (d, which, checked) ->
                        Catalog.setShown(cfg, kind, items.get(which), checked))
                .setPositiveButton("Done", null)
                .show();
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
                .setPositiveButton("Reset", (d, w) -> cfg.removePrefix("name.", "apps.", "inputs."))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
