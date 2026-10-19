package tv.shelf.home;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Scores from ESPN's public site API (no key, no account). Everything here does network I/O:
 * call it off the UI thread. Failures return empty lists; the home screen never waits on it.
 */
final class Sports {
    static final String LEAGUES_KEY = "sports.leagues";
    static final String TEAMS_KEY = "sports.teams";
    static final String DEFAULT_LEAGUES = "eng.1,uefa.champions";
    private static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final int TIMEOUT_MS = 8000;

    static final class League {
        final String sport, id, name, abbr;

        League(String sport, String id, String name, String abbr) {
            this.sport = sport;
            this.id = id;
            this.name = name;
            this.abbr = abbr;
        }
    }

    static final League[] LEAGUES = {
        new League("soccer", "eng.1", "Premier League", "EPL"),
        new League("soccer", "esp.1", "LaLiga", "LIGA"),
        new League("soccer", "ita.1", "Serie A", "SA"),
        new League("soccer", "ger.1", "Bundesliga", "BUN"),
        new League("soccer", "fra.1", "Ligue 1", "L1"),
        new League("soccer", "usa.1", "MLS", "MLS"),
        new League("soccer", "uefa.champions", "Champions League", "UCL"),
        new League("soccer", "uefa.europa", "Europa League", "UEL"),
        new League("soccer", "eng.fa", "FA Cup", "FAC"),
        new League("soccer", "eng.league_cup", "Carabao Cup", "EFL"),
        new League("soccer", "concacaf.champions", "Concacaf Champions Cup", "CCC"),
        new League("basketball", "nba", "NBA", "NBA"),
        new League("football", "nfl", "NFL", "NFL"),
        new League("baseball", "mlb", "MLB", "MLB"),
    };

    static final class Team {
        final League league;
        final String id, name, abbr;

        Team(League league, String id, String name, String abbr) {
            this.league = league;
            this.id = id;
            this.name = name;
            this.abbr = abbr;
        }

        String key() {
            return league.id + ":" + id;
        }
    }

    static final class Side {
        String id = "", abbr = "", name = "", score = "", logo = "";
    }

    static final class Game {
        League league;
        String id = "", state = "pre", detail = "";
        long start;
        final Side home = new Side(), away = new Side();

        boolean live() {
            return "in".equals(state);
        }

        boolean done() {
            return "post".equals(state);
        }

        boolean involves(Set<String> favorites) {
            return favorites.contains(league.id + ":" + home.id) || favorites.contains(league.id + ":" + away.id);
        }
    }

    private Sports() {}

    static League league(String id) {
        for (League l : LEAGUES) {
            if (l.id.equals(id)) return l;
        }
        return null;
    }

    /** The leagues picked in Options; a never-touched setting means the defaults. */
    static List<League> chosen(Config cfg) {
        List<League> out = new ArrayList<>();
        String raw = cfg.get(LEAGUES_KEY, DEFAULT_LEAGUES);
        if ("-".equals(raw)) return out;
        for (String s : raw.split(",")) {
            League l = league(s.trim());
            if (l != null) out.add(l);
        }
        return out;
    }

    static void setChosen(Config cfg, List<String> ids) {
        cfg.set(LEAGUES_KEY, ids.isEmpty() ? "-" : String.join(",", ids));
    }

    static Set<String> favorites(Config cfg) {
        return new HashSet<>(cfg.list(TEAMS_KEY));
    }

    private static boolean hasFavoriteIn(Set<String> favorites, League l) {
        for (String f : favorites) {
            if (f.startsWith(l.id + ":")) return true;
        }
        return false;
    }

    /**
     * Games worth showing, best first: live, then upcoming (soonest first), then results (newest
     * first). A league with favorite teams shows only their games, from yesterday to four days
     * ahead, so the last result and the next fixture appear. Other leagues show today's games.
     */
    static List<Game> load(List<League> leagues, Set<String> favorites, int max) {
        List<Game> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (League l : leagues) {
            boolean picky = hasFavoriteIn(favorites, l);
            Map<String, Game> seen = new LinkedHashMap<>();
            add(seen, scoreboard(l, null));
            if (picky) {
                for (int offset : new int[] {-1, 1, 2, 3, 4}) {
                    Calendar c = Calendar.getInstance();
                    c.add(Calendar.DAY_OF_YEAR, offset);
                    add(seen, scoreboard(l, day(c)));
                }
            }
            for (Game g : seen.values()) {
                if (picky ? g.involves(favorites) : today(g, now)) out.add(g);
            }
        }
        Collections.sort(out, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return Integer.compare(ra, rb);
            return a.done() ? Long.compare(b.start, a.start) : Long.compare(a.start, b.start);
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    private static int rank(Game g) {
        return g.live() ? 0 : g.done() ? 2 : 1;
    }

    private static boolean today(Game g, long now) {
        return g.live() || g.start > now - 14 * 3600_000L && g.start < now + 24 * 3600_000L;
    }

    private static void add(Map<String, Game> seen, List<Game> games) {
        for (Game g : games) seen.put(g.id.isEmpty() ? g.home.id + g.away.id + g.start : g.id, g);
    }

    private static String day(Calendar c) {
        return String.format(Locale.US, "%04d%02d%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static List<Game> scoreboard(League l, String day) {
        List<Game> out = new ArrayList<>();
        String url = BASE + l.sport + "/" + l.id + "/scoreboard" + (day == null ? "" : "?dates=" + day);
        try {
            JSONArray events = new JSONObject(get(url)).optJSONArray("events");
            if (events == null) return out;
            for (int i = 0; i < events.length(); i++) {
                Game g = game(l, events.optJSONObject(i));
                if (g != null) out.add(g);
            }
        } catch (IOException | JSONException e) {
            // offline, blocked or a format change: show nothing for this league
        }
        return out;
    }

    private static Game game(League l, JSONObject e) {
        if (e == null) return null;
        JSONArray comps = e.optJSONArray("competitions");
        JSONObject comp = comps == null ? null : comps.optJSONObject(0);
        if (comp == null) return null;
        Game g = new Game();
        g.league = l;
        g.id = e.optString("id");
        g.start = parseDate(e.optString("date"));
        JSONObject status = e.optJSONObject("status");
        if (status == null) status = comp.optJSONObject("status");
        JSONObject type = status == null ? null : status.optJSONObject("type");
        if (type != null) {
            g.state = type.optString("state", "pre");
            g.detail = type.optString("shortDetail", "");
        }
        JSONArray sides = comp.optJSONArray("competitors");
        if (sides == null) return null;
        for (int i = 0; i < sides.length(); i++) {
            JSONObject c = sides.optJSONObject(i);
            if (c == null) continue;
            Side s = "home".equals(c.optString("homeAway")) ? g.home : g.away;
            JSONObject t = c.optJSONObject("team");
            if (t != null) {
                s.id = t.optString("id");
                s.abbr = t.optString("abbreviation");
                s.name = t.optString("shortDisplayName", t.optString("displayName"));
                s.logo = t.optString("logo");
                if (s.logo.isEmpty()) s.logo = firstLogo(t);
            }
            Object score = c.opt("score");
            if (score instanceof JSONObject) s.score = ((JSONObject) score).optString("displayValue");
            else if (score != null) s.score = String.valueOf(score);
        }
        return g;
    }

    private static String firstLogo(JSONObject team) {
        JSONArray logos = team.optJSONArray("logos");
        JSONObject first = logos == null ? null : logos.optJSONObject(0);
        return first == null ? "" : first.optString("href");
    }

    /** Every team in a league, for the favorites picker. */
    static List<Team> teams(League l) {
        List<Team> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(get(BASE + l.sport + "/" + l.id + "/teams"));
            JSONArray sports = root.optJSONArray("sports");
            JSONObject sport = sports == null ? null : sports.optJSONObject(0);
            JSONArray leagues = sport == null ? null : sport.optJSONArray("leagues");
            JSONObject league = leagues == null ? null : leagues.optJSONObject(0);
            JSONArray teams = league == null ? null : league.optJSONArray("teams");
            if (teams == null) return out;
            for (int i = 0; i < teams.length(); i++) {
                JSONObject wrap = teams.optJSONObject(i);
                JSONObject t = wrap == null ? null : wrap.optJSONObject("team");
                if (t == null) continue;
                out.add(new Team(l, t.optString("id"), t.optString("displayName"), t.optString("abbreviation")));
            }
        } catch (IOException | JSONException e) {
            // the picker says it could not load
        }
        return out;
    }

    /** A team logo, shrunk to roughly icon size. */
    static Bitmap logo(String url) {
        HttpURLConnection http = null;
        try {
            http = open(url);
            try (InputStream in = http.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (http != null) http.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        http.setRequestProperty("User-Agent", "Shelf/1.0 (Fire TV launcher)");
        int code = http.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            http.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return http;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection http = open(url);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(http.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            for (int n; (n = r.read(buf)) > 0; ) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            http.disconnect();
        }
    }

    private static long parseDate(String s) {
        for (String pattern : new String[] {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"}) {
            SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            try {
                return f.parse(s).getTime();
            } catch (ParseException ignored) {
                // try the next pattern
            }
        }
        return 0;
    }
}
