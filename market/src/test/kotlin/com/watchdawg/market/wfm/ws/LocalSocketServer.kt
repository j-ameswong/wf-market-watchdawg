package com.watchdawg.market.wfm.ws

import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

/** Small RFC 6455 peer, bound to loopback; tests exercise the real JDK handshake and framing. */
class LocalSocketServer(private val handshake: Boolean = true) : AutoCloseable {
    private val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
    private val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()
    private val peers = LinkedBlockingQueue<Peer>()
    val uri = URI("ws://127.0.0.1:${server.localPort}/socket")
    private val worker = Thread.ofVirtual().start {
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                sockets += socket
                Thread.ofVirtual().start {
                    val input = DataInputStream(socket.getInputStream())
                    val request = StringBuilder()
                    while (!request.endsWith("\r\n\r\n")) request.append(input.readUnsignedByte().toChar())
                    val headers = request.toString().lines().drop(1).filter { ':' in it }
                        .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
                    if (handshake) {
                        val key = headers.getValue("sec-websocket-key") + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                        val accept = Base64.getEncoder().encodeToString(
                            MessageDigest.getInstance("SHA-1").digest(key.toByteArray()),
                        )
                        socket.getOutputStream().write(
                            (
                                "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                    "Sec-WebSocket-Accept: $accept\r\nSec-WebSocket-Protocol: wfm\r\n\r\n"
                                ).toByteArray(),
                        )
                    }
                    peers.put(Peer(socket, input, headers))
                }
            } catch (_: java.net.SocketException) {
                break
            }
        }
    }

    fun peer(): Peer = checkNotNull(peers.poll(5, SECONDS)) { "no socket connection" }

    override fun close() {
        server.close()
        sockets.forEach { it.close() }
        worker.join(1000)
    }

    class Peer(private val socket: Socket, private val input: DataInputStream, val headers: Map<String, String>) {
        fun receive(): String {
            socket.soTimeout = 5000
            input.readUnsignedByte()
            val flags = input.readUnsignedByte()
            val size = when (val length = flags and 127) {
                126 -> input.readUnsignedShort()
                127 -> input.readLong().toInt()
                else -> length
            }
            val mask = if (flags and 128 != 0) input.readNBytes(4) else null
            val bytes = input.readNBytes(size)
            if (mask !=
                null
            ) {
                bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor mask[it % 4].toInt()).toByte() }
            }
            return String(bytes, UTF_8)
        }

        fun send(text: String, last: Boolean = true, continuation: Boolean = false) {
            val bytes = text.toByteArray(UTF_8)
            val out = java.io.DataOutputStream(socket.getOutputStream())
            out.writeByte((if (last) 128 else 0) or (if (continuation) 0 else 1))
            if (bytes.size < 126) {
                out.writeByte(bytes.size)
            } else {
                out.writeByte(126)
                out.writeShort(bytes.size)
            }
            out.write(bytes)
            out.flush()
        }

        fun close() = socket.close()
    }
}
