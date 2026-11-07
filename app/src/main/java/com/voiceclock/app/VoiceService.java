package com.voiceclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class VoiceService extends Service {

    private static final String TAG = "VoiceService";
    private static final String CHANNEL_ID = "voice_clock_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 16000;

    public static final String ACTION_STATUS = "com.voiceclock.STATUS";
    public static final String ACTION_DEBUG = "com.voiceclock.DEBUG";
    public static final String ACTION_REQUEST_STATUS = "com.voiceclock.REQUEST_STATUS";
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

    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private Handler handler;
    private Thread recognitionThread;
    private volatile boolean isListening = false;
    private volatile boolean modelReady = false;
    private String lastError = "";
    private String currentStatus = STATUS_LOADING;

    // Audio clips for TTS
    private MediaPlayer currentMediaPlayer;

    // Clip name mapping for numbers
    private static final String[] DIGIT_CLIPS = {
        "zero", "one", "two", "three", "four",
        "five", "six", "seven", "eight", "nine"
    };

    private BroadcastReceiver statusRequestReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_REQUEST_STATUS.equals(intent.getAction())) {
                broadcastStatus(currentStatus);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();

        IntentFilter filter = new IntentFilter(ACTION_REQUEST_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusRequestReceiver, filter);

        isRunning = true;
        initAll();
    }

    private void initAll() {
        broadcastStatus(STATUS_LOADING);
        broadcastDebug("[init] \u521d\u59cb\u5316\u4e2d...");

        new Thread(() -> {
            try {
                initVosk();

                if (!initAudioRecord()) {
                    lastError = "AudioRecord init failed";
                    broadcastDebug("[error] \u97f3\u9891\u5f55\u5236\u521d\u59cb\u5316\u5931\u8d25");
                    broadcastStatus(STATUS_ERROR);
                    return;
                }

                broadcastDebug("[ok] \u97f3\u9891\u5f55\u5236\u521d\u59cb\u5316\u6210\u529f");
                broadcastDebug("[tts] \u97f3\u9891\u64ad\u62a5\u5c31\u7eea (\u5185\u7f6e\u97f3\u9891\u7247)");
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

    // ========== Audio Clip Playback ==========

    private synchronized void playClips(List<String> clipNames) {
        if (clipNames == null || clipNames.isEmpty()) return;

        requestAudioFocus();
        playClipsSequential(clipNames, 0);
    }

    private void playClipsSequential(final List<String> clipNames, final int index) {
        if (index >= clipNames.size()) {
            abandonAudioFocus();
            broadcastDebug("[tts-ok] \u64ad\u62a5\u5b8c\u6210");
            return;
        }

        String clipName = clipNames.get(index);
        int resId = getResources().getIdentifier(clipName, "raw", getPackageName());

        if (resId == 0) {
            // Try assets fallback
            broadcastDebug("[tts] \u64ad\u62a5\u97f3\u9891: " + clipName + " (assets)");
            playFromAssets(clipName, clipNames, index);
            return;
        }

        try {
            if (currentMediaPlayer != null) {
                currentMediaPlayer.release();
                currentMediaPlayer = null;
            }

            currentMediaPlayer = MediaPlayer.create(this, resId);
            if (currentMediaPlayer == null) {
                broadcastDebug("[tts-error] \u65e0\u6cd5\u521b\u5efa MediaPlayer: " + clipName);
                playClipsSequential(clipNames, index + 1);
                return;
            }

            currentMediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                if (currentMediaPlayer == mp) {
                    currentMediaPlayer = null;
                }
                playClipsSequential(clipNames, index + 1);
            });

            currentMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                broadcastDebug("[tts-error] \u64ad\u653e\u9519\u8bef: " + clipName + " what=" + what);
                mp.release();
                if (currentMediaPlayer == mp) {
                    currentMediaPlayer = null;
                }
                playClipsSequential(clipNames, index + 1);
                return true;
            });

            currentMediaPlayer.start();
        } catch (Exception e) {
            broadcastDebug("[tts-error] \u64ad\u653e\u5931\u8d25: " + clipName + " - " + e.getMessage());
            playClipsSequential(clipNames, index + 1);
        }
    }

    private void playFromAssets(String clipName, final List<String> clipNames, final int index) {
        try {
            String assetPath = "tts/" + clipName + ".mp3";
            InputStream is = getAssets().open(assetPath);
            
            // Copy to temp file for MediaPlayer
            File tempFile = new File(getCacheDir(), clipName + ".mp3");
            FileOutputStream fos = new FileOutputStream(tempFile);
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
            }
            is.close();
            fos.close();

            if (currentMediaPlayer != null) {
                currentMediaPlayer.release();
                currentMediaPlayer = null;
            }

            currentMediaPlayer = new MediaPlayer();
            currentMediaPlayer.setDataSource(tempFile.getAbsolutePath());
            currentMediaPlayer.prepare();

            currentMediaPlayer.setOnCompletionListener(mp -> {
                mp.release();
                if (currentMediaPlayer == mp) {
                    currentMediaPlayer = null;
                }
                tempFile.delete();
                playClipsSequential(clipNames, index + 1);
            });

            currentMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                mp.release();
                if (currentMediaPlayer == mp) {
                    currentMediaPlayer = null;
                }
                tempFile.delete();
                playClipsSequential(clipNames, index + 1);
                return true;
            });

            currentMediaPlayer.start();
        } catch (Exception e) {
            broadcastDebug("[tts-error] assets\u64ad\u653e\u5931\u8d25: " + clipName + " - " + e.getMessage());
            playClipsSequential(clipNames, index + 1);
        }
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
                broadcastDebug("[\u547d\u4e2d] \u68c0\u6d4b\u5230\u65f6\u95f4\u8be2\u95ee\uff01");
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

    // ========== Speak Time (using audio clips) ==========

    private void speakTime() {
        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        // Build clip sequence
        List<String> clips = new ArrayList<>();
        clips.add("now");   // 现在
        clips.add("is");    // 是

        // Period
        String period;
        if (hour < 6) { period = "dawn"; }        // 凌晨
        else if (hour < 12) { period = "morning"; } // 上午
        else if (hour < 14) { period = "noon"; }    // 中午
        else if (hour < 18) { period = "afternoon"; } // 下午
        else { period = "evening"; }                  // 晚上
        clips.add(period);

        // Hour digits
        addNumberClips(clips, displayHour);
        clips.add("oclock"); // 点

        // Minute digits
        addNumberClips(clips, minute);
        clips.add("minute"); // 分

        String timeText = "\u73b0\u5728\u662f" + getPeriodChinese(hour) + 
                          getNumberChinese(displayHour) + "\u70b9" + 
                          getNumberChinese(minute) + "\u5206";
        broadcastDebug("[tts] \u64ad\u62a5: " + timeText);

        playClips(clips);
    }

    private void addNumberClips(List<String> clips, int num) {
        if (num == 0) {
            clips.add("zero");
        } else if (num < 10) {
            clips.add(DIGIT_CLIPS[num]);
        } else if (num == 10) {
            clips.add("ten");
        } else if (num < 20) {
            clips.add("ten");
            clips.add(DIGIT_CLIPS[num - 10]);
        } else {
            int tens = num / 10;
            int ones = num % 10;
            clips.add(DIGIT_CLIPS[tens]);
            clips.add("ten");
            if (ones > 0) {
                clips.add(DIGIT_CLIPS[ones]);
            }
        }
    }

    private String getPeriodChinese(int hour) {
        if (hour < 6) return "\u51cc\u6668";
        else if (hour < 12) return "\u4e0a\u5348";
        else if (hour < 14) return "\u4e2d\u5348";
        else if (hour < 18) return "\u4e0b\u5348";
        else return "\u665a\u4e0a";
    }

    private String getNumberChinese(int num) {
        String[] digits = {"\u96f6", "\u4e00", "\u4e8c", "\u4e09", "\u56db",
                          "\u4e94", "\u516d", "\u4e03", "\u516b", "\u4e5d"};
        if (num == 0) return digits[0];
        else if (num < 10) return digits[num];
        else if (num == 10) return "\u5341";
        else if (num < 20) return "\u5341" + digits[num - 10];
        else {
            int tens = num / 10;
            int ones = num % 10;
            return digits[tens] + "\u5341" + (ones > 0 ? digits[ones] : "");
        }
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
        currentStatus = status;
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

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusRequestReceiver);
        } catch (Exception e) { /* ignore */ }

        if (currentMediaPlayer != null) {
            try { currentMediaPlayer.stop(); } catch (Exception e) { /* ignore */ }
            currentMediaPlayer.release();
            currentMediaPlayer = null;
        }

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
        abandonAudioFocus();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
