package Aquin.lubie.remote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets

private const val UDP_BUFFER_SIZE = 8192

/**
 * Summary: Listens for gaze JSON payloads sent over UDP.
 * @param onSample Callback invoked for each successfully parsed gaze sample.
 * @param onError Callback invoked when the receiver fails unexpectedly.
 * @return Background UDP receiver.
 */
class UdpGazeReceiver(
    private val onSample: (GazeSample) -> Unit,
    private val onError: (String) -> Unit,
) {
    @Volatile
    private var running: Boolean = false
    private var socket: DatagramSocket? = null
    private var worker: Thread? = null

    /**
     * Summary: Starts listening on the requested UDP port.
     * @param port UDP port to bind.
     * @return Unit.
     */
    fun start(port: Int) {
        stop()
        running = true
        worker = Thread(
            {
                try {
                    DatagramSocket(port).use { datagramSocket ->
                        socket = datagramSocket
                        datagramSocket.soTimeout = 1000
                        val buffer = ByteArray(UDP_BUFFER_SIZE)
                        while (running) {
                            try {
                                val packet = DatagramPacket(buffer, buffer.size)
                                datagramSocket.receive(packet)
                                val payload = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                                val sample = GazeSampleJson.parseOrNull(payload) ?: continue
                                onSample(sample)
                            } catch (_: java.net.SocketTimeoutException) {
                                continue
                            }
                        }
                    }
                } catch (_: SocketException) {
                    if (running) {
                        onError("UDP receiver stopped unexpectedly")
                    }
                } catch (_: Throwable) {
                    if (running) {
                        onError("Failed to receive UDP gaze data")
                    }
                } finally {
                    socket = null
                    running = false
                }
            },
            "udp-gaze-receiver",
        ).also { it.start() }
    }

    /**
     * Summary: Stops the UDP receiver and releases the socket.
     * @param none No parameters.
     * @return Unit.
     */
    fun stop() {
        running = false
        socket?.close()
        socket = null
        worker = null
    }
}
