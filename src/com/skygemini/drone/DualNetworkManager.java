package com.skygemini.drone;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.DatagramSocket;
import java.net.Socket;

/**
 * DualNetworkManager ensures:
 * 1. Drone UDP sockets are bound to TRANSPORT_WIFI (192.168.4.153).
 * 2. Gemini Live WebSocket / HTTP is bound to TRANSPORT_CELLULAR (4G/5G).
 * Notifies listeners immediately when each network is available.
 */
public class DualNetworkManager {
    private static final String TAG = "DualNetworkManager";

    public interface NetworkCallback {
        void onWifiStatus(boolean connected, String message);
        void onCellularStatus(boolean connected, String message);
        void onWifiNetworkReady(Network wifiNetwork);
        void onCellularNetworkReady(Network cellularNetwork);
    }

    private final Context context;
    private final ConnectivityManager connectivityManager;
    private final Handler mainHandler;
    private NetworkCallback callback;

    private volatile Network wifiNetwork = null;
    private volatile Network cellularNetwork = null;

    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager.NetworkCallback cellularCallback;

    public DualNetworkManager(Context context) {
        this.context = context.getApplicationContext();
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setCallback(NetworkCallback callback) {
        this.callback = callback;
    }

    public void start() {
        if (connectivityManager == null) return;

        // 1. Request Wi-Fi Network specifically for Drone
        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                wifiNetwork = network;
                Log.d(TAG, "✅ Wi-Fi Network Available: " + network);
                notifyWifi(true, "DRONE WIFI: BOUND", network);
            }

            @Override
            public void onLost(Network network) {
                if (wifiNetwork != null && wifiNetwork.equals(network)) {
                    wifiNetwork = null;
                    Log.w(TAG, "❌ Wi-Fi Network Lost");
                    notifyWifi(false, "DRONE WIFI: DISCONNECTED", null);
                }
            }
        };

        try {
            connectivityManager.requestNetwork(wifiRequest, wifiCallback);
        } catch (Exception e) {
            Log.e(TAG, "Error requesting WiFi network: " + e.getMessage());
        }

        // 2. Request Cellular (Mobile Data) Network specifically for Gemini Live AI
        NetworkRequest cellularRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cellularNetwork = network;
                Log.d(TAG, "✅ Cellular Network Available: " + network);

                // Automatically bind process to Cellular so all Internet/TLS/WebSocket/DNS traffic uses 4G/5G
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        connectivityManager.bindProcessToNetwork(network);
                        Log.d(TAG, "Process successfully bound to Cellular network: " + network);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to bindProcessToNetwork: " + e.getMessage());
                    }
                }

                notifyCellular(true, "4G/5G CELLULAR: BOUND", network);
            }

            @Override
            public void onLost(Network network) {
                if (cellularNetwork != null && cellularNetwork.equals(network)) {
                    cellularNetwork = null;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        try {
                            connectivityManager.bindProcessToNetwork(null);
                        } catch (Exception ignored) {}
                    }
                    Log.w(TAG, "❌ Cellular Network Lost");
                    notifyCellular(false, "4G/5G: DISCONNECTED", null);
                }
            }
        };

        try {
            connectivityManager.requestNetwork(cellularRequest, cellularCallback);
        } catch (Exception e) {
            Log.e(TAG, "Error requesting Cellular network: " + e.getMessage());
        }
    }

    public void stop() {
        if (connectivityManager != null) {
            if (wifiCallback != null) {
                try { connectivityManager.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
            }
            if (cellularCallback != null) {
                try { connectivityManager.unregisterNetworkCallback(cellularCallback); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Bind drone UDP datagram socket directly to Wi-Fi interface
     */
    public boolean bindToWifi(DatagramSocket socket) {
        Network net = wifiNetwork;
        if (net != null && socket != null) {
            try {
                net.bindSocket(socket);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind DatagramSocket to Wi-Fi: " + e.getMessage());
            }
        }
        return false;
    }

    /**
     * Bind socket directly to Cellular (Mobile Data) interface
     */
    public boolean bindToCellular(Socket socket) {
        Network net = cellularNetwork;
        if (net != null && socket != null) {
            try {
                net.bindSocket(socket);
                return true;
            } catch (Exception e) {
                Log.w(TAG, "Failed to bind Socket to Cellular: " + e.getMessage());
            }
        }
        return false;
    }

    public Network getWifiNetwork() {
        return wifiNetwork;
    }

    public Network getCellularNetwork() {
        return cellularNetwork;
    }

    public void bindProcessToCellular() {
        Network net = cellularNetwork;
        if (net != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                connectivityManager.bindProcessToNetwork(net);
                Log.d(TAG, "Explicitly bound process to Cellular Network: " + net);
            } catch (Exception e) {
                Log.w(TAG, "bindProcessToCellular error: " + e.getMessage());
            }
        }
    }

    private void notifyWifi(boolean connected, String message, Network network) {
        if (callback != null) {
            mainHandler.post(() -> {
                callback.onWifiStatus(connected, message);
                if (connected && network != null) {
                    callback.onWifiNetworkReady(network);
                }
            });
        }
    }

    private void notifyCellular(boolean connected, String message, Network network) {
        if (callback != null) {
            mainHandler.post(() -> {
                callback.onCellularStatus(connected, message);
                if (connected && network != null) {
                    callback.onCellularNetworkReady(network);
                }
            });
        }
    }
}
