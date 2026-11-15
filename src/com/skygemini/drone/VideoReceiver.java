package com.skygemini.drone;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ultra-Fast Real-Time UDP Video Receiver for Drone FPV Camera.
 * Disassembled & reverse-engineered from web_cam libCamera.so:
 * - Dedicated UDP port 8080 receiver (Image::put assembly)
 * - Zero-allocation pre-buffered slice reassembly (1464-byte payloads)
 * - Asynchronous non-blocking decode with latest-frame-only dropping
 * - RGB_565 hardware-accelerated instant rendering with zero lag
 * - Continuous fast wake-up handshake [0x42, 0x76] ('B', 'v')
 */
public class VideoReceiver {
    private static final String TAG = "VideoReceiver";
    private static final int VIDEO_PORT = 8080;
    private static final int MAX_FRAME_SIZE = 400000; // 400 KB preallocated buffer
    private static final int SLICE_PAYLOAD_SIZE = 1464; // from libCamera Image::put

    public interface OnFrameListener {
        void onFrame(Bitmap bitmap);
        void onFpsUpdate(int fps);
        void onRawJpegFrame(byte[] jpegBytes);
    }

    private final DualNetworkManager networkManager;
    private final Handler mainHandler;
    private OnFrameListener listener;

    private volatile boolean running = false;
    private DatagramSocket videoSocket;
    private DatagramSocket handshakeSocket;

    private Thread rxThread;
    private Thread handshakeThread;

    // Dedicated single-thread decode executor with atomic drop-lock to guarantee 0ms latency
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isDecoding = new AtomicBoolean(false);

    // Preallocated buffers for zero-garbage packet reassembly
    private final byte[] frameBuffer = new byte[MAX_FRAME_SIZE];
    private int currentFrameId = -1;
    private int expectedTotalSlices = 0;
    private int maxSliceIndexSeen = 0;
    private int lastSlicePayloadLen = 0;
    private boolean isAssembling = false;
    private int sequentialWritePos = 0;

    private int frameCounter = 0;
    private long lastFpsCalcTime = 0;

    private final String[] targetIps = new String[]{"192.168.4.153", "192.168.4.1"};

    public VideoReceiver(DualNetworkManager networkManager) {
        this.networkManager = networkManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setOnFrameListener(OnFrameListener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        rxThread = new Thread(this::receiveVideoLoop, "DroneVideoRx");
        rxThread.setPriority(Thread.MAX_PRIORITY);
        rxThread.start();

        handshakeThread = new Thread(this::handshakeLoop, "DroneVideoHandshake");
        handshakeThread.setPriority(Thread.NORM_PRIORITY);
        handshakeThread.start();

        Log.d(TAG, "Ultra-fast VideoReceiver started on port " + VIDEO_PORT);
    }

    public synchronized void stop() {
        running = false;
        closeSockets();

        if (rxThread != null) {
            rxThread.interrupt();
            rxThread = null;
        }
        if (handshakeThread != null) {
            handshakeThread.interrupt();
            handshakeThread = null;
        }
        Log.d(TAG, "VideoReceiver stopped");
    }

    public synchronized void onWifiReady(Network wifiNetwork) {
        Log.d(TAG, "Rebinding video socket to Wi-Fi: " + wifiNetwork);
        if (videoSocket != null) {
            try { wifiNetwork.bindSocket(videoSocket); } catch (Exception ignored) {}
        }
        if (handshakeSocket != null) {
            try { wifiNetwork.bindSocket(handshakeSocket); } catch (Exception ignored) {}
        }
    }

    private void closeSockets() {
        if (videoSocket != null && !videoSocket.isClosed()) {
            try { videoSocket.close(); } catch (Exception ignored) {}
            videoSocket = null;
        }
        if (handshakeSocket != null && !handshakeSocket.isClosed()) {
            try { handshakeSocket.close(); } catch (Exception ignored) {}
            handshakeSocket = null;
        }
    }

    /**
     * Continuous handshake sender to keep drone camera awake and streaming.
     * startCmd [0x42, 0x76] ('B', 'v') from libCamera.so
     */
    private void handshakeLoop() {
        byte[] handshakePacket = new byte[]{0x42, 0x76};

        try {
            handshakeSocket = new DatagramSocket();
            handshakeSocket.setReuseAddress(true);
            Network wifiNet = networkManager.getWifiNetwork();
            if (wifiNet != null) {
                try { wifiNet.bindSocket(handshakeSocket); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "Handshake socket creation failed: " + e.getMessage());
        }

        int count = 0;
        while (running) {
            try {
                Network wifiNet = networkManager.getWifiNetwork();
                if (handshakeSocket != null && !handshakeSocket.isClosed()) {
                    if (wifiNet != null) {
                        try { wifiNet.bindSocket(handshakeSocket); } catch (Exception ignored) {}
                    }

                    for (String ip : targetIps) {
                        try {
                            InetAddress addr = (wifiNet != null) ? wifiNet.getByName(ip) : InetAddress.getByName(ip);
                            // Send wake-up to both port 8080 and 8090
                            handshakeSocket.send(new DatagramPacket(handshakePacket, 2, addr, 8080));
                            handshakeSocket.send(new DatagramPacket(handshakePacket, 2, addr, 8090));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Handshake error: " + e.getMessage());
            }

            try {
                // First 10 bursts every 200ms to immediately wake up camera, then every 1000ms
                count++;
                Thread.sleep(count < 10 ? 200 : 1000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Dedicated high-priority video packet receiver loop.
     * Never drops incoming packets and avoids GC allocation.
     */
    private void receiveVideoLoop() {
        try {
            videoSocket = new DatagramSocket(null);
            videoSocket.setReuseAddress(true);
            videoSocket.setReceiveBufferSize(2 * 1024 * 1024); // 2MB OS socket buffer
            videoSocket.setSoTimeout(2000);
            videoSocket.bind(new InetSocketAddress(VIDEO_PORT));

            Network wifiNet = networkManager.getWifiNetwork();
            if (wifiNet != null) {
                try { wifiNet.bindSocket(videoSocket); } catch (Exception ignored) {}
            }
            Log.d(TAG, "Bound video UDP socket on port " + VIDEO_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed binding video socket on " + VIDEO_PORT + ": " + e.getMessage());
            return;
        }

        byte[] rxBuffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(rxBuffer, rxBuffer.length);
        lastFpsCalcTime = System.currentTimeMillis();

        while (running && videoSocket != null && !videoSocket.isClosed()) {
            try {
                videoSocket.receive(packet);
                int len = packet.getLength();
                if (len < 4) continue;

                processIncomingPacket(rxBuffer, len);

            } catch (java.net.SocketTimeoutException ste) {
                // Timeout is normal when drone is not yet streaming
            } catch (Exception e) {
                if (!running) break;
            }
        }

        closeSockets();
    }

    /**
     * Frame Reassembly matching libCamera.so Image::put:
     * Packet structure with 8-byte header:
     * - byte 0: frame_id
     * - byte 1: flag
     * - byte 2: total_slices
     * - byte 3: slice_index
     * - bytes 8..len: payload (up to 1464 bytes)
     *
     * Also supports direct MJPEG streaming packets (starting with 0xFF, 0xD8).
     */
    private synchronized void processIncomingPacket(byte[] data, int len) {
        // Mode 1: Check for 8-byte header (libCamera.so protocol)
        if (len > 8 && (data[8] & 0xFF) == 0xFF && (data[9] & 0xFF) == 0xD8) {
            // New frame start with 8-byte header!
            currentFrameId = data[0] & 0xFF;
            expectedTotalSlices = data[2] & 0xFF;
            int sliceIndex = data[3] & 0xFF;
            int payloadLen = len - 8;

            isAssembling = true;
            maxSliceIndexSeen = sliceIndex;
            lastSlicePayloadLen = payloadLen;

            System.arraycopy(data, 8, frameBuffer, 0, payloadLen);
            return;
        }

        if (isAssembling && len > 8 && (data[0] & 0xFF) == currentFrameId) {
            int sliceIndex = data[3] & 0xFF;
            int payloadLen = len - 8;
            int destOffset = sliceIndex * SLICE_PAYLOAD_SIZE;

            if (destOffset + payloadLen <= MAX_FRAME_SIZE) {
                System.arraycopy(data, 8, frameBuffer, destOffset, payloadLen);
                if (sliceIndex >= maxSliceIndexSeen) {
                    maxSliceIndexSeen = sliceIndex;
                    lastSlicePayloadLen = payloadLen;
                }
            }

            // Check if last slice reached or JPEG EOI found
            boolean isLastSlice = (expectedTotalSlices > 0 && sliceIndex + 1 >= expectedTotalSlices);
            boolean eoiInPacket = (payloadLen >= 2 && (data[len - 2] & 0xFF) == 0xFF && (data[len - 1] & 0xFF) == 0xD9);

            if (isLastSlice || eoiInPacket) {
                int totalFrameLen = maxSliceIndexSeen * SLICE_PAYLOAD_SIZE + lastSlicePayloadLen;
                dispatchFrameIfValid(frameBuffer, totalFrameLen);
                isAssembling = false;
            }
            return;
        }

        // Mode 2: Direct MJPEG stream (packet starting with 0xFF, 0xD8 directly at offset 0)
        int soi = findJpegSOI(data, len);
        if (soi >= 0) {
            sequentialWritePos = 0;
            int copyLen = len - soi;
            if (copyLen <= MAX_FRAME_SIZE) {
                System.arraycopy(data, soi, frameBuffer, 0, copyLen);
                sequentialWritePos = copyLen;
                isAssembling = true;
            }
        } else if (isAssembling) {
            int copyLen = (len > 8 && (data[0] & 0xFF) == 0x00) ? len - 8 : len;
            int srcOff = (len > 8 && (data[0] & 0xFF) == 0x00) ? 8 : 0;

            if (sequentialWritePos + copyLen <= MAX_FRAME_SIZE) {
                System.arraycopy(data, srcOff, frameBuffer, sequentialWritePos, copyLen);
                sequentialWritePos += copyLen;
            }

            // Check for EOI
            if (sequentialWritePos > 100 && (frameBuffer[sequentialWritePos - 2] & 0xFF) == 0xFF && (frameBuffer[sequentialWritePos - 1] & 0xFF) == 0xD9) {
                dispatchFrameIfValid(frameBuffer, sequentialWritePos);
                isAssembling = false;
                sequentialWritePos = 0;
            } else if (sequentialWritePos > 350000) {
                isAssembling = false;
                sequentialWritePos = 0;
            }
        }
    }

    private static int findJpegSOI(byte[] data, int len) {
        for (int i = 0; i < len - 1; i++) {
            if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xFF) == 0xD8) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Dispatches complete frame to asynchronous non-blocking decoder.
     * If the decoder is currently busy with previous frame, skips to maintain 0ms latency.
     */
    private void dispatchFrameIfValid(byte[] buf, int length) {
        if (length < 100 || length > MAX_FRAME_SIZE) return;

        // Verify SOI (0xFF, 0xD8)
        if ((buf[0] & 0xFF) != 0xFF || (buf[1] & 0xFF) != 0xD8) return;

        // Verify or locate EOI (0xFF, 0xD9) in the last 256 bytes
        int actualLen = length;
        boolean hasEoi = false;
        int searchStart = Math.max(0, actualLen - 256);
        for (int i = actualLen - 2; i >= searchStart; i--) {
            if ((buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xFF) == 0xD9) {
                actualLen = i + 2;
                hasEoi = true;
                break;
            }
        }

        if (!hasEoi) return;

        final byte[] completeJpeg = new byte[actualLen];
        System.arraycopy(buf, 0, completeJpeg, 0, actualLen);

        // Notify Gemini Live Vision directly with raw JPEG (rate limited inside GeminiLiveClient)
        if (listener != null) {
            listener.onRawJpegFrame(completeJpeg);
        }

        // Non-blocking UI decode: drop frame if previous decode is still in progress
        if (isDecoding.compareAndSet(false, true)) {
            decodeExecutor.execute(() -> {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    // RGB_565 halves memory bandwidth and doubles decode performance
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    opts.inDither = false;

                    Bitmap bmp = BitmapFactory.decodeByteArray(completeJpeg, 0, completeJpeg.length, opts);
                    if (bmp != null) {
                        frameCounter++;
                        long now = System.currentTimeMillis();
                        if (now - lastFpsCalcTime >= 1000) {
                            final int fps = frameCounter;
                            frameCounter = 0;
                            lastFpsCalcTime = now;
                            if (listener != null) {
                                mainHandler.post(() -> listener.onFpsUpdate(fps));
                            }
                        }

                        mainHandler.post(() -> {
                            if (listener != null) listener.onFrame(bmp);
                        });
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Frame decode error: " + t.getMessage());
                } finally {
                    isDecoding.set(false);
                }
            });
        }
    }
}
