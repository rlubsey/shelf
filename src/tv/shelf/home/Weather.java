package tv.shelf.home;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Current conditions from Open-Meteo (free, no key, no account). The location is a city picked
 * in Options; it defaults to Kingston, Jamaica. Network: call off the UI thread.
 */
final class Weather {
    static final String CITY = "weather.city", LAT = "weather.lat", LON = "weather.lon";
    static final String DEFAULT_CITY = "Kingston, Jamaica";
    static final double DEFAULT_LAT = 17.99702, DEFAULT_LON = -76.79358;

    static final class Now {
        final int temp, code;
        final boolean day;

        Now(int temp, int code, boolean day) {
            this.temp = temp;
            this.code = code;
            this.day = day;
        }
    }

    static final class Place {
        final String label;
        final double lat, lon;

        Place(String label, double lat, double lon) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Weather() {}

    static double lat(Config cfg) {
        return number(cfg.get(LAT, ""), DEFAULT_LAT);
    }

    static double lon(Config cfg) {
        return number(cfg.get(LON, ""), DEFAULT_LON);
    }

    static String city(Config cfg) {
        return cfg.get(CITY, DEFAULT_CITY);
    }

    private static double number(String s, double fallback) {
        try {
            return s.isEmpty() ? fallback : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** The current temperature (rounded) and WMO weather code, or null when offline. */
    static Now now(double lat, double lon, boolean fahrenheit) {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day&temperature_unit=%s&timezone=auto",
                lat, lon, fahrenheit ? "fahrenheit" : "celsius");
        try {
            JSONObject cur = new JSONObject(Sports.get(url)).getJSONObject("current");
            return new Now((int) Math.round(cur.getDouble("temperature_2m")),
                    cur.optInt("weather_code", 0), cur.optInt("is_day", 1) == 1);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /** Places matching a typed city name, best first. */
    static List<Place> search(String name) {
        List<Place> out = new ArrayList<>();
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name="
                    + URLEncoder.encode(name.trim(), "UTF-8");
            JSONArray results = new JSONObject(Sports.get(url)).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r == null) continue;
                StringBuilder label = new StringBuilder(r.optString("name"));
                String admin = r.optString("admin1", ""), country = r.optString("country", "");
                if (!admin.isEmpty() && !admin.equals(r.optString("name"))) label.append(", ").append(admin);
                if (!country.isEmpty()) label.append(", ").append(country);
                out.add(new Place(label.toString(), r.optDouble("latitude"), r.optDouble("longitude")));
            }
        } catch (IOException | JSONException e) {
            // the picker says nothing was found
        }
        return out;
    }

    /** What to draw for a WMO weather code. */
    enum Kind { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

    static Kind kind(int code) {
        if (code == 0) return Kind.CLEAR;
        if (code <= 2) return Kind.PARTLY;
        if (code == 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95) return Kind.STORM;
        if (code >= 51 && code <= 82) return Kind.RAIN;
        return Kind.CLOUDY;
    }
}
