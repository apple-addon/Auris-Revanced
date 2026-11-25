.class public final Lcom/hiby/music/sdk/bridge/NativeLoader;
.super Ljava/lang/Object;
.source "NativeLoader.smali"

.method private constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static load()V
    .locals 1
    const-string v0, "usb"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    const-string v0, "smartusb_jni"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    const-string v0, "hibyaudioutils"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    const-string v0, "hibyservice"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    return-void
.end method
