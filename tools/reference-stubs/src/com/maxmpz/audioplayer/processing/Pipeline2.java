package com.maxmpz.audioplayer.processing;

import java.nio.ByteBuffer;

/** Original PAWS pipeline JNI contract with thin handle-based entry points. */
public final class Pipeline2 {
    public Pipeline2() {}

    private final native int native_build(
        long handle, ByteBuffer commandBuffer, ByteBuffer stateBuffer, int flags
    );
    private final native long native_create();
    private final native int native_get_int_param(long handle, int domain, int key, int arg);
    private final native long native_get_long_param(long handle, int domain, int key, long arg);
    private final native int native_get_position_ms(long handle, int stream);
    private final native int native_get_state(long handle);
    private final native int native_pause(long handle, boolean flush);
    private final native int native_pause_hard(long handle);
    private final native void native_post_cmd(long handle, int command, int arg);
    private final native int native_release(long handle);
    private final native int native_request_open(
        long handle, String path, int arg0, byte[] data, int arg1, int arg2, int arg3
    );
    private final native int native_request_reconfigure(long handle);
    private final native int native_resume(long handle, boolean arg);
    private final native boolean native_route_msg(
        long handle, int arg0, int arg1, int arg2, int arg3
    );
    private final native boolean native_route_msg(
        long handle, int arg0, int arg1, int arg2, int arg3, int arg4
    );
    private final native boolean native_route_msg(
        long handle, int arg0, int arg1, int arg2, int arg3, int arg4, int arg5, int arg6
    );
    private final native boolean native_route_msg(
        long handle,
        int arg0,
        int arg1,
        int arg2,
        int arg3,
        int arg4,
        int arg5,
        int arg6,
        int arg7
    );
    private final native boolean native_route_msg(
        long handle,
        int arg0,
        int arg1,
        int arg2,
        int arg3,
        int arg4,
        int arg5,
        int arg6,
        int arg7,
        int arg8
    );
    private final native boolean native_route_msg(long handle, byte[] message, int length);
    private final native boolean native_route_msg(long handle, int[] message, int length);
    private final native void native_seek(long handle, int position, int arg0, int arg1);
    private final native void native_set_end_event_position_ms(long handle, int stream, int position);
    private final native int native_sleep_until_java_cmd(long handle);
    private final native int native_stop(long handle, boolean arg0, boolean arg1);
    private final native void native_test_mode(long handle);

    public long create() {
        return native_create();
    }

    public int build(
        long handle, ByteBuffer commandBuffer, ByteBuffer stateBuffer, int flags
    ) {
        return native_build(handle, commandBuffer, stateBuffer, flags);
    }

    public int release(long handle) {
        return native_release(handle);
    }

    public boolean route(long handle, int arg0, int arg1, int arg2, int arg3) {
        return native_route_msg(handle, arg0, arg1, arg2, arg3);
    }

    public boolean route(long handle, int arg0, int arg1, int arg2, int arg3, int arg4) {
        return native_route_msg(handle, arg0, arg1, arg2, arg3, arg4);
    }

    public boolean route(
        long handle, int arg0, int arg1, int arg2, int arg3, int arg4, int arg5, int arg6
    ) {
        return native_route_msg(handle, arg0, arg1, arg2, arg3, arg4, arg5, arg6);
    }

    public boolean route(
        long handle,
        int arg0,
        int arg1,
        int arg2,
        int arg3,
        int arg4,
        int arg5,
        int arg6,
        int arg7
    ) {
        return native_route_msg(handle, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7);
    }

    public boolean route(
        long handle,
        int arg0,
        int arg1,
        int arg2,
        int arg3,
        int arg4,
        int arg5,
        int arg6,
        int arg7,
        int arg8
    ) {
        return native_route_msg(handle, arg0, arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
    }

    public boolean route(long handle, byte[] message, int length) {
        return native_route_msg(handle, message, length);
    }

    public boolean route(long handle, int[] message, int length) {
        return native_route_msg(handle, message, length);
    }
}
