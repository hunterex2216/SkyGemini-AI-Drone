package com.skygemini.drone;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pure Google Gemini Live Multimodal Assistant Client.
 * Connects directly to Google's official Gemini Live WebSocket API (gemini-3.8-live / gemini-2.0-flash-exp).
 * Zero robotic Android TTS — 100% native Gemini audio voice (Puck) streamed in 24kHz PCM,
 * with 16kHz mic input, multimodal FPV camera vision, and autonomous drone flight tool calling.
 */
public class GeminiLiveClient {
    private static final String TAG = "GeminiLiveClient";

    public interface LiveListener {
        void onStatusChange(String status, boolean connected);
        void onUserTranscript(String text);
        void onAiTranscript(String text);
        void onSpeakingStateChanged(boolean aiSpeaking, boolean userSpeaking);
        void onError(String error);
    }

    private final DualNetworkManager networkManager;
    private final DroneController droneController;
    private final Handler mainHandler;
    private LiveListener listener;

    private String apiKey = "YOUR_GEMINI_API_KEY";
    private Socket rawSocket;
    private SSLSocket sslSocket;
    private OutputStream socketOut;
    private InputStream socketIn;

    private volatile boolean isRunning = false;
    private volatile boolean isConnected = false;
    private volatile boolean isAiSpeaking = false;
    private volatile boolean isUserSpeaking = false;
    private volatile long lastAiAudioTime = 0;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private ExecutorService executor;

    private long lastVideoSendTime = 0;
    private final SecureRandom secureRandom = new SecureRandom();

    public GeminiLiveClient(DualNetworkManager networkManager, DroneController droneController) {
        this.networkManager = networkManager;
        this.droneController = droneController;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(LiveListener listener) {
        this.listener = listener;
    }

    public void setApiKey(String key) {
        if (key != null && !key.trim().isEmpty()) {
            this.apiKey = key.trim();
        }
    }

    public synchronized void startLiveSession() {
        if (isRunning) return;
        if (apiKey.isEmpty()) {
            notifyError("Gemini API Key খালি! ⚙️ সেটিংসে কি বসান।");
            return;
        }

        isRunning = true;
        executor = Executors.newCachedThreadPool();
        executor.execute(this::runWebSocketSession);
    }

    public synchronized void stopLiveSession() {
        isRunning = false;
        isConnected = false;

        closeSocket();
        stopAudioRecord();
        stopAudioTrack();

        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }

        notifyStatus("GEMINI: IDLE", false);
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void sendVideoFrame(byte[] jpegData) {
        if (!isConnected || socketOut == null || jpegData == null || jpegData.length == 0) return;
        long now = System.currentTimeMillis();
        // Stream 1 frame every 1000ms for Gemini Multimodal Vision
        if (now - lastVideoSendTime < 1000) return;
        lastVideoSendTime = now;

        if (executor != null && !executor.isShutdown()) {
            executor.execute(() -> {
                try {
                    String b64 = Base64.encodeToString(jpegData, Base64.NO_WRAP);
                    JSONObject videoObj = new JSONObject();
                    videoObj.put("data", b64);
                    videoObj.put("mimeType", "image/jpeg");

                    JSONObject realtimeInput = new JSONObject();
                    realtimeInput.put("video", videoObj);

                    JSONObject root = new JSONObject();
                    root.put("realtimeInput", realtimeInput);

                    sendWsText(root.toString());
                } catch (Exception ignored) {}
            });
        }
    }

    /**
     * Injects a spoken command / confirmation request into Gemini Live.
     * Gemini Live immediately responds verbally in Bengali using its native voice.
     */
    public void speakBengaliPrompt(String promptText) {
        if (!isConnected || socketOut == null) return;
        if (executor != null && !executor.isShutdown()) {
            executor.execute(() -> {
                try {
                    JSONObject clientContent = new JSONObject();
                    JSONArray turns = new JSONArray();
                    JSONObject turn = new JSONObject();
                    turn.put("role", "user");
                    JSONArray parts = new JSONArray();
                    JSONObject part = new JSONObject();
                    part.put("text", promptText);
                    parts.put(part);
                    turn.put("parts", parts);
                    turns.put(turn);
                    clientContent.put("turns", turns);
                    clientContent.put("turnComplete", true);

                    JSONObject root = new JSONObject();
                    root.put("clientContent", clientContent);
                    sendWsText(root.toString());
                } catch (Exception e) {
                    Log.w(TAG, "Failed sending clientContent prompt: " + e.getMessage());
                }
            });
        }
    }

    private void runWebSocketSession() {
        notifyStatus("GEMINI LIVE: CONNECTING...", false);
        try {
            // Ensure process is bound to cellular network so all Internet/TLS/WebSocket/DNS traffic uses 4G/5G
            networkManager.bindProcessToCellular();
            Network cellNet = networkManager.getCellularNetwork();
            InetAddress targetAddr = null;

            if (cellNet != null) {
                try {
                    InetAddress[] addrs = cellNet.getAllByName("generativelanguage.googleapis.com");
                    if (addrs != null && addrs.length > 0) {
                        targetAddr = addrs[0];
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Cellular DNS query failed: " + e.getMessage());
                }
            }

            if (targetAddr == null) {
                targetAddr = InetAddress.getByName("generativelanguage.googleapis.com");
            }

            // Create plain TCP socket and connect first (avoids Android EPERM from cellNet.getSocketFactory().createSocket())
            // Because process is bound to cellular, rawSocket automatically routes via 4G/5G!
            rawSocket = new Socket();
            rawSocket.setTcpNoDelay(true);
            rawSocket.connect(new InetSocketAddress(targetAddr, 443), 10000);

            // Layer TLS/SSL over the connected socket with explicit SNI
            SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            sslSocket = (SSLSocket) sslFactory.createSocket(rawSocket, "generativelanguage.googleapis.com", 443, true);
            sslSocket.startHandshake();

            socketOut = sslSocket.getOutputStream();
            socketIn = sslSocket.getInputStream();

            // HTTP WebSocket Handshake Upgrade (v1beta BidiGenerateContent)
            byte[] nonce = new byte[16];
            secureRandom.nextBytes(nonce);
            String wsKey = Base64.encodeToString(nonce, Base64.NO_WRAP);

            String request = "GET /ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + apiKey + " HTTP/1.1\r\n"
                    + "Host: generativelanguage.googleapis.com\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";

            socketOut.write(request.getBytes(StandardCharsets.UTF_8));
            socketOut.flush();

            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b;
            int matched = 0;
            while ((b = socketIn.read()) != -1) {
                headerBuf.write(b);
                if ((matched == 0 || matched == 2) && b == '\r') {
                    matched++;
                } else if ((matched == 1 || matched == 3) && b == '\n') {
                    matched++;
                    if (matched == 4) break;
                } else {
                    matched = 0;
                }
            }

            String responseHeader = new String(headerBuf.toByteArray(), StandardCharsets.UTF_8);
            if (!responseHeader.contains("101 Switching Protocols")) {
                throw new Exception("Handshake failed: " + responseHeader.split("\r\n")[0]);
            }

            isConnected = true;
            notifyStatus("GEMINI LIVE: ONLINE", true);
            Log.d(TAG, "Connected to Google Gemini Live API!");

            sendSetupMessage();

            initAudioTrack();
            initAudioRecord();

            executor.execute(this::streamMicrophoneLoop);

            receiveWebSocketLoop();

        } catch (Exception e) {
            Log.e(TAG, "Gemini Live error: " + e.getMessage(), e);
            notifyError("Gemini Live Error: " + e.getMessage());
        } finally {
            stopLiveSession();
        }
    }

    private void sendSetupMessage() throws Exception {
        JSONObject setup = new JSONObject();
        // Official Google Gemini Live model: gemini-3.8-live
        setup.put("model", "models/gemini-3.8-live");

        JSONObject genConfig = new JSONObject();
        JSONArray modalities = new JSONArray();
        modalities.put("AUDIO");
        genConfig.put("responseModalities", modalities);

        JSONObject voiceConfig = new JSONObject();
        JSONObject prebuiltVoice = new JSONObject();
        prebuiltVoice.put("voiceName", "Puck"); // Official energetic Gemini Live voice
        voiceConfig.put("prebuiltVoiceConfig", prebuiltVoice);
        JSONObject speechConfig = new JSONObject();
        speechConfig.put("voiceConfig", voiceConfig);
        genConfig.put("speechConfig", speechConfig);
        setup.put("generationConfig", genConfig);

        setup.put("inputAudioTranscription", new JSONObject());
        setup.put("outputAudioTranscription", new JSONObject());

        JSONObject sysInstruction = new JSONObject();
        JSONArray parts = new JSONArray();
        JSONObject part = new JSONObject();
        part.put("text", "You are Google Gemini Live, the autonomous AI copilot piloting this physical drone. "
                + "You speak naturally in fluent Bengali (বাংলা) with the real human-like Gemini voice. "
                + "You autonomously understand the user's spoken voice commands in Bengali and English. "
                + "When the user commands you (e.g. 'উড়াও', 'টেক অফ', 'takeoff', 'উড়তে দাও', 'উপরে তোলো', 'স্টার্ট'), "
                + "you MUST IMMEDIATELY call the `takeoff` function tool and verbally say in Bengali 'ড্রোন টেক অফ করছি!'. "
                + "When the user commands 'ল্যান্ড করো', 'নামাও', 'land', 'নিচে আসো', "
                + "you MUST IMMEDIATELY call the `land` function tool and say in Bengali 'ড্রোন ল্যান্ড করছি!'. "
                + "When the user says 'ফ্লিপ করো', 'ডিগবাজি দাও', 'flip', execute `flip_360` and say '৩৬০ ডিগ্রি ফ্লিপ করছি!'. "
                + "When the user says 'থামো', 'বন্ধ করো', 'stop', execute `emergency_stop` and say 'ড্রোন থামিয়ে দেওয়া হয়েছে!'. "
                + "When the user commands 'সামনে যাও' call `fly_forward`; 'পেছনে আসো' call `fly_backward`; 'বামে ঘুরাও' call `turn_left`; 'ডানে ঘুরাও' call `turn_right`; 'উপরে তোলো' call `ascend`; 'নিচে নামাও' call `descend`. "
                + "You also see live HD camera frames from the drone FPV camera. When the user asks what you see or what is in front, look at the camera image and describe it clearly in Bengali. "
                + "Always keep your verbal responses quick and natural in Bengali.");
        parts.put(part);
        sysInstruction.put("parts", parts);
        setup.put("systemInstruction", sysInstruction);

        JSONArray tools = new JSONArray();
        JSONObject tool = new JSONObject();
        JSONArray funcDecls = new JSONArray();

        funcDecls.put(buildFunc("takeoff", "Launch and hover the drone / ড্রোন আকাশে উড়াও বা টেক অফ করো"));
        funcDecls.put(buildFunc("land", "Gently land the drone / ড্রোন নামাও বা ল্যান্ড করো"));
        funcDecls.put(buildFunc("arm_motors", "Arm and spin motors / মোটর চালু করো বা আর্ম করো"));
        funcDecls.put(buildFunc("emergency_stop", "EMERGENCY CUT MOTORS / ড্রোন জরুরি থামাও"));
        funcDecls.put(buildFunc("flip_360", "Perform a 360 degree aerial flip stunt / ৩৬০ ডিগ্রি ফ্লিপ করো"));
        funcDecls.put(buildFunc("calibrate", "Calibrate drone gyroscopes / জাইরো ক্যালিব্রেট করো"));
        funcDecls.put(buildFuncWithParam("fly_forward", "Fly drone forward / ড্রোন সামনের দিকে চালাও", "power"));
        funcDecls.put(buildFuncWithParam("fly_backward", "Fly drone backward / ড্রোন পেছনের দিকে আনো", "power"));
        funcDecls.put(buildFuncWithParam("turn_left", "Rotate drone counter-clockwise / ড্রোন বামে ঘুরাও", "power"));
        funcDecls.put(buildFuncWithParam("turn_right", "Rotate drone clockwise / ড্রোন ডানে ঘুরাও", "power"));
        funcDecls.put(buildFuncWithParam("ascend", "Gain altitude / ড্রোন উপরে তোলো", "power"));
        funcDecls.put(buildFuncWithParam("descend", "Lower altitude / ড্রোন নিচে নামাও", "power"));
        funcDecls.put(buildFunc("hover", "Stabilize and hover in place / হোভার করো"));

        tool.put("functionDeclarations", funcDecls);
        tools.put(tool);
        setup.put("tools", tools);

        JSONObject root = new JSONObject();
        root.put("setup", setup);

        sendWsText(root.toString());
    }

    private JSONObject buildFunc(String name, String desc) throws Exception {
        JSONObject f = new JSONObject();
        f.put("name", name);
        f.put("description", desc);
        f.put("behavior", "NON_BLOCKING");
        JSONObject params = new JSONObject();
        params.put("type", "OBJECT");
        params.put("properties", new JSONObject());
        f.put("parameters", params);
        return f;
    }

    private JSONObject buildFuncWithParam(String name, String desc, String paramName) throws Exception {
        JSONObject f = new JSONObject();
        f.put("name", name);
        f.put("description", desc);
        f.put("behavior", "NON_BLOCKING");
        JSONObject params = new JSONObject();
        params.put("type", "OBJECT");
        JSONObject props = new JSONObject();
        JSONObject p = new JSONObject();
        p.put("type", "INTEGER");
        p.put("description", "Power percentage 1-100");
        props.put(paramName, p);
        params.put("properties", props);
        f.put("parameters", params);
        return f;
    }

    private void initAudioTrack() {
        try {
            int bufferSize = AudioTrack.getMinBufferSize(24000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                audioTrack = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(24000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(bufferSize, 8192))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, 24000,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(bufferSize, 8192), AudioTrack.MODE_STREAM);
            }
            audioTrack.play();
        } catch (Exception e) {
            Log.e(TAG, "AudioTrack init error: " + e.getMessage());
        }
    }

    private void stopAudioTrack() {
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (Exception ignored) {}
            audioTrack = null;
        }
    }

    private void initAudioRecord() {
        try {
            int bufferSize = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            try {
                // VOICE_COMMUNICATION enables hardware acoustic echo cancellation & noise suppression
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(bufferSize, 8192));
            } catch (Exception ignored) {}

            if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(bufferSize, 8192));
            }

            if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                audioRecord.startRecording();
                Log.d(TAG, "AudioRecord started recording at 16kHz PCM");
            }
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord init error: " + e.getMessage());
        }
    }

    private void stopAudioRecord() {
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
    }

    private void streamMicrophoneLoop() {
        // 1024 bytes = 32ms of 16kHz 16-bit mono PCM for ultra-low latency streaming
        byte[] pcmBuffer = new byte[1024];
        long lastUserVoiceTime = 0;

        while (isRunning && isConnected && audioRecord != null) {
            try {
                int read = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                if (read > 0) {
                    // Compute RMS volume to track user speech state
                    long sum = 0;
                    for (int i = 0; i < read - 1; i += 2) {
                        short s = (short) ((pcmBuffer[i] & 0xFF) | (pcmBuffer[i + 1] << 8));
                        sum += (long) s * s;
                    }
                    double rms = Math.sqrt(sum / (read / 2.0));
                    boolean speaking = rms > 650;

                    long now = System.currentTimeMillis();
                    if (speaking) {
                        lastUserVoiceTime = now;
                        if (!isUserSpeaking) {
                            isUserSpeaking = true;
                            notifySpeakingState();
                        }
                    } else if (isUserSpeaking && (now - lastUserVoiceTime > 800)) {
                        isUserSpeaking = false;
                        notifySpeakingState();
                    }

                    if (isAiSpeaking && (now - lastAiAudioTime > 800)) {
                        isAiSpeaking = false;
                        notifySpeakingState();
                    }

                    String b64 = Base64.encodeToString(pcmBuffer, 0, read, Base64.NO_WRAP);
                    JSONObject audioObj = new JSONObject();
                    audioObj.put("data", b64);
                    audioObj.put("mimeType", "audio/pcm;rate=16000");

                    JSONObject realtimeInput = new JSONObject();
                    realtimeInput.put("audio", audioObj);

                    JSONObject root = new JSONObject();
                    root.put("realtimeInput", realtimeInput);

                    sendWsText(root.toString());
                }
            } catch (Exception e) {
                if (!isRunning) break;
            }
        }
    }

    private void receiveWebSocketLoop() {
        try {
            while (isRunning && isConnected) {
                int b0 = socketIn.read();
                if (b0 == -1) break;

                int opcode = b0 & 0x0F;
                int b1 = socketIn.read();
                if (b1 == -1) break;

                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;

                if (len == 126) {
                    int h = socketIn.read();
                    int l = socketIn.read();
                    len = ((h & 0xFF) << 8) | (l & 0xFF);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | (socketIn.read() & 0xFF);
                    }
                }

                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(socketIn, mask, 0, 4);
                }

                byte[] payload = new byte[(int) len];
                readFully(socketIn, payload, 0, (int) len);

                if (masked && mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ mask[i % 4]);
                    }
                }

                if (opcode == 0x8) {
                    break;
                } else if (opcode == 0x9) {
                    sendWsPong(payload);
                } else if (opcode == 0x1) {
                    String text = new String(payload, StandardCharsets.UTF_8);
                    handleServerMessage(text);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Rx loop ended: " + e.getMessage());
        }
    }

    private void handleServerMessage(String jsonStr) {
        try {
            JSONObject msg = new JSONObject(jsonStr);

            if (msg.has("setupComplete")) {
                Log.d(TAG, "Gemini Live setupComplete received!");
                notifyStatus("GEMINI LIVE: ONLINE", true);
                notifyAiTranscript("জেমিনি প্রস্তুত! কথা বলুন...");

                // Auto-trigger Gemini to speak verbal greeting in Bengali aloud
                speakBengaliPrompt("ড্রোন সেশন চালু হয়েছে। আপনি স্কাইজেমিনি এআই ড্রোন কো-পাইলট। ব্যবহারকারীকে সুন্দর ও আনন্দের সাথে বাংলায় স্বাগতম জানান এবং বলুন আপনি ড্রোন ওড়াতে তৈরি।");
            }

            if (msg.has("serverContent")) {
                JSONObject sc = msg.getJSONObject("serverContent");

                if (sc.has("modelTurn")) {
                    JSONObject mt = sc.getJSONObject("modelTurn");
                    if (mt.has("parts")) {
                        JSONArray parts = mt.getJSONArray("parts");
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject p = parts.getJSONObject(i);
                            if (p.has("inlineData")) {
                                JSONObject inlineData = p.getJSONObject("inlineData");
                                String b64Audio = inlineData.optString("data");
                                if (b64Audio != null && !b64Audio.isEmpty()) {
                                    byte[] pcm24 = Base64.decode(b64Audio, Base64.DEFAULT);
                                    if (audioTrack != null && pcm24 != null) {
                                        lastAiAudioTime = System.currentTimeMillis();
                                        if (!isAiSpeaking) {
                                            isAiSpeaking = true;
                                            notifySpeakingState();
                                        }
                                        audioTrack.write(pcm24, 0, pcm24.length);
                                    }
                                }
                            }
                            if (p.has("text")) {
                                String text = p.getString("text");
                                notifyAiTranscript(text);
                            }
                        }
                    }
                }

                if (sc.optBoolean("turnComplete", false)) {
                    isAiSpeaking = false;
                    notifySpeakingState();
                }

                if (sc.has("inputTranscription")) {
                    String userText = sc.getJSONObject("inputTranscription").optString("text");
                    if (!userText.isEmpty()) notifyUserTranscript(userText);
                }

                if (sc.has("outputTranscription")) {
                    String aiText = sc.getJSONObject("outputTranscription").optString("text");
                    if (!aiText.isEmpty()) notifyAiTranscript(aiText);
                }

                if (sc.optBoolean("interrupted", false)) {
                    // Natural speech barge-in / interruption
                    isAiSpeaking = false;
                    notifySpeakingState();
                    if (audioTrack != null) {
                        audioTrack.pause();
                        audioTrack.flush();
                        audioTrack.play();
                    }
                }
            }

            if (msg.has("toolCall")) {
                JSONObject tc = msg.getJSONObject("toolCall");
                if (tc.has("functionCalls")) {
                    JSONArray calls = tc.getJSONArray("functionCalls");
                    JSONArray responses = new JSONArray();

                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.getJSONObject(i);
                        String id = call.optString("id");
                        String name = call.optString("name");
                        JSONObject args = call.optJSONObject("args");

                        String result = executeDroneFunction(name, args);

                        JSONObject resp = new JSONObject();
                        resp.put("id", id);
                        resp.put("name", name);
                        JSONObject output = new JSONObject();
                        output.put("result", result);
                        resp.put("response", output);
                        responses.put(resp);
                    }

                    JSONObject toolResp = new JSONObject();
                    JSONObject tr = new JSONObject();
                    tr.put("functionResponses", responses);
                    toolResp.put("toolResponse", tr);

                    sendWsText(toolResp.toString());
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error handling message: " + e.getMessage());
        }
    }

    private String executeDroneFunction(String name, JSONObject args) {
        if (droneController == null) return "Error";

        switch (name) {
            case "takeoff":
                droneController.takeoff();
                notifyAiTranscript("🛫 ড্রোন টেক অফ করা হচ্ছে...");
                return "Takeoff executed successfully";

            case "land":
                droneController.land();
                notifyAiTranscript("🛬 ড্রোন ল্যান্ড করা হচ্ছে...");
                return "Landing executed successfully";

            case "arm_motors":
                droneController.arm();
                notifyAiTranscript("⚡ মোটর আর্ম করা হয়েছে");
                return "Motors armed";

            case "emergency_stop":
                droneController.emergencyStop();
                notifyAiTranscript("🛑 জরুরি অবতরণ! মোটর বন্ধ");
                return "Emergency stop";

            case "flip_360":
                droneController.flip();
                notifyAiTranscript("🔄 ৩৬০° ফ্লিপ করা হচ্ছে!");
                return "Flip executed";

            case "calibrate":
                droneController.calibrate();
                notifyAiTranscript("⚖️ সেন্সর ক্যালিব্রেট করা হচ্ছে...");
                return "Calibrated";

            case "fly_forward":
                droneController.flyForward(60);
                scheduleReturnToCenter(1500);
                return "Forward";

            case "fly_backward":
                droneController.flyBackward(60);
                scheduleReturnToCenter(1500);
                return "Backward";

            case "turn_left":
                droneController.turnLeft(60);
                scheduleReturnToCenter(1200);
                return "Turn left";

            case "turn_right":
                droneController.turnRight(60);
                scheduleReturnToCenter(1200);
                return "Turn right";

            case "ascend":
                droneController.ascend(50);
                scheduleReturnToCenter(1500);
                return "Ascend";

            case "descend":
                droneController.descend(40);
                scheduleReturnToCenter(1500);
                return "Descend";

            case "hover":
            default:
                droneController.hover();
                return "Hovering";
        }
    }

    private void scheduleReturnToCenter(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (droneController != null) droneController.hover();
        }, delayMs);
    }

    private synchronized void sendWsText(String text) {
        try {
            if (socketOut == null) return;
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            sendFrame(0x1, payload);
        } catch (Exception ignored) {}
    }

    private synchronized void sendWsPong(byte[] pingPayload) {
        try {
            if (socketOut == null) return;
            sendFrame(0xA, pingPayload);
        } catch (Exception ignored) {}
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws Exception {
        int len = (payload != null) ? payload.length : 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(0x80 | (opcode & 0x0F));

        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 65535) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }

        byte[] mask = new byte[4];
        secureRandom.nextBytes(mask);
        out.write(mask);

        if (payload != null) {
            byte[] maskedData = new byte[len];
            for (int i = 0; i < len; i++) {
                maskedData[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
            out.write(maskedData);
        }

        socketOut.write(out.toByteArray());
        socketOut.flush();
    }

    private void readFully(InputStream in, byte[] b, int off, int len) throws Exception {
        int total = 0;
        while (total < len) {
            int count = in.read(b, off + total, len - total);
            if (count < 0) throw new Exception("Stream ended");
            total += count;
        }
    }

    private void closeSocket() {
        if (sslSocket != null) {
            try { sslSocket.close(); } catch (Exception ignored) {}
            sslSocket = null;
        }
        if (rawSocket != null) {
            try { rawSocket.close(); } catch (Exception ignored) {}
            rawSocket = null;
        }
        socketOut = null;
        socketIn = null;
    }

    private void notifySpeakingState() {
        if (listener != null) {
            final boolean ai = isAiSpeaking;
            final boolean user = isUserSpeaking;
            mainHandler.post(() -> listener.onSpeakingStateChanged(ai, user));
        }
    }

    private void notifyStatus(String status, boolean connected) {
        if (listener != null) {
            final String s = status;
            final boolean c = connected;
            mainHandler.post(() -> listener.onStatusChange(s, c));
        }
    }

    private void notifyUserTranscript(String text) {
        if (listener != null) {
            final String t = text;
            mainHandler.post(() -> listener.onUserTranscript(t));
        }
    }

    private void notifyAiTranscript(String text) {
        if (listener != null) {
            final String t = text;
            mainHandler.post(() -> listener.onAiTranscript(t));
        }
    }

    private void notifyError(String error) {
        if (listener != null) {
            final String err = error;
            mainHandler.post(() -> listener.onError(err));
        }
    }
}
