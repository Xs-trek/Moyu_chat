package com.moyu.chat;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * Mascot state the WebView cannot keep itself (its DOM storage is off): the boss-key disguise, the
 * waiting mini-game's best score and the day the rare koi colours were rolled. Only these keys are
 * writable through {@code ui.mascot.set}; they live in the ordinary settings table.
 */
final class MascotSettings {
    static final String DISGUISE = "disguise";
    static final String GAME_BEST = "gameBest";
    static final String KOI_DAY = "koiDay";
    static final int MAX_SCORE = 99_999;
    private static final Pattern DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private final ChatDatabase db;
    private boolean disguise;
    private int gameBest;
    private String koiDay;

    MascotSettings(ChatDatabase db) {
        this.db = db;
        this.disguise = "true".equals(db.setting("mascot." + DISGUISE, "false"));
        int best;
        try { best = Integer.parseInt(db.setting("mascot." + GAME_BEST, "0")); } catch (NumberFormatException e) { best = 0; }
        this.gameBest = Math.max(0, Math.min(MAX_SCORE, best));
        String day = db.setting("mascot." + KOI_DAY, "");
        this.koiDay = DAY.matcher(day).matches() ? day : "";
    }

    /** The best score only ever rises; a day must be YYYY-MM-DD (or empty to forget the koi). */
    void set(String key, Object value) {
        switch (key) {
            case DISGUISE:
                disguise = Boolean.TRUE.equals(value) || "true".equals(String.valueOf(value));
                db.putSetting("mascot." + DISGUISE, disguise ? "true" : "false");
                break;
            case GAME_BEST: {
                if (!(value instanceof Number)) throw new IllegalArgumentException("gameBest 必须是数字");
                int score = ((Number) value).intValue();
                if (score < 0 || score > MAX_SCORE) throw new IllegalArgumentException("gameBest 超出范围");
                if (score > gameBest) { gameBest = score; db.putSetting("mascot." + GAME_BEST, String.valueOf(score)); }
                break;
            }
            case KOI_DAY: {
                String day = value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
                if (!day.isEmpty() && !DAY.matcher(day).matches()) throw new IllegalArgumentException("koiDay 格式应为 YYYY-MM-DD");
                koiDay = day;
                db.putSetting("mascot." + KOI_DAY, day);
                break;
            }
            default: throw new IllegalArgumentException("unsupported mascot key: " + key);
        }
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put(DISGUISE, disguise).put(GAME_BEST, gameBest).put(KOI_DAY, koiDay);
    }
}
