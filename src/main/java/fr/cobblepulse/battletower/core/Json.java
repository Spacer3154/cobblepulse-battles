package fr.cobblepulse.battletower.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Instance Gson partagee + copie profonde par aller-retour JSON. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
    public static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    public static <T> T copy(T obj, Class<T> type) {
        if (obj == null) return null;
        return COMPACT.fromJson(COMPACT.toJson(obj), type);
    }
}
