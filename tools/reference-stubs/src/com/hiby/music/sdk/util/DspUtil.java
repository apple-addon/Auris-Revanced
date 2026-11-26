package com.hiby.music.sdk.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Original HiBy DSP JNI contract. Method names/signatures are ABI-visible. */
public class DspUtil {
    private static DspUtil mDspUtil;

    public List<String> activatedDsp = new ArrayList<>();
    public List<String> list = new ArrayList<>();
    public List<String> showNameList = new ArrayList<>();

    private DspUtil() {}

    public static synchronized DspUtil getInstance() {
        if (mDspUtil == null) {
            mDspUtil = new DspUtil();
            mDspUtil.init();
        }
        return mDspUtil;
    }

    public static void addList(String value) {
        getInstance().list.add(value);
    }

    public static void addShowNameList(String value) {
        getInstance().showNameList.add(value);
    }

    public static void cleanActivatedDspList() {
        getInstance().activatedDsp.clear();
    }

    public static void getActivatedDsp(String value) {
        getInstance().activatedDsp.add(value);
    }

    public native String GetDspDislayName(int id, String name, int language);
    public native String GetDspInfo(int id, String name);
    public native double GetDspInfoDouble(int id, String name);
    public native float GetDspInfoFloat(int id, String name);
    public native int GetDspInfoInt(int id, String name);
    public native HashMap GetDsplayTableString(int id, String name, int language);
    public native int OnDspAdd(String name);
    public native void OnDspChainClear();
    public native void OnDspChainLoad(String path);
    public native void OnDspChainSave(String path);
    public native void OnDspChainUpgrade(String from, String to);
    public native void OnDspMove(int from, int to);
    public native void OnDspReload();
    public native void OnDspRemove(int id);
    public native void SetDspInfo(int id, String name, String value, int flags);
    public native void SetDspInfoDouble(int id, String name, double value);
    public native void SetDspInfoFloat(int id, String name, float value);
    public native void SetDspInfoInt(int id, String name, int value);
    public native void init();
    public native void onLoadPlugin(String path);
    public native int onUnloadPlugin(String path);
}
