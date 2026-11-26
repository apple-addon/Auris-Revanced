package com.maxmpz.audioplayer.plugin;

/** Original PAWS native-plugin value object. */
public final class NativePluginInfo {
    public static final NativePluginInfo NULL = new NativePluginInfo("", -1, 0, "");

    public final int flags;
    public final int id;
    public final String name;
    public final String uniq_name;

    public NativePluginInfo(String uniqName, int id, int flags, String name) {
        this.uniq_name = uniqName;
        this.id = id;
        this.flags = flags;
        this.name = name;
    }

    public String getPackage() {
        return uniq_name.substring(0, uniq_name.indexOf('/'));
    }

    public boolean isValid() {
        return this != NULL && uniq_name != null && !uniq_name.isEmpty() && id >= 0;
    }

    @Override
    public String toString() {
        return "NativePluginInfo" + Integer.toHexString(hashCode())
            + "{uniq_name='" + uniq_name + "', name='" + name + "', id=" + id
            + ", flags=0x" + Integer.toHexString(flags) + "}";
    }
}
