package com.music.bitchord.playback.reference.paws

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Setup-time stubs for UI/message-bus callbacks which are irrelevant to native PCM processing. */
internal object PawsHostStubs {
    fun createMsgBusHost(hostClassLoader: ClassLoader): Any {
        val msgBusClass = hostClassLoader.loadClass("com.auris.widget.MsgBus")
        val msgBus = Proxy.newProxyInstance(
            hostClassLoader,
            arrayOf(msgBusClass),
            NoopInvocationHandler,
        )
        val hostClass = hostClassLoader.loadClass("ׅ/dw0")
        return Proxy.newProxyInstance(
            hostClassLoader,
            arrayOf(hostClass),
            MsgBusHostInvocationHandler(msgBus),
        )
    }

    private object NoopInvocationHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            defaultValue(method.returnType)
    }

    private class MsgBusHostInvocationHandler(private val msgBus: Any) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? =
            if (method.name == "getMsgBus") msgBus else defaultValue(method.returnType)
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}
