package com.moyu.chat;

import org.json.JSONArray;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Global on/off switches of the helper modules (CLAUDE.md principle 4), stored in the settings table. A switch
 * only takes away a helper the session's context mode would run; it never adds one. The switched-off set is
 * frozen into each turn's configuration, so results stay attributable and statistics can compare combinations.
 */
final class HelperSwitches {
    static final String ROUTER = "router", COMPACTION = "compaction", WEB_PLAN = "webPlan", VERIFY = "verify", SUBAGENT = "subagent";
    static final String[] ALL = {ROUTER, COMPACTION, WEB_PLAN, VERIFY, SUBAGENT};
    static final String SETTING_KEY = "helpersOff";
    /** Trace skip reason of a helper the user switched off. */
    static final String USER_OFF = "user_off";

    private HelperSwitches() { }

    static boolean known(String id) {
        for (String helper : ALL) if (helper.equals(id)) return true;
        return false;
    }

    /** Stored JSON array → known ids in canonical order; anything unreadable means all helpers on. */
    static Set<String> parse(String stored) {
        Set<String> found = new LinkedHashSet<>();
        try {
            JSONArray values = new JSONArray(stored == null || stored.trim().isEmpty() ? "[]" : stored);
            for (int i = 0; i < values.length(); i++) found.add(values.optString(i, ""));
        } catch (Exception broken) { return Collections.emptySet(); }
        return canonical(found);
    }

    /** Known ids of {@code ids} in the order of {@link #ALL}. */
    static Set<String> canonical(Set<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids != null) for (String helper : ALL) if (ids.contains(helper)) out.add(helper);
        return Collections.unmodifiableSet(out);
    }

    static JSONArray toJson(Set<String> off) {
        JSONArray out = new JSONArray();
        for (String helper : canonical(off)) out.put(helper);
        return out;
    }
}
