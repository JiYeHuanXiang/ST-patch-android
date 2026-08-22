package com.st.patch.common

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 接管心跳端口持有者（进程级单例）。
 * 绑定 OS 分配的空闲端口（bind 端口 0，避免与设备其它 app 固定端口冲突，如本机 8765 被
 * AffinityApps 占用），Termux 侧守卫脚本（见 TermuxBridge.startTermuxSt）轮询该端口：
 * app 进程一旦消失（划掉 recent / 崩溃 / 被系统杀），端口随进程关闭，脚本探测失败即 kill node，
 * 杜绝酒馆后台残留耗电 / 占用端口。
 * 生命周期由 TakeoverGuardService 前台服务保活；停止时 close()。
 */
object TakeoverBeacon {

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false

    /** 绑定空闲端口并启动 accept 循环；返回端口号。已开则返回现有端口。 */
    fun open(): Int {
        synchronized(this) {
            socket?.let { return it.localPort }
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress("127.0.0.1", 0)) // 0 = OS 分配空闲端口
            socket = s
            running = true
            thread(name = "takeover-beacon", isDaemon = true) {
                while (running && !Thread.currentThread().isInterrupted) {
                    try { s.accept()?.close() } catch (_: Exception) { if (!running) break }
                }
            }
            return s.localPort
        }
    }

    fun close() {
        synchronized(this) {
            running = false
            try { socket?.close() } catch (_: Exception) {}
            socket = null
        }
    }
}
