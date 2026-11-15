package com.skygemini.drone;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DroneController matches the companion app (web_cam libCamera.so & BaseCmd) 100%:
 * 1. Byte escaping: IBaseCmd_RightData (0x66 or 0x99 escaped to val + 1)
 * 2. XOR checksum with bit inversion (sum ^ 0xFF)
 * 3. 25Hz transmission loop targeting 192.168.4.153:8090 and 192.168.4.153:8080
 * 4. Dual packet protocol: 8-byte frame & 20-byte IBaseCmdNew frame
 */
public class DroneController {
    private static final String TAG = "DroneController";

    private final DualNetworkManager networkManager;
    private final Handler mainHandler;

    private DatagramSocket udpSocket;
    private ScheduledExecutorService executor;

    // Flight axes (0 - 255, 128 = neutral)
    public int roll = 128;
    public int pitch = 128;
    public int throttle = 0;
    public int yaw = 128;

    // Command Flags
    private boolean flagTakeoff = false;
    private boolean flagLand = false;
    private boolean flagEmergency = false;
    private boolean flagFlip = false;
    private boolean flagHeadless = false;
    private boolean flagCalibrate = false;
    private boolean isArmed = false;

    // Pulse cycles remaining (40ms each)
    private int pulseTakeoff = 0;
    private int pulseLand = 0;
    private int pulseEmergency = 0;
    private int pulseFlip = 0;
    private int pulseCalibrate = 0;

    private final String[] targetIps = new String[]{"192.168.4.153", "192.168.4.1"};
    private final int[] targetPorts = new int[]{8090, 8080};

    public DroneController(DualNetworkManager networkManager) {
        this.networkManager = networkManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized void start() {
        if (executor != null && !executor.isShutdown()) return;

        try {
            udpSocket = new DatagramSocket();
            Network wifiNet = networkManager.getWifiNetwork();
            if (wifiNet != null) {
                wifiNet.bindSocket(udpSocket);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating command socket: " + e.getMessage());
        }

        executor = Executors.newSingleThreadScheduledExecutor();
        // 25Hz = 40ms interval (identical to BaseCmd dealWithStart / dealWithResume)
        executor.scheduleAtFixedRate(this::tick, 0, 40, TimeUnit.MILLISECONDS);
        Log.d(TAG, "DroneController 25Hz loop started.");
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (udpSocket != null && !udpSocket.isClosed()) {
            udpSocket.close();
            udpSocket = null;
        }
        resetControls();
        throttle = 0;
        Log.d(TAG, "DroneController stopped.");
    }

    public synchronized void onWifiReady(Network wifiNetwork) {
        Log.d(TAG, "Wi-Fi is ready, rebinding command socket to: " + wifiNetwork);
        if (udpSocket != null && !udpSocket.isClosed()) {
            try {
                wifiNetwork.bindSocket(udpSocket);
            } catch (Exception e) {
                Log.w(TAG, "Failed rebinding cmd socket: " + e.getMessage());
            }
        }
    }

    /**
     * Exact byte escaping from web_cam IBaseCmd_RightData:
     * If byte is 0x66 (header) or 0x99 (tail), increment by 1
     * to prevent frame delimiter corruption!
     */
    private static int escapeByte(int val) {
        val &= 0xFF;
        if (val == 0x66 || val == 0x99) {
            val++;
        }
        return val & 0xFF;
    }

    private void tick() {
        Network wifiNet = networkManager.getWifiNetwork();
        if (wifiNet != null && udpSocket != null) {
            try { wifiNet.bindSocket(udpSocket); } catch (Exception ignored) {}
        }

        // Handle pulses
        if (pulseTakeoff > 0) {
            flagTakeoff = true;
            pulseTakeoff--;
            if (pulseTakeoff == 0) flagTakeoff = false;
        }
        if (pulseLand > 0) {
            flagLand = true;
            pulseLand--;
            if (pulseLand == 0) flagLand = false;
        }
        if (pulseEmergency > 0) {
            flagEmergency = true;
            pulseEmergency--;
            if (pulseEmergency == 0) flagEmergency = false;
        }
        if (pulseFlip > 0) {
            flagFlip = true;
            pulseFlip--;
            if (pulseFlip == 0) flagFlip = false;
        }
        if (pulseCalibrate > 0) {
            flagCalibrate = true;
            pulseCalibrate--;
            if (pulseCalibrate == 0) flagCalibrate = false;
        }

        // Build both frames
        byte[] pkt8 = buildPacket8();
        byte[] pkt20 = buildPacket20();

        // Transmit via UDP to 192.168.4.153:8090 and 8080
        if (udpSocket != null && !udpSocket.isClosed()) {
            for (String ip : targetIps) {
                for (int port : targetPorts) {
                    try {
                        InetAddress addr = (wifiNet != null) ? wifiNet.getByName(ip) : InetAddress.getByName(ip);
                        DatagramPacket p8 = new DatagramPacket(pkt8, pkt8.length, addr, port);
                        DatagramPacket p20 = new DatagramPacket(pkt20, pkt20.length, addr, port);
                        udpSocket.send(p8);
                        udpSocket.send(p20);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    /**
     * Classic 8-byte command packet:
     * [0x66, Roll, Pitch, Throttle, Yaw, Flags, Checksum, 0x99]
     */
    private byte[] buildPacket8() {
        int bRoll = escapeByte(roll);
        int bPitch = escapeByte(pitch);
        int bThrot = escapeByte(throttle);
        int bYaw = escapeByte(yaw);

        int flags = 0;
        if (flagTakeoff)   flags |= 0x01;
        if (flagLand)      flags |= 0x02;
        if (flagEmergency) flags |= 0x04;
        if (flagFlip)      flags |= 0x08;
        if (flagHeadless)  flags |= 0x10;
        if (flagCalibrate) flags |= 0x80;
        int bFlags = escapeByte(flags);

        int cs = ((bRoll ^ bPitch ^ bThrot ^ bYaw ^ bFlags ^ 0xFF) & 0xFF);
        cs = escapeByte(cs);

        byte[] buf = new byte[8];
        buf[0] = (byte) 0x66;
        buf[1] = (byte) bRoll;
        buf[2] = (byte) bPitch;
        buf[3] = (byte) bThrot;
        buf[4] = (byte) bYaw;
        buf[5] = (byte) bFlags;
        buf[6] = (byte) cs;
        buf[7] = (byte) 0x99;
        return buf;
    }

    /**
     * Extended 20-byte command packet (IBaseCmdNew):
     * [0x66, 0x14, Roll, Pitch, Throttle, Yaw, Flags, 0x00...0x00, Checksum, 0x99]
     */
    private byte[] buildPacket20() {
        byte[] buf = new byte[20];
        buf[0] = (byte) 0x66;
        buf[1] = (byte) 0x14; // Length 20
        buf[2] = (byte) escapeByte(roll);
        buf[3] = (byte) escapeByte(pitch);
        buf[4] = (byte) escapeByte(throttle);
        buf[5] = (byte) escapeByte(yaw);

        int flags = 0;
        if (flagTakeoff)   flags |= 0x01;
        if (flagLand)      flags |= 0x02;
        if (flagEmergency) flags |= 0x04;
        if (flagFlip)      flags |= 0x08;
        if (flagHeadless)  flags |= 0x10;
        if (flagCalibrate) flags |= 0x80;
        buf[6] = (byte) escapeByte(flags);

        // bytes 7 to 17 remain 0x00

        int sum = 0;
        for (int i = 2; i <= 17; i++) {
            sum ^= (buf[i] & 0xFF);
        }
        sum = escapeByte((sum ^ 0xFF) & 0xFF);
        buf[18] = (byte) sum;
        buf[19] = (byte) 0x99;
        return buf;
    }

    public void armMotors() {
        Log.d(TAG, "Arming motors");
        isArmed = true;
        throttle = 40; // Idle spin
        mainHandler.postDelayed(() -> {
            if (!flagTakeoff && throttle <= 45) {
                throttle = 0;
            }
        }, 3000);
    }

    public void arm() {
        armMotors();
    }

    public void takeoff() {
        Log.d(TAG, "TAKEOFF triggered (pulsing bit 0x01)");
        pulseTakeoff = 25; // 1000ms pulse
        throttle = 135;   // Stable hover throttle
        isArmed = true;
    }

    public void land() {
        Log.d(TAG, "LAND triggered (pulsing bit 0x02)");
        pulseLand = 25;
        final int initThrot = throttle;
        new Thread(() -> {
            for (int i = 10; i >= 0; i--) {
                throttle = (initThrot * i) / 10;
                try { Thread.sleep(200); } catch (Exception ignored) {}
            }
            resetControls();
            throttle = 0;
        }).start();
    }

    public void emergencyStop() {
        Log.d(TAG, "EMERGENCY STOP (pulsing bit 0x04)");
        pulseEmergency = 25;
        resetControls();
        throttle = 0;
        isArmed = false;
    }

    public void calibrate() {
        Log.d(TAG, "CALIBRATE GYRO (pulsing bit 0x80)");
        pulseCalibrate = 25;
    }

    public void flip() {
        flip("front");
    }

    public void flip(String direction) {
        Log.d(TAG, "360 FLIP: " + direction);
        pulseFlip = 15;
        if ("left".equalsIgnoreCase(direction)) roll = 60;
        else if ("right".equalsIgnoreCase(direction)) roll = 196;
        else if ("back".equalsIgnoreCase(direction)) pitch = 60;
        else pitch = 196; // front

        mainHandler.postDelayed(() -> {
            roll = 128;
            pitch = 128;
        }, 400);
    }

    public void flyForward(int power) {
        this.pitch = clamp(128 + power, 0, 255);
    }

    public void flyBackward(int power) {
        this.pitch = clamp(128 - power, 0, 255);
    }

    public void turnLeft(int power) {
        this.yaw = clamp(128 - power, 0, 255);
    }

    public void turnRight(int power) {
        this.yaw = clamp(128 + power, 0, 255);
    }

    public void ascend(int power) {
        this.throttle = clamp(this.throttle + power, 0, 255);
    }

    public void descend(int power) {
        this.throttle = clamp(this.throttle - power, 0, 255);
    }

    public void hover() {
        this.roll = 128;
        this.pitch = 128;
        this.yaw = 128;
    }

    public void setControls(float normRoll, float normPitch, float normThrottle, float normYaw) {
        this.roll = clamp((int) (128 + (normRoll * 127)), 0, 255);
        this.pitch = clamp((int) (128 + (normPitch * 127)), 0, 255);
        this.yaw = clamp((int) (128 + (normYaw * 127)), 0, 255);

        if (normThrottle >= 0) {
            this.throttle = clamp((int) (normThrottle * 255), 0, 255);
        }
    }

    public void resetControls() {
        roll = 128;
        pitch = 128;
        yaw = 128;
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
