package com.voiceclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Locale;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

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

    private Model voskModel;
    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private TextToSpeech textToSpeech;
    private Handler handler;
    private Thread recognitionThread;
    private volatile boolean isListening = false;
    private volatile boolean modelReady = false;
    private volatile boolean ttsReady = false;
    private String lastError = "";
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        initTTS();
        isRunning = true;
        loadModelAndStart();
    }

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
    }

    private void loadModelAndStart() {
        broadcastStatus(STATUS_LOADING);
        broadcastDebug("[init] \u5f00\u59cb\u52a0\u8f7d\u79bb\u7ebf\u6a21\u578b...");
        new Thread(() -> {
            try {
                Log.d(TAG, "Starting model load...");

                String modelPath = getFilesDir().getAbsolutePath() + "/vosk-model";
                File modelDir = new File(modelPath);
                File markerFile = new File(modelDir, ".ready");

                if (!markerFile.exists()) {
                    broadcastDebug("[init] \u6b63\u5728\u590d\u5236\u6a21\u578b\u6587\u4ef6...");
                    Log.d(TAG, "Copying model from assets to internal storage...");
                    if (modelDir.exists()) {
                        deleteRecursive(modelDir);
                    }
                    modelDir.mkdirs();
                    copyAssetFolder("model", modelPath);
                    markerFile.createNewFile();
                    broadcastDebug("[init] \u6a21\u578b\u590d\u5236\u5b8c\u6210");
                    Log.d(TAG, "Model copy complete");
                } else {
                    Log.d(TAG, "Model already copied, skipping");
                }

                broadcastDebug("[init] \u6b63\u5728\u52a0\u8f7d Vosk \u6a21\u578b...");
                Log.d(TAG, "Loading Vosk model from: " + modelPath);
                voskModel = new Model(modelPath);
                broadcastDebug("[ok] Vosk \u6a21\u578b\u52a0\u8f7d\u6210\u529f");
                Log.d(TAG, "Vosk model loaded successfully");

                recognizer = new Recognizer(voskModel, SAMPLE_RATE);
                Log.d(TAG, "Recognizer created at sample rate: " + SAMPLE_RATE);

                if (!initAudioRecord()) {
                    lastError = "AudioRecord init failed";
                    broadcastDebug("[error] \u97f3\u9891\u5f55\u5236\u521d\u59cb\u5316\u5931\u8d25");
                    Log.e(TAG, "AudioRecord initialization failed!");
                    broadcastStatus(STATUS_ERROR);
                    return;
                }

                broadcastDebug("[ok] \u97f3\u9891\u5f55\u5236\u521d\u59cb\u5316\u6210\u529f");

                // Wait for TTS to be ready (up to 5 seconds)
                broadcastDebug("[tts] \u7b49\u5f85\u8bed\u97f3\u5408\u6210\u5f15\u64ce\u5c31\u7eea...");
                int waitCount = 0;
                while (!ttsReady && waitCount < 50) {
                    Thread.sleep(100);
                    waitCount++;
                }

                if (!ttsReady) {
                    broadcastDebug("[tts-warn] TTS \u5f15\u64ce\u672a\u5c31\u7eea\uff0c\u5c1d\u8bd5\u56de\u9000\u65b9\u6848...");
                    initTTSFallback();
                }

                modelReady = true;
                startRecognitionLoop();
                broadcastStatus(STATUS_READY);
                broadcastDebug("[ready] \u5df2\u5c31\u7eea\uff0c\u5f00\u59cb\u76d1\u542c...\u8bf7\u8bf4\u8bdd");
                Log.d(TAG, "Voice service fully started and listening");

            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                broadcastDebug("[error] " + lastError);
                Log.e(TAG, "Failed to start voice service: " + lastError, e);
                broadcastStatus(STATUS_ERROR);
            }
        }).start();
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private void copyAssetFolder(String assetFolder, String destPath) throws IOException {
        File destDir = new File(destPath);
        destDir.mkdirs();

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

    private boolean initAudioRecord() {
        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        Log.d(TAG, "AudioRecord min buffer size: " + minBufferSize);

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid min buffer size: " + minBufferSize);
            return false;
        }

        int bufferSize = Math.max(minBufferSize, 16000);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
        } catch (SecurityException e) {
            Log.e(TAG, "No RECORD_AUDIO permission!", e);
            lastError = "Permission denied";
            return false;
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord constructor failed", e);
            lastError = e.getMessage();
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized, state: " + audioRecord.getState());
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        Log.d(TAG, "AudioRecord initialized successfully, buffer: " + bufferSize);
        return true;
    }

    private void startRecognitionLoop() {
        isListening = true;
        broadcastStatus(STATUS_LISTENING);

        recognitionThread = new Thread(() -> {
            audioRecord.startRecording();
            Log.d(TAG, "AudioRecord started recording");

            byte[] buffer = new byte[8192];
            int loopCount = 0;

            while (isListening) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);

                if (bytesRead < 0) {
                    Log.e(TAG, "AudioRecord read error: " + bytesRead);
                    break;
                }

                if (bytesRead > 0 && recognizer != null && modelReady) {
                    try {
                        if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                            String result = recognizer.getResult();
                            processResult(result);
                        } else {
                            String partial = recognizer.getPartialResult();
                            processPartialResult(partial);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Recognition error", e);
                    }
                }

                loopCount++;
                if (loopCount % 50 == 0) {
                    Log.d(TAG, "Recognition loop alive, processed " + loopCount + " buffers");
                }
            }

            Log.d(TAG, "Recognition loop exited");
        });
        recognitionThread.start();
    }

    private void processResult(String resultJson) {
        try {
            JSONObject json = new JSONObject(resultJson);
            String text = json.optString("text", "");
            Log.d(TAG, "Final result: [" + text + "]");
            broadcastDebug("[\u8bc6\u522b] " + text);

            if (containsTimeQuery(text)) {
                broadcastDebug("[\u547d\u4e2d] \u68c0\u6d4b\u5230\u65f6\u95f4\u8be2\u95ee\uff01");
                handler.post(() -> speakTime());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing result", e);
            broadcastDebug("[error] \u89e3\u6790\u7ed3\u679c\u5931\u8d25");
        }
    }

    private void processPartialResult(String partialJson) {
        try {
            JSONObject json = new JSONObject(partialJson);
            String text = json.optString("partial", "");

            if (!text.isEmpty()) {
                Log.d(TAG, "Partial: [" + text + "]");
                broadcastDebug("[\u8bed\u97f3] " + text);
            }

            if (containsTimeQuery(text)) {
                broadcastDebug("[\u547d\u4e2d] \u68c0\u6d4b\u5230\u65f6\u95f4\u8be2\u95ee\uff01");
                handler.post(() -> speakTime());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing partial result", e);
        }
    }

    private boolean containsTimeQuery(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("\u51e0\u70b9\u4e86")
                || text.contains("\u51e0\u70b9\u5566")
                || text.contains("\u4ec0\u4e48\u65f6\u95f4")
                || text.contains("\u73b0\u5728\u65f6\u95f4")
                || text.contains("\u51e0\u65f6")
                || text.contains("\u51e0\u70b9");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification("\u52a0\u8f7d\u4e2d...");
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    private void updateNotification(String status) {
        String text;
        switch (status) {
            case STATUS_LOADING:
                text = "\u6b63\u5728\u52a0\u8f7d\u79bb\u7ebf\u6a21\u578b...";
                break;
            case STATUS_READY:
            case STATUS_LISTENING:
                text = "\u79bb\u7ebf\u76d1\u542c\u4e2d... \u8bf4\u201c\u51e0\u70b9\u4e86\u201d\u83b7\u53d6\u65f6\u95f4";
                break;
            case STATUS_ERROR:
                text = "\u9519\u8bef: " + lastError;
                break;
            default:
                text = "\u8fd0\u884c\u4e2d";
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("\u8bed\u97f3\u52a9\u624b")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isListening = false;
        modelReady = false;

        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                // ignore
            }
            audioRecord.release();
            audioRecord = null;
        }

        if (recognitionThread != null) {
            recognitionThread.interrupt();
            recognitionThread = null;
        }

        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }

        if (voskModel != null) {
            voskModel.close();
            voskModel = null;
        }

        if (textToSpeech != null) {
            textToSpeech.shutdown();
            textToSpeech = null;
        }

        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "\u8bed\u97f3\u52a9\u624b",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("\u8bed\u97f3\u65f6\u949f\u52a9\u624b\u901a\u77e5");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // ========== TTS Management ==========

    private void initTTS() {
        broadcastDebug("[tts] \u521d\u59cb\u5316\u8bed\u97f3\u5408\u6210\u5f15\u64ce...");

        textToSpeech = new TextToSpeech(this, new OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    configureTTS(textToSpeech, "default");
                } else {
                    broadcastDebug("[tts-error] \u5f15\u64ce\u521d\u59cb\u5316\u5931\u8d25, status=" + status);
                    Log.e(TAG, "TTS init failed with status: " + status);
                }
            }
        });
    }

    private void initTTSFallback() {
        broadcastDebug("[tts] \u5c1d\u8bd5\u56de\u9000\u65b9\u6848: \u4f7f\u7528\u82f1\u6587 TTS + \u6570\u5b57\u62a5\u65f6...");

        if (textToSpeech != null) {
            textToSpeech.shutdown();
        }

        // Try with English as fallback - numbers are universal
        textToSpeech = new TextToSpeech(this, new OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int result = textToSpeech.setLanguage(Locale.ENGLISH);
                    if (result != TextToSpeech.LANG_MISSING_DATA
                            && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                        ttsReady = true;
                        broadcastDebug("[tts-ok] \u82f1\u6587 TTS \u56de\u9000\u6210\u529f");
                        setupTTSListeners();
                    } else {
                        broadcastDebug("[tts-error] \u82f1\u6587 TTS \u4e5f\u4e0d\u53ef\u7528");
                    }
                } else {
                    broadcastDebug("[tts-error] \u56de\u9000\u5f15\u64ce\u4e5f\u5931\u8d25");
                }
            }
        });
    }

    private void configureTTS(TextToSpeech tts, String engineName) {
        broadcastDebug("[tts] \u914d\u7f6e " + engineName + " \u5f15\u64ce...");

        // Try Chinese first
        int result = tts.setLanguage(Locale.CHINESE);
        broadcastDebug("[tts] setLanguage(CHINESE) = " + result);

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            broadcastDebug("[tts-warn] \u4e2d\u6587\u8bed\u97f3\u6570\u636e\u7f3a\u5931\uff0c\u5c1d\u8bd5\u5176\u4ed6\u8bed\u8a00...");

            // Try Simplified Chinese
            result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            broadcastDebug("[tts] setLanguage(SIMPLIFIED_CHINESE) = " + result);

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Try Taiwan Chinese
                result = tts.setLanguage(Locale.TRADITIONAL_CHINESE);
                broadcastDebug("[tts] setLanguage(TRADITIONAL_CHINESE) = " + result);

                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Last resort: try default locale
                    result = tts.setLanguage(Locale.getDefault());
                    broadcastDebug("[tts] setLanguage(default=" + Locale.getDefault().getDisplayName() + ") = " + result);

                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        broadcastDebug("[tts-error] \u65e0\u4efb\u4f55\u53ef\u7528\u8bed\u8a00\uff0cTTS \u4e0d\u53ef\u7528");
                        ttsReady = false;
                        return;
                    }
                }
            }
        }

        ttsReady = true;
        broadcastDebug("[tts-ok] \u8bed\u97f3\u5408\u6210\u5f15\u64ce\u5c31\u7eea (lang=" + tts.getLanguage().getDisplayName() + ")");
        setupTTSListeners();
    }

    private void setupTTSListeners() {
        if (textToSpeech == null) return;

        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "TTS started: " + utteranceId);
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "TTS done: " + utteranceId);
                abandonAudioFocus();
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "TTS error: " + utteranceId);
                broadcastDebug("[tts-error] \u64ad\u62a5\u5931\u8d25: " + utteranceId);
                abandonAudioFocus();
            }
        });
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();

            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            Log.d(TAG, "Audio focus changed: " + focusChange);
                        }
                    })
                    .build();

            int result = audioManager.requestAudioFocus(audioFocusRequest);
            return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } else {
            @SuppressWarnings("deprecation")
            int result = audioManager.requestAudioFocus(
                    new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            Log.d(TAG, "Audio focus changed: " + focusChange);
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            );
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
            @SuppressWarnings("deprecation")
            AudioManager.OnAudioFocusChangeListener listener = null;
            audioManager.abandonAudioFocus(listener);
        }
    }

    private void speakTime() {
        if (!ttsReady) {
            broadcastDebug("[tts-error] TTS \u672a\u5c31\u7eea\uff0c\u65e0\u6cd5\u64ad\u62a5");
            return;
        }

        if (!requestAudioFocus()) {
            broadcastDebug("[tts-warn] \u97f3\u9891\u805a\u7126\u83b7\u53d6\u5931\u8d25");
        }

        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        String period;
        if (hour < 6) {
            period = "\u51cc\u6668";
        } else if (hour < 12) {
            period = "\u4e0a\u5348";
        } else if (hour < 14) {
            period = "\u4e2d\u5348";
        } else if (hour < 18) {
            period = "\u4e0b\u5348";
        } else {
            period = "\u665a\u4e0a";
        }

        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        Locale ttsLocale = textToSpeech.getLanguage();
        String timeText;

        // Use Chinese if available, otherwise use English with numbers
        if (ttsLocale != null && (ttsLocale.getLanguage().equals("zh")
                || ttsLocale.equals(Locale.CHINESE)
                || ttsLocale.equals(Locale.SIMPLIFIED_CHINESE)
                || ttsLocale.equals(Locale.TRADITIONAL_CHINESE))) {
            timeText = "\u73b0\u5728\u662f" + period + displayHour
                    + "\u70b9" + minute + "\u5206";
        } else {
            // English fallback: "Now is 3:25"
            timeText = "Now is " + displayHour + ":" + String.format("%02d", minute);
        }

        broadcastDebug("[tts] \u64ad\u62a5: " + timeText);
        Log.d(TAG, "Speaking: " + timeText);

        int speakResult = textToSpeech.speak(timeText, TextToSpeech.QUEUE_FLUSH, null, "time_announcement");

        if (speakResult == TextToSpeech.SUCCESS) {
            broadcastDebug("[tts-ok] \u64ad\u62a5\u6307\u4ee4\u5df2\u53d1\u9001");
        } else if (speakResult == TextToSpeech.ERROR) {
            broadcastDebug("[tts-error] speak() \u8fd4\u56de ERROR");
        } else {
            broadcastDebug("[tts-warn] speak() \u8fd4\u56de: " + speakResult);
        }
    }
}
