package com.voiceclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Calendar;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import org.vosk.Model;
import org.vosk.Recognizer;

public class VoiceService extends Service {

    private static final String TAG = "VoiceService";
    private static final String CHANNEL_ID = "voice_clock_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 16000;

    public static final String ACTION_STATUS = "com.voiceclock.STATUS";
    public static final String ACTION_DEBUG = "com.voiceclock.DEBUG";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_DEBUG = "debug";
    public static final String STATUS_LOADING = "loading";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_LISTENING = "listening";

    public static boolean isRunning = false;

    // Vosk (speech recognition)
    private Model voskModel;
    private Recognizer recognizer;
    private AudioRecord audioRecord;

    // SherpaOnnx (TTS)
    private OfflineTts tts;
    private int ttsSampleRate = 22050;

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private Handler handler;
    private Thread recognitionThread;
    private volatile boolean isListening = false;
    private volatile boolean modelReady = false;
    private volatile boolean ttsReady = false;
    private String lastError = "";

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        isRunning = true;
        initAll();
    }

    private void initAll() {
        broadcastStatus(STATUS_LOADING);
        broadcastDebug("[init] \u521d\u59cb\u5316\u4e2d...");

        new Thread(() -> {
            try {
                // 1. Copy and load Vosk model (speech recognition)
                initVosk();

                // 2. Copy and load SherpaOnnx TTS model
                initSherpaOnnxTts();

                // 3. Init AudioRecord
                if (!initAudioRecord()) {
                    lastError = "AudioRecord init failed";
                    broadcastDebug("[error] \u97f3\u9891\u5f55\u5236\u521d\u59cb\u5316\u5931\u8d25");
                    broadcastStatus(STATUS_ERROR);
                    return;
                }

                broadcastDebug("[ok] \u97f3\u9891\u5f55\u5236\u521d\u59cb\u5316\u6210\u529f");
                modelReady = true;
                startRecognitionLoop();
                broadcastStatus(STATUS_READY);
                broadcastDebug("[ready] \u5df2\u5c31\u7eea\uff0c\u5f00\u59cb\u76d1\u542c...\u8bf7\u8bf4\u8bdd");

            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                broadcastDebug("[error] " + lastError);
                Log.e(TAG, "Failed to start: " + lastError, e);
                broadcastStatus(STATUS_ERROR);
            }
        }).start();
    }

    // ========== Vosk (Speech Recognition) ==========

    private void initVosk() throws Exception {
        broadcastDebug("[init] \u52a0\u8f7d\u8bed\u97f3\u8bc6\u522b\u6a21\u578b...");

        String modelPath = getFilesDir().getAbsolutePath() + "/vosk-model";
        File modelDir = new File(modelPath);
        File markerFile = new File(modelDir, ".ready");

        if (!markerFile.exists()) {
            broadcastDebug("[init] \u590d\u5236\u8bc6\u522b\u6a21\u578b\u6587\u4ef6...");
            if (modelDir.exists()) {
                deleteRecursive(modelDir);
            }
            modelDir.mkdirs();
            copyAssetFolder("model", modelPath);
            markerFile.createNewFile();
        }

        voskModel = new Model(modelPath);
        recognizer = new Recognizer(voskModel, SAMPLE_RATE);
        broadcastDebug("[ok] \u8bed\u97f3\u8bc6\u522b\u5f15\u64ce\u5c31\u7eea (Vosk)");
    }

    // ========== SherpaOnnx TTS ==========

    private static final String TTS_MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-theresa.tar.bz2";

    private void initSherpaOnnxTts() throws Exception {
        broadcastDebug("[tts] \u521d\u59cb\u5316 SherpaOnnx \u79bb\u7ebf TTS...");

        String ttsModelDir = getFilesDir().getAbsolutePath() + "/tts-model";
        File ttsDir = new File(ttsModelDir);
        File markerFile = new File(ttsDir, ".ready");

        if (!markerFile.exists()) {
            broadcastDebug("[tts] \u6a21\u578b\u672a\u627e\u5230\uff0c\u5f00\u59cb\u4e0b\u8f7d...");
            
            // Download model
            File downloadFile = new File(getCacheDir(), "tts-model.tar.bz2");
            downloadModel(TTS_MODEL_URL, downloadFile);
            
            // Extract model
            broadcastDebug("[tts] \u89e3\u538b\u6a21\u578b\u6587\u4ef6...");
            if (ttsDir.exists()) {
                deleteRecursive(ttsDir);
            }
            ttsDir.mkdirs();
            extractTarBz2(downloadFile, ttsDir);
            downloadFile.delete();
            
            markerFile.createNewFile();
            broadcastDebug("[tts] \u6a21\u578b\u4e0b\u8f7d\u5e76\u89e3\u538b\u5b8c\u6210");
        }

        // Find model files
        String modelFile = findFile(ttsDir, ".onnx");
        String lexiconFile = findFile(ttsDir, "lexicon");
        String tokensFile = findFile(ttsDir, "tokens");

        broadcastDebug("[tts] model: " + (modelFile != null ? new File(modelFile).getName() : "NOT FOUND"));
        broadcastDebug("[tts] lexicon: " + (lexiconFile != null ? new File(lexiconFile).getName() : "NOT FOUND"));
        broadcastDebug("[tts] tokens: " + (tokensFile != null ? new File(tokensFile).getName() : "NOT FOUND"));

        if (modelFile == null || tokensFile == null) {
            throw new Exception("TTS model files not found after download");
        }

        OfflineTtsVitsModelConfig vitsConfig = OfflineTtsVitsModelConfig.builder()
                .setModel(modelFile)
                .setLexicon(lexiconFile != null ? lexiconFile : "")
                .setTokens(tokensFile)
                .setNoiseScale(0.667f)
                .setNoiseScaleW(0.8f)
                .setLengthScale(1.0f)
                .build();

        OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                .setVits(vitsConfig)
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu")
                .build();

        OfflineTtsConfig config = OfflineTtsConfig.builder()
                .setModel(modelConfig)
                .setMaxNumSentences(1)
                .build();

        tts = new OfflineTts(config);
        ttsSampleRate = tts.getSampleRate();
        ttsReady = true;

        broadcastDebug("[tts-ok] SherpaOnnx TTS \u5c31\u7eea (sampleRate=" + ttsSampleRate + ")");
    }

    private void downloadModel(String urlStr, File destFile) throws IOException {
        broadcastDebug("[tts] \u4e0b\u8f7d\u4e2d: " + urlStr);
        
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "VoiceClock/1.0");
        
        int totalSize = conn.getContentLength();
        broadcastDebug("[tts] \u6587\u4ef6\u5927\u5c0f: " + (totalSize / 1024 / 1024) + "MB");
        
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(destFile);
        
        byte[] buffer = new byte[65536];
        int bytesRead;
        long totalRead = 0;
        int lastPercent = 0;
        
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
            totalRead += bytesRead;
            
            if (totalSize > 0) {
                int percent = (int) (totalRead * 100 / totalSize);
                if (percent >= lastPercent + 10) {
                    broadcastDebug("[tts] \u4e0b\u8f7d\u8fdb\u5ea6: " + percent + "%");
                    lastPercent = percent;
                }
            }
        }
        
        in.close();
        out.close();
        conn.disconnect();
        
        broadcastDebug("[tts] \u4e0b\u8f7d\u5b8c\u6210");
    }

    private void extractTarBz2(File tarBz2File, File destDir) throws IOException {
        InputStream fi = new java.io.FileInputStream(tarBz2File);
        BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(fi);
        TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn);
        
        TarArchiveEntry entry;
        int fileCount = 0;
        
        while ((entry = tarIn.getNextTarEntry()) != null) {
            if (entry.isDirectory()) {
                continue;
            }
            
            // Strip the first directory component (e.g., "vits-zh-hf-theresa/")
            String entryName = entry.getName();
            int slashIndex = entryName.indexOf('/');
            if (slashIndex >= 0) {
                entryName = entryName.substring(slashIndex + 1);
            }
            
            if (entryName.isEmpty()) continue;
            
            File outputFile = new File(destDir, entryName);
            outputFile.getParentFile().mkdirs();
            
            FileOutputStream out = new FileOutputStream(outputFile);
            byte[] buffer = new byte[8192];
            int len;
            while ((len = tarIn.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            out.close();
            fileCount++;
        }
        
        tarIn.close();
        bzIn.close();
        fi.close();
        
        broadcastDebug("[tts] \u89e3\u538b\u5b8c\u6210: " + fileCount + " \u4e2a\u6587\u4ef6");
    }

    private String findFile(File dir, String suffix) {
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        // First check direct children
        for (File f : files) {
            if (f.getName().equals(suffix) || f.getName().endsWith(suffix)) {
                return f.getAbsolutePath();
            }
        }

        // Then check subdirectories
        for (File f : files) {
            if (f.isDirectory()) {
                String result = findFile(f, suffix);
                if (result != null) return result;
            }
        }
        return null;
    }

    // ========== Audio Playback ==========

    private void playAudio(float[] samples, int sampleRate) {
        if (samples == null || samples.length == 0) return;

        // Convert float samples to short (PCM16)
        short[] shortSamples = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            shortSamples[i] = (short) (s * Short.MAX_VALUE);
        }

        // Convert to byte buffer (little-endian)
        byte[] audioBytes = new byte[shortSamples.length * 2];
        ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shortSamples);

        int bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();

        AudioTrack audioTrack = new AudioTrack(
                attrs,
                format,
                Math.max(bufferSize, audioBytes.length),
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
        );

        requestAudioFocus();
        audioTrack.play();
        audioTrack.write(audioBytes, 0, audioBytes.length);

        // Wait for playback to complete
        try {
            Thread.sleep((long) (samples.length * 1000.0 / sampleRate) + 100);
        } catch (InterruptedException e) {
            // ignore
        }

        audioTrack.stop();
        audioTrack.release();
        abandonAudioFocus();
    }

    // ========== Audio Focus ==========

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build();
            return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            @SuppressWarnings("deprecation")
            int result = audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }

    // ========== Speech Recognition Loop ==========

    private boolean initAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) return false;

        int bufferSize = Math.max(minBufferSize, 16000);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        } catch (SecurityException e) {
            lastError = "Permission denied";
            return false;
        } catch (Exception e) {
            lastError = e.getMessage();
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            audioRecord = null;
            return false;
        }
        return true;
    }

    private void startRecognitionLoop() {
        isListening = true;
        broadcastStatus(STATUS_LISTENING);

        recognitionThread = new Thread(() -> {
            audioRecord.startRecording();
            byte[] buffer = new byte[8192];

            while (isListening) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead < 0) break;

                if (bytesRead > 0 && recognizer != null && modelReady) {
                    try {
                        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                            processResult(recognizer.getResult());
                        } else {
                            processPartialResult(recognizer.getPartialResult());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Recognition error", e);
                    }
                }
            }
        });
        recognitionThread.start();
    }

    private void processResult(String resultJson) {
        try {
            String text = new JSONObject(resultJson).optString("text", "");
            broadcastDebug("[\u8bc6\u522b] " + text);
            if (containsTimeQuery(text)) {
                broadcastDebug("[\u547d\u4e2d] \u68c0\u6d4b\u5230\u65f6\u95f4\u8be2\u95ee\uff01");
                handler.post(this::speakTime);
            }
        } catch (Exception e) {
            broadcastDebug("[error] \u89e3\u6790\u7ed3\u679c\u5931\u8d25");
        }
    }

    private void processPartialResult(String partialJson) {
        try {
            String text = new JSONObject(partialJson).optString("partial", "");
            if (!text.isEmpty()) {
                broadcastDebug("[\u8bed\u97f3] " + text);
            }
            if (containsTimeQuery(text)) {
                broadcastDebug("[\u547d\u4e2d] \u68c0\u6d4b\u5230\u65f6\u95ee\uff01");
                handler.post(this::speakTime);
            }
        } catch (Exception e) {
            Log.e(TAG, "Partial result error", e);
        }
    }

    private boolean containsTimeQuery(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("\u51e0\u70b9\u4e86") || text.contains("\u51e0\u70b9\u5566")
                || text.contains("\u4ec0\u4e48\u65f6\u95f4") || text.contains("\u73b0\u5728\u65f6\u95f4")
                || text.contains("\u51e0\u65f6") || text.contains("\u51e0\u70b9");
    }

    // ========== Speak Time (using SherpaOnnx TTS) ==========

    private void speakTime() {
        if (!ttsReady || tts == null) {
            broadcastDebug("[tts-error] TTS \u672a\u5c31\u7eea");
            return;
        }

        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        String period;
        if (hour < 6) period = "\u51cc\u6668";
        else if (hour < 12) period = "\u4e0a\u5348";
        else if (hour < 14) period = "\u4e2d\u5348";
        else if (hour < 18) period = "\u4e0b\u5348";
        else period = "\u665a\u4e0a";

        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        String timeText = "\u73b0\u5728\u662f" + period + displayHour + "\u70b9" + minute + "\u5206";
        broadcastDebug("[tts] \u64ad\u62a5: " + timeText);

        new Thread(() -> {
            try {
                GeneratedAudio audio = tts.generate(timeText);
                if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                    broadcastDebug("[tts-ok] \u751f\u6210\u97f3\u9891 " + audio.getSamples().length + " \u91c7\u6837\u70b9");
                    playAudio(audio.getSamples(), audio.getSampleRate());
                    broadcastDebug("[tts-ok] \u64ad\u62a5\u5b8c\u6210");
                } else {
                    broadcastDebug("[tts-error] \u751f\u6210\u97f3\u9891\u5931\u8d25");
                }
            } catch (Exception e) {
                broadcastDebug("[tts-error] " + e.getMessage());
                Log.e(TAG, "TTS error", e);
            }
        }).start();
    }

    // ========== File Operations ==========

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    private void copyAssetFolder(String assetFolder, String destPath) throws IOException {
        new File(destPath).mkdirs();
        String[] files = getAssets().list(assetFolder);
        if (files == null) return;

        for (String fileName : files) {
            String assetPath = assetFolder + "/" + fileName;
            String destFilePath = destPath + "/" + fileName;
            String[] subFiles = getAssets().list(assetPath);
            if (subFiles != null && subFiles.length > 0) {
                copyAssetFolder(assetPath, destFilePath);
            } else {
                copyAssetFile(assetPath, destFilePath);
            }
        }
    }

    private void copyAssetFile(String assetPath, String destPath) throws IOException {
        InputStream in = getAssets().open(assetPath);
        FileOutputStream out = new FileOutputStream(destPath);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.close();
    }

    // ========== Broadcast & Notification ==========

    private void broadcastStatus(String status) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra("error", lastError);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        updateNotification(status);
    }

    private void broadcastDebug(String message) {
        Intent intent = new Intent(ACTION_DEBUG);
        intent.putExtra(EXTRA_DEBUG, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, message);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("\u52a0\u8f7d\u4e2d..."));
        return START_STICKY;
    }

    private void updateNotification(String status) {
        String text;
        switch (status) {
            case STATUS_LOADING: text = "\u6b63\u5728\u52a0\u8f7d\u79bb\u7ebf\u6a21\u578b..."; break;
            case STATUS_READY:
            case STATUS_LISTENING: text = "\u79bb\u7ebf\u76d1\u542c\u4e2d... \u8bf4\"\u51e0\u70b9\u4e86\"\u83b7\u53d6\u65f6\u95f4"; break;
            case STATUS_ERROR: text = "\u9519\u8bef: " + lastError; break;
            default: text = "\u8fd0\u884c\u4e2d";
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("\u8bed\u97f3\u52a9\u624b")
                    .setContentText(text)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setOngoing(true)
                    .build());
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("\u8bed\u97f3\u52a9\u624b\u8fd0\u884c\u4e2d")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "\u8bed\u97f3\u52a9\u624b", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    // ========== Lifecycle ==========

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isListening = false;
        modelReady = false;
        ttsReady = false;

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception e) { /* ignore */ }
            audioRecord.release();
            audioRecord = null;
        }
        if (recognitionThread != null) {
            recognitionThread.interrupt();
            recognitionThread = null;
        }
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        if (voskModel != null) { voskModel.close(); voskModel = null; }
        if (tts != null) { tts.release(); tts = null; }
        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
