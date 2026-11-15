package com.skygemini.drone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * SkyGemini AI Drone Autopilot Main Activity.
 * Direct Pure Google Gemini Live Multimodal Assistant + Dual Joysticks.
 * Zero robotic TTS — Real Gemini Live streaming voice + live FPV vision.
 */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "SkyGeminiPrefs";
    private static final String KEY_GEMINI_API = "gemini_api_key";
    private static final String DEFAULT_GEMINI_KEY = "YOUR_GEMINI_API_KEY";

    // Views
    private ImageView ivVideoFeed;
    private HudOverlayView hudOverlayView;
    private TextView tvWifiStatus;
    private TextView tvCellStatus;
    private TextView tvAiStatus;
    private TextView tvTranscript;
    private Button btnVoiceToggle;
    private Button btnSettings;
    private Button btnArm;
    private Button btnTakeoff;
    private Button btnLand;
    private Button btnFlip;
    private Button btnCalib;
    private Button btnEmergency;
    private VirtualJoystickView leftJoystick;
    private VirtualJoystickView rightJoystick;

    // Managers & Clients
    private DualNetworkManager networkManager;
    private DroneController droneController;
    private VideoReceiver videoReceiver;
    private GeminiLiveClient geminiClient;
    private SharedPreferences prefs;

    // Flight Stick States
    private float stickThrottle = 0f;
    private float stickYaw = 0f;
    private float stickPitch = 0f;
    private float stickRoll = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        hideSystemUI();

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Bind Views
        ivVideoFeed = findViewById(R.id.ivVideoFeed);
        hudOverlayView = findViewById(R.id.hudOverlayView);
        tvWifiStatus = findViewById(R.id.tvWifiStatus);
        tvCellStatus = findViewById(R.id.tvCellStatus);
        tvAiStatus = findViewById(R.id.tvAiStatus);
        tvTranscript = findViewById(R.id.tvTranscript);
        btnVoiceToggle = findViewById(R.id.btnVoiceToggle);
        btnSettings = findViewById(R.id.btnSettings);
        btnArm = findViewById(R.id.btnArm);
        btnTakeoff = findViewById(R.id.btnTakeoff);
        btnLand = findViewById(R.id.btnLand);
        btnFlip = findViewById(R.id.btnFlip);
        btnCalib = findViewById(R.id.btnCalib);
        btnEmergency = findViewById(R.id.btnEmergency);
        leftJoystick = findViewById(R.id.leftJoystick);
        rightJoystick = findViewById(R.id.rightJoystick);

        // Initialize Core Drone Services
        networkManager = new DualNetworkManager(this);
        droneController = new DroneController(networkManager);
        videoReceiver = new VideoReceiver(networkManager);
        geminiClient = new GeminiLiveClient(networkManager, droneController);

        String savedKey = prefs.getString(KEY_GEMINI_API, DEFAULT_GEMINI_KEY);
        geminiClient.setApiKey(savedKey);

        setupNetworkCallbacks();
        setupVideoReceiver();
        setupJoysticks();
        setupButtons();
        setupGeminiLiveCallbacks();

        checkRuntimePermissions();
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void checkRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String[] perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.CHANGE_NETWORK_STATE
            };
            boolean needReq = false;
            for (String p : perms) {
                if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                    needReq = true;
                    break;
                }
            }
            if (needReq) {
                requestPermissions(perms, 101);
            }
        }
    }

    private void setupNetworkCallbacks() {
        networkManager.setCallback(new DualNetworkManager.NetworkCallback() {
            @Override
            public void onWifiStatus(boolean connected, String message) {
                tvWifiStatus.setText(message);
                tvWifiStatus.setTextColor(connected ? Color.parseColor("#00F0FF") : Color.parseColor("#FF5555"));
                tvWifiStatus.setBackgroundColor(connected ? Color.parseColor("#3300F0FF") : Color.parseColor("#33FF5555"));
            }

            @Override
            public void onCellularStatus(boolean connected, String message) {
                tvCellStatus.setText(message);
                tvCellStatus.setTextColor(connected ? Color.parseColor("#00FF88") : Color.parseColor("#FF9900"));
                tvCellStatus.setBackgroundColor(connected ? Color.parseColor("#3300FF88") : Color.parseColor("#33FF9900"));
            }

            @Override
            public void onWifiNetworkReady(Network wifiNetwork) {
                videoReceiver.onWifiReady(wifiNetwork);
                droneController.onWifiReady(wifiNetwork);
            }

            @Override
            public void onCellularNetworkReady(Network cellularNetwork) {
            }
        });
    }

    private void setupVideoReceiver() {
        videoReceiver.setOnFrameListener(new VideoReceiver.OnFrameListener() {
            @Override
            public void onFrame(Bitmap bitmap) {
                ivVideoFeed.setImageBitmap(bitmap);
            }

            @Override
            public void onFpsUpdate(int fps) {
            }

            @Override
            public void onRawJpegFrame(byte[] jpegBytes) {
                // Stream HD video frame into Gemini Live Multimodal Vision
                if (geminiClient != null && geminiClient.isConnected()) {
                    geminiClient.sendVideoFrame(jpegBytes);
                }
            }
        });
    }

    private void setupJoysticks() {
        // Left Joystick: Yaw (X) and Throttle (Y > 0)
        leftJoystick.setOnJoystickMovedListener(new VirtualJoystickView.OnJoystickMovedListener() {
            @Override
            public void onMoved(float x, float y) {
                stickYaw = x;
                stickThrottle = Math.max(0f, y);
                updateFlightCommands();
            }

            @Override
            public void onReleased() {
                stickYaw = 0f;
                stickThrottle = 0f;
                updateFlightCommands();
            }
        });

        // Right Joystick: Pitch (Y) and Roll (X)
        rightJoystick.setOnJoystickMovedListener(new VirtualJoystickView.OnJoystickMovedListener() {
            @Override
            public void onMoved(float x, float y) {
                stickRoll = x;
                stickPitch = y;
                updateFlightCommands();
            }

            @Override
            public void onReleased() {
                stickRoll = 0f;
                stickPitch = 0f;
                updateFlightCommands();
            }
        });
    }

    private void updateFlightCommands() {
        droneController.setControls(stickRoll, stickPitch, stickThrottle, stickYaw);
        hudOverlayView.updateFlightTelemetry(stickPitch, stickRoll, stickYaw, stickThrottle);
    }

    private void setupButtons() {
        btnArm.setOnClickListener(v -> {
            droneController.arm();
            Toast.makeText(this, "⚡ মোটর আর্ম হয়েছে", Toast.LENGTH_SHORT).show();
        });

        btnTakeoff.setOnClickListener(v -> {
            droneController.takeoff();
            Toast.makeText(this, "🛫 টেক অফ কমান্ড পাঠানো হয়েছে", Toast.LENGTH_SHORT).show();
        });

        btnLand.setOnClickListener(v -> {
            droneController.land();
            Toast.makeText(this, "🛬 ল্যান্ড কমান্ড পাঠানো হয়েছে", Toast.LENGTH_SHORT).show();
        });

        btnFlip.setOnClickListener(v -> {
            droneController.flip();
            Toast.makeText(this, "🔄 ৩৬০° ফ্লিপ অ্যাকশন", Toast.LENGTH_SHORT).show();
        });

        btnCalib.setOnClickListener(v -> {
            droneController.calibrate();
            Toast.makeText(this, "⚖️ সেন্সর ক্যালিব্রেট করা হয়েছে", Toast.LENGTH_SHORT).show();
        });

        btnEmergency.setOnClickListener(v -> {
            droneController.emergencyStop();
            Toast.makeText(this, "🛑 জরুরি ব্রেক! মোটর বন্ধ", Toast.LENGTH_SHORT).show();
        });

        btnVoiceToggle.setOnClickListener(v -> toggleGeminiLive());

        btnSettings.setOnClickListener(v -> showApiKeyDialog());
    }

    private void toggleGeminiLive() {
        if (geminiClient.isConnected()) {
            geminiClient.stopLiveSession();
            btnVoiceToggle.setText("🎙️ START GEMINI LIVE");
            btnVoiceToggle.setTextColor(Color.parseColor("#00FF88"));
            btnVoiceToggle.setBackgroundColor(Color.parseColor("#3300FF88"));
            tvAiStatus.setText("GEMINI: IDLE");
            tvAiStatus.setTextColor(Color.parseColor("#FFCC00"));
            tvTranscript.setText("🎙️ ট্যাপ করে আসল Gemini Live AI চালু করুন");
        } else {
            String key = prefs.getString(KEY_GEMINI_API, DEFAULT_GEMINI_KEY);
            geminiClient.setApiKey(key);
            geminiClient.startLiveSession();

            btnVoiceToggle.setText("⏹️ STOP GEMINI LIVE");
            btnVoiceToggle.setTextColor(Color.parseColor("#FF2255"));
            btnVoiceToggle.setBackgroundColor(Color.parseColor("#33FF2255"));
            tvTranscript.setText("🎙️ জেমিনির সাথে কথা বলুন (বাংলায়)");
        }
    }

    private void setupGeminiLiveCallbacks() {
        geminiClient.setListener(new GeminiLiveClient.LiveListener() {
            @Override
            public void onStatusChange(String status, boolean connected) {
                tvAiStatus.setText(status);
                tvAiStatus.setTextColor(connected ? Color.parseColor("#00FF88") : Color.parseColor("#FFCC00"));
                if (connected) {
                    btnVoiceToggle.setText("⏹️ STOP GEMINI LIVE");
                    btnVoiceToggle.setTextColor(Color.parseColor("#FF2255"));
                    btnVoiceToggle.setBackgroundColor(Color.parseColor("#33FF2255"));
                } else {
                    btnVoiceToggle.setText("🎙️ START GEMINI LIVE");
                    btnVoiceToggle.setTextColor(Color.parseColor("#00FF88"));
                    btnVoiceToggle.setBackgroundColor(Color.parseColor("#3300FF88"));
                }
            }

            @Override
            public void onUserTranscript(String text) {
                tvTranscript.setText("🗣️ আপনি: " + text);
            }

            @Override
            public void onAiTranscript(String text) {
                tvTranscript.setText("🤖 জেমিনি: " + text);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                btnVoiceToggle.setText("🎙️ START GEMINI LIVE");
                btnVoiceToggle.setTextColor(Color.parseColor("#00FF88"));
                btnVoiceToggle.setBackgroundColor(Color.parseColor("#3300FF88"));
            }
        });
    }

    private void showApiKeyDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🔑 Google Gemini Live API Key");

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(prefs.getString(KEY_GEMINI_API, DEFAULT_GEMINI_KEY));
        builder.setView(input);

        builder.setPositiveButton("সংরক্ষণ (Save)", (dialog, which) -> {
            String key = input.getText().toString().trim();
            prefs.edit().putString(KEY_GEMINI_API, key).apply();
            geminiClient.setApiKey(key);
            Toast.makeText(this, "Gemini API Key সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("বাতিল", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        networkManager.start();
        droneController.start();
        videoReceiver.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        videoReceiver.stop();
        droneController.stop();
        geminiClient.stopLiveSession();
        networkManager.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
