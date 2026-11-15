package org.telegram.ui.threads;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;

// THREADS: local-only thread storage (SharedPreferences, no server calls).
// One JSON array per thread, keyed by account + chat (dialog) id + parent message id.
public final class ThreadsStore {

    public static final class Reply {
        public final String text;
        public final int date;
        // THREADS: user id of who wrote the reply (stage 3). Threads saved before have none: the current user.
        public final long senderId;

        public Reply(String text, int date, long senderId) {
            this.text = text;
            this.date = date;
            this.senderId = senderId;
        }
    }

    private static final String PREFS = "threads_local";
    // Reply counts are read while drawing every chat message, so keep them in memory.
    private static final HashMap<String, Integer> countCache = new HashMap<>();
    // THREADS: participants are read while drawing too (the avatar stack), cached the same way.
    private static final HashMap<String, ArrayList<Long>> participantsCache = new HashMap<>();

    private ThreadsStore() {
    }

    private static String read(String k) {
        return prefs().getString(k, null);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(int account, long dialogId, int parentMessageId) {
        return account + "_" + dialogId + "_" + parentMessageId;
    }

    public static ArrayList<Reply> getReplies(int account, long dialogId, int parentMessageId) {
        final ArrayList<Reply> result = new ArrayList<>();
        final long self = UserConfig.getInstance(account).getClientUserId();
        final String json = read(key(account, dialogId, parentMessageId));
        if (json == null) {
            return result;
        }
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject o = array.getJSONObject(i);
                result.add(new Reply(o.optString("t"), o.optInt("d"), o.optLong("s", self))); // THREADS
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    public static int getCount(int account, long dialogId, int parentMessageId) {
        final String k = key(account, dialogId, parentMessageId);
        Integer count = countCache.get(k);
        if (count == null) {
            count = getReplies(account, dialogId, parentMessageId).size();
            countCache.put(k, count);
        }
        return count;
    }

    // THREADS: unique senders of the replies, most recent first (like a post's recent repliers).
    public static ArrayList<Long> getParticipants(int account, long dialogId, int parentMessageId) {
        final String k = key(account, dialogId, parentMessageId);
        ArrayList<Long> participants = participantsCache.get(k);
        if (participants == null) {
            final ArrayList<Reply> replies = getReplies(account, dialogId, parentMessageId);
            final LinkedHashSet<Long> unique = new LinkedHashSet<>();
            for (int i = replies.size() - 1; i >= 0; i--) {
                unique.add(replies.get(i).senderId);
            }
            participants = new ArrayList<>(unique);
            participantsCache.put(k, participants);
        }
        return participants;
    }

    private static void save(String k, JSONArray array) {
        if (array.length() == 0) {
            prefs().edit().remove(k).apply();
        } else {
            prefs().edit().putString(k, array.toString()).apply();
        }
        countCache.put(k, array.length());
        participantsCache.remove(k); // THREADS
    }

    public static void deleteReply(int account, long dialogId, int parentMessageId, int index) {
        final String k = key(account, dialogId, parentMessageId);
        try {
            final String json = read(k);
            if (json == null) {
                return;
            }
            final JSONArray array = new JSONArray(json);
            if (index >= 0 && index < array.length()) {
                array.remove(index);
                save(k, array);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    // THREADS (stage 5): local mute switch of a thread (thread info screen). Stored on this device only.
    public static boolean isMuted(int account, long dialogId, int parentMessageId) {
        final String k = key(account, dialogId, parentMessageId);
        return prefs().getBoolean("mute_" + k, false);
    }

    public static void setMuted(int account, long dialogId, int parentMessageId, boolean muted) {
        final String k = key(account, dialogId, parentMessageId);
        prefs().edit().putBoolean("mute_" + k, muted).apply();
    }

    // THREADS: the parent message ids of every thread of a chat that has at least one reply.
    // Used by the chat's "Threads" menu item and list.
    public static ArrayList<Integer> getThreadIds(int account, long dialogId) {
        final ArrayList<Integer> ids = new ArrayList<>();
        final String prefix = account + "_" + dialogId + "_";
        final java.util.Set<String> keys = prefs().getAll().keySet();
        for (String k : keys) {
            if (!k.startsWith(prefix)) {
                continue;
            }
            try {
                final int id = Integer.parseInt(k.substring(prefix.length()));
                if (getCount(account, dialogId, id) > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignore) {
                // another setting stored with the same prefix: not a thread
            }
        }
        return ids;
    }

    public static boolean hasThreads(int account, long dialogId) {
        return !getThreadIds(account, dialogId).isEmpty();
    }

    // THREADS (stage 9b): the first-time tooltip on the reply panel's thread toggle is shown once per device.
    public static boolean isToggleHintShown() {
        return prefs().getBoolean("toggle_hint_shown", false);
    }

    public static void setToggleHintShown() {
        prefs().edit().putBoolean("toggle_hint_shown", true).apply();
    }

    public static Reply addReply(int account, long dialogId, int parentMessageId, String text) {
        return addReply(account, dialogId, parentMessageId, text, UserConfig.getInstance(account).getClientUserId());
    }

    // THREADS: any sender; the app itself only ever sends as the current user.
    public static Reply addReply(int account, long dialogId, int parentMessageId, String text, long senderId) {
        return addReply(account, dialogId, parentMessageId, new Reply(text, (int) (System.currentTimeMillis() / 1000), senderId));
    }

    private static Reply addReply(int account, long dialogId, int parentMessageId, Reply reply) {
        final String k = key(account, dialogId, parentMessageId);
        try {
            final String json = read(k);
            final JSONArray array = json == null ? new JSONArray() : new JSONArray(json);
            final JSONObject o = new JSONObject();
            o.put("t", reply.text);
            o.put("d", reply.date);
            o.put("s", reply.senderId); // THREADS
            array.put(o);
            save(k, array);
        } catch (Exception e) {
            FileLog.e(e);
        }
        return reply;
    }
}
