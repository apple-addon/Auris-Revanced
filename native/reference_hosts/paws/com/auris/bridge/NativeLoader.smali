.class public final Lcom/auris/bridge/NativeLoader;
.super Ljava/lang/Object;
.source "NativeLoader.smali"

.method private constructor <init>()V
    .locals 0
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method

.method public static load()V
    .locals 1
    const-string v0, "ffmpeg_neon"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    const-string v0, "auriscore"
    invoke-static {v0}, Ljava/lang/System;->loadLibrary(Ljava/lang/String;)V
    return-void
.end method
