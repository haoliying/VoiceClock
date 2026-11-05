package com.voiceclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
    private static final int BUFFER_SIZE = 8000;

    public static boolean isRunning = false;

    private Model voskModel;
    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private TextToSpeech textToSpeech;
    private Handler handler;
    private Thread recognitionThread;
    private volatile boolean isListening = false;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        initTTS();
        isRunning = true;
        loadModelAndStart();
    }

    private void loadModelAndStart() {
        new Thread(() -> {
            try {
                // Copy model from assets to internal storage if needed
                String modelPath = getFilesDir().getAbsolutePath() + "/vosk-model";
                File modelDir = new File(modelPath);
                if (!modelDir.exists()) {
                    copyAssetFolder("model", modelPath);
                }

                voskModel = new Model(modelPath);
                recognizer = new Recognizer(voskModel, SAMPLE_RATE);

                initAudioRecord();
                startRecognitionLoop();

                Log.d(TAG, "Vosk model loaded and recognition started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to load Vosk model", e);
            }
        }).start();
    }

    private void copyAssetFolder(String assetFolder, String destPath) throws IOException {
        File destDir = new File(destPath);
        destDir.mkdirs();

        String[] files = getAssets().list(assetFolder);
        if (files == null) return;

        for (String fileName : files) {
            String assetPath = assetFolder + "/" + fileName;
            String destFilePath = destPath + "/" + fileName;

            // Try to list as directory
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
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        out.close();
    }

    private void initAudioRecord() {
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(bufferSize, BUFFER_SIZE)
        );
    }

    private void startRecognitionLoop() {
        isListening = true;
        recognitionThread = new Thread(() -> {
            audioRecord.startRecording();
            byte[] buffer = new byte[BUFFER_SIZE];

            while (isListening) {
                int bytesRead = audioRecord.read(buffer, 0, buffer.length);
                if (bytesRead > 0 && recognizer != null) {
                    if (recognizer.acceptWaveForm(buffer, bytesRead)) {
                        // Final result for an utterance
                        String result = recognizer.getResult();
                        processResult(result);
                    } else {
                        // Partial result
                        String partial = recognizer.getPartialResult();
                        processPartialResult(partial);
                    }
                }
            }
        });
        recognitionThread.start();
    }

    private void processResult(String resultJson) {
        try {
            JSONObject json = new JSONObject(resultJson);
            String text = json.optString("text", "");
            Log.d(TAG, "Recognized: " + text);

            if (containsTimeQuery(text)) {
                handler.post(() -> speakTime());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing result", e);
        }
    }

    private void processPartialResult(String partialJson) {
        try {
            JSONObject json = new JSONObject(partialJson);
            String text = json.optString("partial", "");

            if (containsTimeQuery(text)) {
                handler.post(() -> speakTime());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing partial result", e);
        }
    }

    private boolean containsTimeQuery(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("\u51e0\u70b9\u4e86") || text.contains("\u51e0\u70b9\u5566") || text.contains("\u4ec0\u4e48\u65f6\u95f4")
                || text.contains("\u73b0\u5728\u65f6\u95f4") || text.contains("\u51e0\u65f6") || text.contains("\u51e0\u70b9");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        isListening = false;

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

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("\u8bed\u97f3\u52a9\u624b\u8fd0\u884c\u4e2d")
                .setContentText("\u79bb\u7ebf\u76d1\u542c\u4e2d...\u8bf4\"\u51e0\u70b9\u4e86\"\u83b7\u53d6\u65f6\u95f4")
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build();
    }

    private void initTTS() {
        textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    int result = textToSpeech.setLanguage(Locale.CHINESE);
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Chinese TTS not available, using default");
                        textToSpeech.setLanguage(Locale.getDefault());
                    }
                } else {
                    Log.e(TAG, "TTS init failed: " + status);
                }
            }
        });
    }

    private void speakTime() {
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

        String timeText = "\u73b0\u5728\u662f" + period + displayHour + "\u70b9" + minute + "\u5206";
        Log.d(TAG, "Speaking: " + timeText);

        if (textToSpeech != null) {
            textToSpeech.speak(timeText, TextToSpeech.QUEUE_FLUSH, null, "time_announcement");
        }
    }
}
