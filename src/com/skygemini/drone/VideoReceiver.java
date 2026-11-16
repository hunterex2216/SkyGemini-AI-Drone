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
 * Reverse-Engineered from companion app libCamera.so & Image::put:
 * 1. Single UDP Socket bound to local port 8080 (ensuring drone streams back to port 8080).
 * 2. Handshake startCmd [0x42, 0x76] ('B', 'v') sent directly from port 8080 to drone 192.168.4.153:8080.
 * 3. Drone handshake ACK [0x55, 0x00, type, ... 0x99] detection.
 * 4. Zero-allocation slice reassembly (1464-byte payloads from Image::put).
 * 5. Direct MJPEG fallback detection.
 * 6. Non-blocking RGB_565 hardware decode with latest-frame-only dropping (0ms latency).
 */
public class VideoReceiver {
    private static final String TAG = "VideoReceiver";
    private static final int VIDEO_PORT = 8080;
    private static final int MAX_FRAME_SIZE = 500000; // 500 KB frame buffer
    private static final int SLICE_PAYLOAD_SIZE = 1464; // from libCamera Image::put

    public interface OnFrameListener {
        void onFrame(Bitmap bitmap);
        void onFpsUpdate(int fps);
        void onCameraStateChanged(boolean connected);
        void onRawJpegFrame(byte[] jpegBytes);
    }

    private final DualNetworkManager networkManager;
    private final Handler mainHandler;
    private OnFrameListener listener;

    private volatile boolean running = false;
    private volatile boolean cameraConnected = false;
    private volatile long lastFrameTimestamp = 0;

    // Single unified DatagramSocket for both handshake and video packet reception
    private DatagramSocket unifiedSocket;
    private Thread rxThread;
    private Thread handshakeThread;

    // Dedicated single-thread decode executor with atomic drop-lock to guarantee 0ms latency
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean isDecoding = new AtomicBoolean(false);

    // Latest decoded bitmap for photo snapshot & video recording
    private volatile Bitmap latestFrameBitmap = null;

    // Preallocated buffer for packet reassembly
    private final byte[] frameBuffer = new byte[MAX_FRAME_SIZE];
    private int currentFrameId = -1;
    private int expectedTotalSlices = 0;
    private int maxSliceIndexSeen = 0;
    private int lastSlicePayloadLen = 0;
    private boolean isAssembling = false;
    private int sequentialWritePos = 0;

    private int frameCounter = 0;
    private long lastFpsCalcTime = 0;
    private long lastUiFrameTime = 0;
    private long lastGeminiFrameTime = 0;

    private final String[] targetIps = new String[]{"192.168.4.153", "192.168.4.1"};
    private volatile InetAddress[] cachedTargetAddresses = null;

    public VideoReceiver(DualNetworkManager networkManager) {
        this.networkManager = networkManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
        resolveTargetAddresses(null);
    }

    private void resolveTargetAddresses(Network wifiNet) {
        try {
            InetAddress[] addrs = new InetAddress[targetIps.length];
            for (int i = 0; i < targetIps.length; i++) {
                try {
                    addrs[i] = (wifiNet != null) ? wifiNet.getByName(targetIps[i]) : InetAddress.getByName(targetIps[i]);
                } catch (Exception ignored) {
                    addrs[i] = InetAddress.getByName(targetIps[i]);
                }
            }
            cachedTargetAddresses = addrs;
        } catch (Exception ignored) {}
    }

    public void setOnFrameListener(OnFrameListener listener) {
        this.listener = listener;
    }

    public Bitmap getLatestBitmap() {
        return latestFrameBitmap;
    }

    public boolean isCameraStreaming() {
        return cameraConnected && (System.currentTimeMillis() - lastFrameTimestamp < 3000);
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        rxThread = new Thread(this::receiveAndProcessLoop, "DroneVideoRx");
        rxThread.setPriority(Thread.MAX_PRIORITY);
        rxThread.start();

        handshakeThread = new Thread(this::handshakeLoop, "DroneVideoHandshake");
        handshakeThread.setPriority(Thread.NORM_PRIORITY);
        handshakeThread.start();

        Log.d(TAG, "VideoReceiver started on port " + VIDEO_PORT);
    }

    public synchronized void stop() {
        running = false;
        cameraConnected = false;
        closeSocket();

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
        Log.d(TAG, "Wi-Fi ready, binding video socket: " + wifiNetwork);
        if (unifiedSocket != null && !unifiedSocket.isClosed()) {
            try {
                wifiNetwork.bindSocket(unifiedSocket);
                resolveTargetAddresses(wifiNetwork);
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind socket to Wi-Fi: " + e.getMessage());
            }
        }
    }

    private synchronized void closeSocket() {
        if (unifiedSocket != null && !unifiedSocket.isClosed()) {
            try { unifiedSocket.close(); } catch (Exception ignored) {}
            unifiedSocket = null;
        }
    }

    /**
     * Handshake sender loop: Sends startCmd [0x42, 0x76] ('B', 'v')
     * DIRECTLY through unifiedSocket (bound to port 8080).
     * This guarantees the drone sees source port = 8080 and replies back to port 8080!
     */
    private void handshakeLoop() {
        byte[] handshakePacket = new byte[]{0x42, 0x76};

        int count = 0;
        while (running) {
            try {
                if (unifiedSocket != null && !unifiedSocket.isClosed()) {
                    InetAddress[] addrs = cachedTargetAddresses;
                    if (addrs != null) {
                        for (InetAddress addr : addrs) {
                            if (addr == null) continue;
                            try {
                                DatagramPacket p = new DatagramPacket(handshakePacket, handshakePacket.length, addr, VIDEO_PORT);
                                unifiedSocket.send(p);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Handshake send error: " + e.getMessage());
            }

            try {
                count++;
                // Quick burst initially (every 150ms for first 15 bursts), then every 800ms keepalive
                Thread.sleep(count < 15 ? 150 : 800);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * Unified reception loop on local port 8080.
     */
    private void receiveAndProcessLoop() {
        try {
            unifiedSocket = new DatagramSocket(null);
            unifiedSocket.setReuseAddress(true);
            unifiedSocket.setReceiveBufferSize(2 * 1024 * 1024); // 2MB kernel buffer
            unifiedSocket.setSoTimeout(1500);
            unifiedSocket.bind(new InetSocketAddress(VIDEO_PORT));

            Network wifiNet = networkManager.getWifiNetwork();
            if (wifiNet != null) {
                try { wifiNet.bindSocket(unifiedSocket); } catch (Exception ignored) {}
            }
            Log.d(TAG, "Bound unified video socket on port " + VIDEO_PORT);
        } catch (Exception e) {
            Log.e(TAG, "Failed binding video UDP socket on " + VIDEO_PORT + ": " + e.getMessage());
            return;
        }

        byte[] rxBuffer = new byte[4096];
        DatagramPacket packet = new DatagramPacket(rxBuffer, rxBuffer.length);
        lastFpsCalcTime = System.currentTimeMillis();

        while (running && unifiedSocket != null && !unifiedSocket.isClosed()) {
            try {
                unifiedSocket.receive(packet);
                int len = packet.getLength();
                if (len < 2) continue;

                processIncomingPacket(rxBuffer, len);

            } catch (java.net.SocketTimeoutException ste) {
                // Check if camera was streaming but timed out
                if (cameraConnected && (System.currentTimeMillis() - lastFrameTimestamp > 3000)) {
                    cameraConnected = false;
                    notifyCameraState(false);
                }
            } catch (Exception e) {
                if (!running) break;
            }
        }

        closeSocket();
    }

    /**
     * Process incoming UDP packet matching libCamera.so logic.
     */
    private synchronized void processIncomingPacket(byte[] data, int len) {
        // 1. Handshake response check: [0x55, ..., 0x99]
        if (len == 8 && (data[0] & 0xFF) == 0x55 && (data[7] & 0xFF) == 0x99) {
            int camType = data[2] & 0xFF;
            Log.d(TAG, "Drone camera handshake ACK received! Camera type: " + camType);
            if (!cameraConnected) {
                cameraConnected = true;
                notifyCameraState(true);
            }
            return;
        }

        // 2. libCamera.so protocol: 8-byte header
        // byte 0: frame_id, byte 2: total_slices, byte 3: slice_index, bytes 8..len: JPEG payload
        if (len > 8 && (data[8] & 0xFF) == 0xFF && (data[9] & 0xFF) == 0xD8) {
            // New frame start with SOI at byte 8
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

            boolean isLastSlice = (expectedTotalSlices > 0 && sliceIndex + 1 >= expectedTotalSlices);
            boolean eoiInPacket = (payloadLen >= 2 && (data[len - 2] & 0xFF) == 0xFF && (data[len - 1] & 0xFF) == 0xD9);

            if (isLastSlice || eoiInPacket) {
                int totalFrameLen = maxSliceIndexSeen * SLICE_PAYLOAD_SIZE + lastSlicePayloadLen;
                dispatchFrameIfValid(frameBuffer, totalFrameLen);
                isAssembling = false;
            }
            return;
        }

        // 3. Fallback: Direct MJPEG stream (packet starting with 0xFF, 0xD8 at offset 0)
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
            } else if (sequentialWritePos > 400000) {
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

    private void dispatchFrameIfValid(byte[] buf, int length) {
        if (length < 100 || length > MAX_FRAME_SIZE) return;

        // Verify SOI
        if ((buf[0] & 0xFF) != 0xFF || (buf[1] & 0xFF) != 0xD8) return;

        // Find EOI in last 256 bytes
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

        long now = System.currentTimeMillis();
        lastFrameTimestamp = now;
        if (!cameraConnected) {
            cameraConnected = true;
            notifyCameraState(true);
        }

        // Throttle UI decode to a stable ~25 FPS (40ms interval) to completely prevent GC lag
        boolean shouldDecodeForUi = (now - lastUiFrameTime >= 40) && isDecoding.compareAndSet(false, true);
        boolean shouldSendToGemini = (listener != null) && (now - lastGeminiFrameTime >= 700);

        if (!shouldDecodeForUi && !shouldSendToGemini) {
            return; // Zero allocations, fast drop!
        }

        final byte[] completeJpeg = new byte[actualLen];
        System.arraycopy(buf, 0, completeJpeg, 0, actualLen);

        // Feed Gemini Live Vision with raw JPEG bytes only when scheduled (every 700ms)
        if (shouldSendToGemini) {
            lastGeminiFrameTime = now;
            listener.onRawJpegFrame(completeJpeg);
        }

        // Non-blocking UI decode
        if (shouldDecodeForUi) {
            lastUiFrameTime = now;
            decodeExecutor.execute(() -> {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565; // Halves memory bandwidth, doubles decode speed
                    opts.inDither = false;

                    Bitmap bmp = BitmapFactory.decodeByteArray(completeJpeg, 0, completeJpeg.length, opts);
                    if (bmp != null) {
                        latestFrameBitmap = bmp;
                        frameCounter++;
                        long t = System.currentTimeMillis();
                        if (t - lastFpsCalcTime >= 1000) {
                            final int fps = frameCounter;
                            frameCounter = 0;
                            lastFpsCalcTime = t;
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

    private void notifyCameraState(boolean connected) {
        if (listener != null) {
            mainHandler.post(() -> listener.onCameraStateChanged(connected));
        }
    }
}
