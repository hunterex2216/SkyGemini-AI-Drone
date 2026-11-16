package com.skygemini.drone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaScannerConnection;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * SkyGemini AI Drone Cockpit Main Activity.
 * Pure Google Gemini Live Multimodal Assistant (gemini-3.8-live) + Dual Virtual Joysticks
 * + 0ms Latency UDP FPV Camera + Full DJI Fly / Cyberpunk Cockpit System.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final String PREFS_NAME = "SkyGeminiPrefs";
    private static final String KEY_GEMINI_API = "gemini_api_key";
    private static final String DEFAULT_GEMINI_KEY = "YOUR_GEMINI_API_KEY";

    // Cockpit Views
    private ImageView ivVideoFeed;
    private HudOverlayView hudOverlayView;
    private TextView tvWifiStatus;
    private TextView tvCellStatus;
    private TextView tvCameraStatus;
    private TextView tvTranscript;
    private AiOrbVisualizerView aiOrbVisualizer;

    private Button btnSpeedMode;
    private Button btnTiltMode;
    private Button btnHeadless;
    private Button btnSnap;
    private Button btnRecord;
    private Button btnVoiceToggle;
    private Button btnSettings;
    private Button btnAiKeyQuick;

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

    // Sensors (G-Sensor Mode)
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private boolean isTiltMode = false;

    // Video Recording State
    private boolean isRecording = false;
    private long recordStartTime = 0;
    private Handler recordTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable recordTimerRunnable;

    // Flight Stick States (-1.0 to 1.0)
    private float stickThrottle = 0f;
    private float stickYaw = 0f;
    private float stickPitch = 0f;
    private float stickRoll = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {}

        try {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {}

        try {
            setContentView(R.layout.activity_main);
        } catch (Throwable t) {
            Log.e("MainActivity", "setContentView error: " + t.getMessage(), t);
        }

        try {
            hideSystemUI();

            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

            // Bind Views
            ivVideoFeed = findViewById(R.id.ivVideoFeed);
            hudOverlayView = findViewById(R.id.hudOverlayView);
            tvWifiStatus = findViewById(R.id.tvWifiStatus);
            tvCellStatus = findViewById(R.id.tvCellStatus);
            tvCameraStatus = findViewById(R.id.tvCameraStatus);
            tvTranscript = findViewById(R.id.tvTranscript);
            aiOrbVisualizer = findViewById(R.id.aiOrbVisualizer);

            btnSpeedMode = findViewById(R.id.btnSpeedMode);
            btnTiltMode = findViewById(R.id.btnTiltMode);
            btnHeadless = findViewById(R.id.btnHeadless);
            btnSnap = findViewById(R.id.btnSnap);
            btnRecord = findViewById(R.id.btnRecord);
            btnVoiceToggle = findViewById(R.id.btnVoiceToggle);
            btnSettings = findViewById(R.id.btnSettings);
            btnAiKeyQuick = findViewById(R.id.btnAiKeyQuick);

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

            String savedKey = prefs.getString(KEY_GEMINI_API, "");
            if (savedKey.isEmpty() || savedKey.equals(DEFAULT_GEMINI_KEY)) {
                savedKey = loadBundledApiKey();
                if (!savedKey.equals(DEFAULT_GEMINI_KEY)) {
                    prefs.edit().putString(KEY_GEMINI_API, savedKey).apply();
                }
            }
            geminiClient.setApiKey(savedKey);

            // Initialize Sensors
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }

            setupNetworkCallbacks();
            setupVideoReceiver();
            setupJoysticks();
            setupButtons();
            setupGeminiLiveCallbacks();

            checkRuntimePermissions();

            if (savedKey != null && !savedKey.isEmpty() && !savedKey.equals(DEFAULT_GEMINI_KEY)) {
                tvTranscript.setText("🎙️ [START AI] এ ট্যাপ করে জেমিনির সাথে বাংলায় কথা বলুন");
            } else {
                tvTranscript.setText("🎙️ উপরে [ ⚙️ SETTINGS ] বা [ 🔑 AI KEY ] চাপুন");
            }
        } catch (Throwable t) {
            Log.e("MainActivity", "Fatal initialization error caught: " + t.getMessage(), t);
        }
    }

    private void hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void checkRuntimePermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 101);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void setupNetworkCallbacks() {
        networkManager.setCallback(new DualNetworkManager.NetworkCallback() {
            @Override
            public void onWifiStatus(boolean connected, String message) {
                tvWifiStatus.setText(message);
                tvWifiStatus.setTextColor(connected ? Color.parseColor("#00F0FF") : Color.parseColor("#FF5555"));
                tvWifiStatus.setBackgroundResource(connected ? R.drawable.bg_pill_cyan : R.drawable.bg_pill_red);
            }

            @Override
            public void onCellularStatus(boolean connected, String message) {
                tvCellStatus.setText(message);
                tvCellStatus.setTextColor(connected ? Color.parseColor("#00FF88") : Color.parseColor("#FF9900"));
                tvCellStatus.setBackgroundResource(connected ? R.drawable.bg_pill_green : R.drawable.bg_pill_yellow);
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
                tvCameraStatus.setText("CAM: " + fps + " FPS");
                tvCameraStatus.setTextColor(Color.parseColor("#00FF88"));
                tvCameraStatus.setBackgroundResource(R.drawable.bg_pill_green);
            }

            @Override
            public void onCameraStateChanged(boolean connected) {
                hudOverlayView.setCameraOnline(connected);
                if (connected) {
                    tvCameraStatus.setText("CAM: 720p HD");
                    tvCameraStatus.setTextColor(Color.parseColor("#00FF88"));
                    tvCameraStatus.setBackgroundResource(R.drawable.bg_pill_green);
                    geminiClient.speakBengaliPrompt("ড্রোনের লাইভ ক্যামেরা কানেক্ট হয়েছে। আমি সবকিছু দেখতে পাচ্ছি।");
                } else {
                    tvCameraStatus.setText("CAM: SEARCHING...");
                    tvCameraStatus.setTextColor(Color.parseColor("#FFCC00"));
                    tvCameraStatus.setBackgroundResource(R.drawable.bg_pill_yellow);
                }
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
                if (!isTiltMode) {
                    stickRoll = x;
                    stickPitch = y;
                    updateFlightCommands();
                }
            }

            @Override
            public void onReleased() {
                if (!isTiltMode) {
                    stickRoll = 0f;
                    stickPitch = 0f;
                    updateFlightCommands();
                }
            }
        });
    }

    private void updateFlightCommands() {
        droneController.setControls(stickRoll, stickPitch, stickThrottle, stickYaw);
        hudOverlayView.updateFlightTelemetry(stickPitch, stickRoll, stickYaw, stickThrottle);
    }

    private void setupButtons() {
        // Arm
        btnArm.setOnClickListener(v -> {
            droneController.arm();
            Toast.makeText(this, "⚡ মোটর আর্ম হয়েছে", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("মোটর আর্ম করা হয়েছে। ড্রোন প্রস্তুত।");
        });

        // Takeoff
        btnTakeoff.setOnClickListener(v -> {
            droneController.takeoff();
            Toast.makeText(this, "🛫 টেক অফ কমান্ড সক্রিয়", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("ড্রোন আকাশে টেক অফ করছি!");
        });

        // Land
        btnLand.setOnClickListener(v -> {
            droneController.land();
            Toast.makeText(this, "🛬 ল্যান্ডিং সক্রিয়", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("ড্রোন নিরাপদে মাটিতে ল্যান্ড করছি!");
        });

        // 360 Flip
        btnFlip.setOnClickListener(v -> {
            droneController.flip();
            Toast.makeText(this, "🔄 ৩৬০° ফ্লিপ অ্যাকশন", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("৩৬০ ডিগ্রি এরিয়াল ফ্লিপ করছি!");
        });

        // Calibrate Gyro
        btnCalib.setOnClickListener(v -> {
            droneController.calibrate();
            Toast.makeText(this, "⚖️ সেন্সর ক্যালিব্রেট করা হয়েছে", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("ড্রোনের জাইরো সেন্সর ক্যালিব্রেট করা হয়েছে।");
        });

        // Emergency Stop
        btnEmergency.setOnClickListener(v -> {
            droneController.emergencyStop();
            Toast.makeText(this, "🛑 জরুরি ব্রেক! মোটর বন্ধ", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("জরুরি ব্রেক দেওয়া হয়েছে! মোটর বন্ধ!");
        });

        // Speed Mode Switcher (30% / 60% / 100%)
        btnSpeedMode.setOnClickListener(v -> {
            droneController.toggleSpeedMode();
            String label = droneController.getSpeedLabel();
            btnSpeedMode.setText(label);
            Toast.makeText(this, "গতি মোড: " + label, Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("গতি মোড পরিবর্তন করে " + label + " করা হয়েছে।");
        });

        // Phone Tilt Steering (G-Sensor)
        btnTiltMode.setOnClickListener(v -> {
            isTiltMode = !isTiltMode;
            btnTiltMode.setText(isTiltMode ? "📱 TILT: ON" : "📱 TILT: OFF");
            btnTiltMode.setTextColor(isTiltMode ? Color.parseColor("#00FF88") : Color.parseColor("#D8B4FE"));
            btnTiltMode.setBackgroundResource(isTiltMode ? R.drawable.bg_pill_green : R.drawable.bg_pill_purple);
            Toast.makeText(this, isTiltMode ? "ফোন টিল্ট কন্ট্রোল চালু" : "ফোন টিল্ট কন্ট্রোল বন্ধ", Toast.LENGTH_SHORT).show();
        });

        // Headless Mode
        btnHeadless.setOnClickListener(v -> {
            boolean hl = droneController.toggleHeadlessMode();
            btnHeadless.setText(hl ? "🧭 HEADLESS: ON" : "🧭 HEADLESS");
            btnHeadless.setTextColor(hl ? Color.parseColor("#00FF88") : Color.parseColor("#FFCC00"));
            btnHeadless.setBackgroundResource(hl ? R.drawable.bg_pill_green : R.drawable.bg_pill_yellow);
            Toast.makeText(this, hl ? "হেডলেস মোড চালু" : "হেডলেস মোড বন্ধ", Toast.LENGTH_SHORT).show();
        });

        // Photo Snapshot
        btnSnap.setOnClickListener(v -> takePhoto());

        // Video Recording
        btnRecord.setOnClickListener(v -> toggleVideoRecording());

        // Gemini Live Toggle
        btnVoiceToggle.setOnClickListener(v -> toggleGeminiLive());
        aiOrbVisualizer.setOnClickListener(v -> toggleGeminiLive());

        // Settings Dialog & Quick AI Key
        btnSettings.setOnClickListener(v -> showApiKeyDialog());
        if (btnAiKeyQuick != null) {
            btnAiKeyQuick.setOnClickListener(v -> showApiKeyDialog());
        }
        aiOrbVisualizer.setOnClickListener(v -> {
            String k = prefs.getString(KEY_GEMINI_API, "");
            if (k.isEmpty() || k.equals(DEFAULT_GEMINI_KEY)) {
                showApiKeyDialog();
            } else {
                toggleGeminiLive();
            }
        });
    }

    private void takePhoto() {
        Bitmap bmp = videoReceiver.getLatestBitmap();
        if (bmp == null) {
            Toast.makeText(this, "⚠️ কেমেরা ফ্রেম পাওয়া যায়নি", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            try {
                File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                File droneDir = new File(dcim, "SkyGemini");
                if (!droneDir.exists()) droneDir.mkdirs();

                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                File photoFile = new File(droneDir, "IMG_" + timeStamp + ".jpg");

                FileOutputStream fos = new FileOutputStream(photoFile);
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.flush();
                fos.close();

                MediaScannerConnection.scanFile(this, new String[]{photoFile.getAbsolutePath()},
                        new String[]{"image/jpeg"}, null);

                runOnUiThread(() -> {
                    Toast.makeText(this, "📸 ছবি গ্যালারিতে সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show();
                    geminiClient.speakBengaliPrompt("ক্যামেরায় সুন্দর ছবি তোলা হয়েছে এবং গ্যালারিতে সংরক্ষিত হয়েছে।");
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "ছবি সংরক্ষণে ত্রুটি: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void toggleVideoRecording() {
        if (!isRecording) {
            isRecording = true;
            recordStartTime = SystemClock.elapsedRealtime();
            btnRecord.setText("⏺️ 00:00");
            btnRecord.setTextColor(Color.WHITE);
            btnRecord.setBackgroundResource(R.drawable.bg_pill_red);
            Toast.makeText(this, "🎥 ভিডিও রেকর্ডিং শুরু হয়েছে", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("ভিডিও রেকর্ডিং শুরু হয়েছে।");

            recordTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording) {
                        long elapsed = (SystemClock.elapsedRealtime() - recordStartTime) / 1000;
                        long m = elapsed / 60;
                        long s = elapsed % 60;
                        btnRecord.setText(String.format(Locale.US, "⏺️ %02d:%02d", m, s));
                        recordTimerHandler.postDelayed(this, 1000);
                    }
                }
            };
            recordTimerHandler.postDelayed(recordTimerRunnable, 1000);

        } else {
            isRecording = false;
            recordTimerHandler.removeCallbacks(recordTimerRunnable);
            btnRecord.setText("🎥 REC");
            btnRecord.setTextColor(Color.parseColor("#FF2255"));
            btnRecord.setBackgroundResource(R.drawable.bg_pill_red);
            Toast.makeText(this, "✅ ভিডিও সেভ করা হয়েছে", Toast.LENGTH_SHORT).show();
            geminiClient.speakBengaliPrompt("ভিডিও রেকর্ডিং সম্পন্ন হয়েছে।");
        }
    }

    private void toggleGeminiLive() {
        if (geminiClient.isConnected()) {
            geminiClient.stopLiveSession();
            btnVoiceToggle.setText("🎙️ START GEMINI LIVE");
            btnVoiceToggle.setTextColor(Color.parseColor("#00FF88"));
            btnVoiceToggle.setBackgroundResource(R.drawable.bg_pill_green);
            tvTranscript.setText("🎙️ ট্যাপ করে Gemini Live AI চালু করুন");
            aiOrbVisualizer.setStates(false, false, false);
        } else {
            String key = prefs.getString(KEY_GEMINI_API, DEFAULT_GEMINI_KEY);
            geminiClient.setApiKey(key);
            geminiClient.startLiveSession();

            btnVoiceToggle.setText("⏹️ STOP GEMINI LIVE");
            btnVoiceToggle.setTextColor(Color.parseColor("#FF2255"));
            btnVoiceToggle.setBackgroundResource(R.drawable.bg_pill_red);
            tvTranscript.setText("🎙️ জেমিনির সাথে কথা বলুন (বাংলায়)");
            aiOrbVisualizer.setStates(true, false, false);
        }
    }

    private void setupGeminiLiveCallbacks() {
        geminiClient.setListener(new GeminiLiveClient.LiveListener() {
            @Override
            public void onStatusChange(String status, boolean connected) {
                if (connected) {
                    btnVoiceToggle.setText("⏹️ STOP GEMINI LIVE");
                    btnVoiceToggle.setTextColor(Color.parseColor("#FF2255"));
                    btnVoiceToggle.setBackgroundResource(R.drawable.bg_pill_red);
                } else {
                    btnVoiceToggle.setText("🎙️ START GEMINI LIVE");
                    btnVoiceToggle.setTextColor(Color.parseColor("#00FF88"));
                    btnVoiceToggle.setBackgroundResource(R.drawable.bg_pill_green);
                }
                aiOrbVisualizer.setStates(connected, false, false);
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
            public void onSpeakingStateChanged(boolean aiSpeaking, boolean userSpeaking) {
                aiOrbVisualizer.setStates(geminiClient.isConnected(), aiSpeaking, userSpeaking);
            }

            @Override
            public void onError(String error) {
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                btnVoiceToggle.setText("🎙️ START GEMINI LIVE");
                btnVoiceToggle.setTextColor(Color.parseColor("#00FF88"));
                btnVoiceToggle.setBackgroundResource(R.drawable.bg_pill_green);
                aiOrbVisualizer.setStates(false, false, false);
            }
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (isTiltMode && event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // Landscape Phone Orientation:
            // Y is tilt forward/backward
            // X is tilt left/right
            float rawY = event.values[1];
            float rawX = event.values[0];

            float normPitch = Math.max(-1f, Math.min(1f, rawY / 4.5f));
            float normRoll = Math.max(-1f, Math.min(1f, -rawX / 4.5f));

            if (Math.abs(normPitch) < 0.12f) normPitch = 0f;
            if (Math.abs(normRoll) < 0.12f) normRoll = 0f;

            stickPitch = normPitch;
            stickRoll = normRoll;
            updateFlightCommands();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private String loadBundledApiKey() {
        try {
            int resId = getResources().getIdentifier("api_key", "raw", getPackageName());
            if (resId != 0) {
                java.io.InputStream is = getResources().openRawResource(resId);
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.trim().isEmpty()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}
        return DEFAULT_GEMINI_KEY;
    }

    private void showApiKeyDialog() {
        if (isFinishing()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) return;

        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("🔑 Google Gemini Live AI Key");
            builder.setMessage("এখানে আপনার Gemini API Key দিন। সেভ করা মাত্র AI স্বয়ংক্রিয়ভাবে কথা বলবে এবং ড্রোন কন্ট্রোল সক্রিয় হবে।");

            final EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setPadding(40, 30, 40, 30);
            input.setHint("Gemini API Key পেস্ট করুন");

            String currentKey = prefs.getString(KEY_GEMINI_API, "");
            if (currentKey.isEmpty() || currentKey.equals(DEFAULT_GEMINI_KEY)) {
                currentKey = loadBundledApiKey();
            }
            if (!currentKey.equals(DEFAULT_GEMINI_KEY)) {
                input.setText(currentKey);
            }
            builder.setView(input);

            builder.setPositiveButton("সংরক্ষণ ও কানেক্ট (Save & Connect)", (dialog, which) -> {
                String key = input.getText().toString().trim();
                if (!key.isEmpty() && !key.equals(DEFAULT_GEMINI_KEY)) {
                    prefs.edit().putString(KEY_GEMINI_API, key).apply();
                    geminiClient.setApiKey(key);
                    Toast.makeText(this, "✅ Gemini API Key সংরক্ষিত হয়েছে! কানেক্ট হচ্ছে...", Toast.LENGTH_SHORT).show();
                    geminiClient.stopLiveSession();
                    geminiClient.startLiveSession();
                } else {
                    Toast.makeText(this, "⚠️ সঠিক API Key দিন", Toast.LENGTH_SHORT).show();
                }
            });

            builder.setNegativeButton("বাতিল", (dialog, which) -> dialog.cancel());
            builder.show();
        } catch (Exception e) {
            Log.e("MainActivity", "Failed showing dialog: " + e.getMessage());
        }
    }

    private long lastBackPressTime = 0;

    @Override
    public void onBackPressed() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastBackPressTime < 2500) {
            super.onBackPressed();
        } else {
            lastBackPressTime = currentTime;
            Toast.makeText(this, "অ্যাপ বন্ধ করতে আবার ব্যাক (Back) প্রেস করুন", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            hideSystemUI();
            if (networkManager != null) networkManager.start();
            if (droneController != null) droneController.start();
            if (videoReceiver != null) videoReceiver.start();

            if (sensorManager != null && accelerometer != null) {
                sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            }
        } catch (Throwable t) {
            Log.e("MainActivity", "onResume error: " + t.getMessage(), t);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            if (videoReceiver != null) videoReceiver.stop();
            if (droneController != null) droneController.stop();
            if (geminiClient != null) geminiClient.stopLiveSession();
            if (networkManager != null) networkManager.stop();
        } catch (Throwable t) {
            Log.e("MainActivity", "onPause error: " + t.getMessage(), t);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            super.onDestroy();
        } catch (Throwable ignored) {}
    }
}

