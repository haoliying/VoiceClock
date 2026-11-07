package com.voiceclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
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
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    public static final String EXTRA_ERROR = "error";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_DEBUG = "debug";
    public static final String STATUS_STOPPED = "stopped";
    public static final String STATUS_LOADING = "loading";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_LISTENING = "listening";

    private static final String TTS_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-theresa.tar.bz2";
    private static final String TTS_DIR_NAME = "tts-model";
    private static final String TTS_TMP_DIR_NAME = "tts-model.tmp";
    private static final String TTS_ARCHIVE_REL = "downloads/tts-model.tar.bz2";
    /** 只解压了一半的 onnx 文件不可能这么大（完整的 theresa.onnx 约 117MB），用来判断模型是否完整。 */
    private static final long MIN_MODEL_FILE_SIZE = 100L * 1024 * 1024;
    private static final long HEARTBEAT_INTERVAL_MS = 10000L;

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
    private volatile boolean destroyed = false;
    private volatile long lastSpeakAt = 0L;
    private volatile String lastError = "";
    private volatile String currentStatus = STATUS_LOADING;
    private volatile String loadingDetail = "";
    private PowerManager.WakeLock wakeLock;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            ServiceState.save(VoiceService.this, true, currentStatus, lastError, loadingDetail);
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver statusRequestReceiver = new BroadcastReceiver() {
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

        // 必须在 onCreate 里就进入前台，否则 Android 14 上麦克风采集会直接抛异常
        try {
            Notification notification = buildNotification("正在初始化...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Log.e(TAG, "startForeground failed", e);
            ServiceState.save(this, false, STATUS_ERROR, lastError, "");
            Intent failure = new Intent(ACTION_STATUS);
            failure.putExtra(EXTRA_STATUS, STATUS_ERROR);
            failure.putExtra(EXTRA_ERROR, lastError);
            LocalBroadcastManager.getInstance(this).sendBroadcast(failure);
            stopSelf();
            return;
        }

        IntentFilter filter = new IntentFilter(ACTION_REQUEST_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusRequestReceiver, filter);

        ServiceState.save(this, true, STATUS_LOADING, "", "");
        handler.post(heartbeatRunnable);

        initAll();
    }

    private void initAll() {
        broadcastStatus(STATUS_LOADING);
        broadcastDebug("[init] 初始化中...");

        new Thread(() -> {
            try {
                // 1. Load Vosk model (speech recognition)
                initVosk();

                // 2. Load SherpaOnnx TTS model
                initSherpaOnnxTts();

                // 3. Init AudioRecord
                if (!initAudioRecord()) {
                    lastError = "AudioRecord init failed";
                    broadcastDebug("[error] 音频录制初始化失败");
                    broadcastStatus(STATUS_ERROR);
                    return;
                }

                broadcastDebug("[ok] 全部初始化成功");
                modelReady = true;
                startRecognitionLoop();
                broadcastStatus(STATUS_READY);
                broadcastDebug("[ready] 已就绪，开始监听...请说话");
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
        broadcastDebug("[init] 加载语音识别模型...");

        String modelPath = getFilesDir().getAbsolutePath() + "/vosk-model";
        File modelDir = new File(modelPath);
        File markerFile = new File(modelDir, ".ready");

        if (!markerFile.exists()) {
            broadcastDebug("[init] 首次启动，复制识别模型文件(约66MB)...");
            if (modelDir.exists()) {
                deleteRecursive(modelDir);
            }
            modelDir.mkdirs();
            copyAssetFolder("model", modelPath);
            markerFile.createNewFile();
        }

        voskModel = new Model(modelPath);
        recognizer = new Recognizer(voskModel, SAMPLE_RATE);
        lastError = "";
        broadcastDebug("[ok] 语音识别引擎就绪 (Vosk)");
    }

    // ========== SherpaOnnx TTS ==========

    private void initSherpaOnnxTts() throws Exception {
        broadcastDebug("[tts] 初始化 SherpaOnnx TTS...");

        File modelDir = new File(getFilesDir(), TTS_DIR_NAME);
        File archive = new File(getFilesDir(), TTS_ARCHIVE_REL);

        ModelFiles files = locateModelFiles(modelDir);
        if (files != null) {
            broadcastDebug("[tts] 本地模型已就绪，跳过下载");
            if (archive.exists()) archive.delete();
        } else {
            acquireWakeLock();
            try {
                if (archive.isFile() && archive.length() > 0) {
                    broadcastDebug("[tts] 发现已下载的模型包，尝试续传/解压...");
                } else {
                    broadcastDebug("[tts] 未找到语音模型，开始下载(约116MB，请保持网络畅通)...");
                }
                try {
                    downloadArchiveIfNeeded(archive);
                } catch (IOException e) {
                    // 保留下载了一半的文件，下次启动可以继续续传
                    throw e;
                }
                try {
                    extractArchive(archive, modelDir);
                    archive.delete();
                } catch (IOException e) {
                    // 压缩包损坏或解压失败，删掉重下
                    archive.delete();
                    throw e;
                }
            } finally {
                releaseWakeLock();
            }
            files = locateModelFiles(modelDir);
        }

        if (files == null) {
            throw new Exception("语音模型文件缺失，请检查网络后重新启动服务");
        }

        broadcastDebug("[tts] model: " + files.model);
        broadcastDebug("[tts] lexicon: " + files.lexicon);
        broadcastDebug("[tts] tokens: " + files.tokens);
        if (files.dictDir != null) broadcastDebug("[tts] dict: " + files.dictDir);

        OfflineTtsVitsModelConfig vitsConfig = new OfflineTtsVitsModelConfig();
        vitsConfig.model = files.model;
        vitsConfig.lexicon = files.lexicon != null ? files.lexicon : "";
        vitsConfig.tokens = files.tokens;
        vitsConfig.dictDir = files.dictDir != null ? files.dictDir : "";
        vitsConfig.noiseScale = 0.667f;
        vitsConfig.noiseScaleW = 0.8f;
        vitsConfig.lengthScale = 1.0f;

        OfflineTtsModelConfig modelConfig = new OfflineTtsModelConfig();
        modelConfig.vits = vitsConfig;
        modelConfig.numThreads = 2;
        modelConfig.debug = true;
        modelConfig.provider = "cpu";

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.model = modelConfig;
        config.ruleFsts = files.ruleFsts;
        config.maxNumSentences = 1;

        broadcastDebug("[tts] 加载语音合成引擎(需要一点时间)...");
        setLoadingDetail("正在加载语音合成引擎...");

        tts = new OfflineTts(config);
        ttsSampleRate = tts.getSampleRate();
        ttsReady = true;
        broadcastDebug("[tts-ok] 语音合成引擎就绪, sampleRate=" + ttsSampleRate);
    }

    /** 已解压到位的模型文件集合，缺关键文件时返回 null。 */
    private ModelFiles locateModelFiles(File dir) {
        if (dir == null || !dir.isDirectory()) return null;

        String modelPath = findFile(dir, ".onnx");
        File tokens = findFileByName(dir, "tokens.txt");
        File lexicon = findFileByName(dir, "lexicon.txt");
        if (modelPath == null || tokens == null) return null;
        File model = new File(modelPath);
        if (model.length() < MIN_MODEL_FILE_SIZE || tokens.length() == 0) return null;

        File dictDir = findDir(dir, "dict");
        List<String> rules = new ArrayList<>();
        for (String ruleName : new String[]{"date.fst", "number.fst"}) {
            File rule = findFileByName(dir, ruleName);
            if (rule != null) rules.add(rule.getAbsolutePath());
        }

        return new ModelFiles(
                model.getAbsolutePath(),
                tokens.getAbsolutePath(),
                lexicon != null ? lexicon.getAbsolutePath() : null,
                dictDir != null ? dictDir.getAbsolutePath() : null,
                String.join(",", rules));
    }

    private void downloadArchiveIfNeeded(File archive) throws IOException {
        File parent = archive.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建下载目录");
        }

        long remoteSize = fetchRemoteSize();
        long localSize = archive.isFile() ? archive.length() : 0L;
        if (remoteSize > 0 && localSize == remoteSize) {
            broadcastDebug("[tts] 模型包已完整下载，跳过下载");
            return;
        }
        if (remoteSize > 0 && localSize > remoteSize) {
            archive.delete();
        }

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                downloadOnce(archive);
                return;
            } catch (IOException e) {
                lastFailure = e;
                broadcastDebug("[tts] 下载失败(" + attempt + "/3): " + e.getMessage());
                setLoadingDetail("下载中断，正在重试(" + attempt + "/3)...");
                sleepQuietly(2000);
            }
        }
        throw new IOException("模型下载失败: "
                + (lastFailure != null ? lastFailure.getMessage() : "未知错误"), lastFailure);
    }

    private void downloadOnce(File archive) throws IOException {
        long startAt = archive.isFile() ? archive.length() : 0L;
        if (startAt < 0) startAt = 0;

        HttpURLConnection conn = openConnection(TTS_MODEL_URL, startAt, "GET");
        int responseCode;
        try {
            responseCode = conn.getResponseCode();
        } catch (IOException e) {
            conn.disconnect();
            throw e;
        }

        boolean append;
        long expectedTotal;
        if (responseCode == HttpURLConnection.HTTP_PARTIAL && startAt > 0) {
            append = true;
            long len = conn.getContentLengthLong();
            expectedTotal = len > 0 ? startAt + len : -1;
        } else if (responseCode == HttpURLConnection.HTTP_OK) {
            append = false;
            expectedTotal = conn.getContentLengthLong();
            startAt = 0;
        } else if (responseCode == 416) {
            // 本地文件已经到末尾但服务器大小对不上，重新下载
            conn.disconnect();
            archive.delete();
            throw new IOException("HTTP 416，重新下载");
        } else {
            conn.disconnect();
            throw new IOException("HTTP " + responseCode);
        }

        broadcastDebug("[tts] 下载: HTTP " + responseCode + "，已下载 "
                + (startAt / 1024 / 1024) + "MB"
                + (expectedTotal > 0 ? " / 共 " + (expectedTotal / 1024 / 1024) + "MB" : ""));

        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(archive, append);
        long written = startAt;
        int lastPercent = -1;
        try {
            byte[] buffer = new byte[128 * 1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                written += bytesRead;

                if (expectedTotal > 0) {
                    int percent = (int) (written * 100 / expectedTotal);
                    if (percent >= lastPercent + 2 || percent == 100) {
                        lastPercent = percent;
                        int safePercent = Math.min(percent, 100);
                        setLoadingDetail("正在下载语音模型 " + safePercent + "%（"
                                + (written / 1024 / 1024) + "/" + (expectedTotal / 1024 / 1024) + "MB）");
                    }
                }
            }
        } finally {
            try { out.flush(); } catch (IOException ignored) { }
            try { out.close(); } catch (IOException ignored) { }
            try { in.close(); } catch (IOException ignored) { }
            conn.disconnect();
        }

        if (expectedTotal > 0 && archive.length() != expectedTotal) {
            long actual = archive.length();
            if (actual > expectedTotal) archive.delete(); // 续传位置错了，下次从头来
            throw new IOException("文件不完整: " + actual + "/" + expectedTotal);
        }
        broadcastDebug("[tts] 下载完成: " + (archive.length() / 1024 / 1024) + "MB");
    }

    private HttpURLConnection openConnection(String urlStr, long rangeStart, String method) throws IOException {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 5; hop++) {
            URL url = new URL(currentUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "VoiceClock/1.1");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setInstanceFollowRedirects(false);
            if (rangeStart > 0) {
                conn.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null) throw new IOException("重定向缺少 Location");
                currentUrl = new URL(url, location).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    /** 通过 HEAD 请求拿到服务器上的文件大小，用来判断本地压缩包是否已经下载完整。 */
    private long fetchRemoteSize() {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(TTS_MODEL_URL, 0, "HEAD");
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                return conn.getContentLengthLong();
            }
        } catch (Exception e) {
            Log.w(TAG, "HEAD request failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return -1;
    }

    private void extractArchive(File archive, File finalDir) throws IOException {
        File tmpDir = new File(getFilesDir(), TTS_TMP_DIR_NAME);
        deleteRecursive(tmpDir);
        if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) {
            throw new IOException("无法创建临时目录");
        }

        long archiveSize = archive.length();
        broadcastDebug("[tts] 解压中 (" + (archiveSize / 1024 / 1024) + "MB)...");
        setLoadingDetail("正在解压语音模型 0%");

        int fileCount = 0;
        int lastPercent = -1;
        long writtenBytes = 0;
        long lastProgressAt = 0;
        long lastLogAt = 0;
        CountingInputStream counting = null;
        BZip2CompressorInputStream bzIn = null;
        TarArchiveInputStream tarIn = null;

        try {
            counting = new CountingInputStream(new FileInputStream(archive));
            bzIn = new BZip2CompressorInputStream(counting, true);
            tarIn = new TarArchiveInputStream(bzIn);

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                if (entry.isDirectory() || entry.isLink() || entry.isSymbolicLink()) continue;

                String name = entry.getName().replace('\\', '/');
                int slashIndex = name.indexOf('/');
                if (slashIndex >= 0) name = name.substring(slashIndex + 1);
                if (name.isEmpty() || name.contains("..")) continue;

                File outputFile = new File(tmpDir, name);
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                FileOutputStream out = new FileOutputStream(outputFile);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int len;
                    // 进度必须按字节更新：最大的 onnx 是一个 117MB 的单独条目，
                    // 如果只在文件之间更新，解压期间进度会一直停在上一个文件的位置
                    while ((len = tarIn.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                        writtenBytes += len;

                        if (archiveSize > 0) {
                            int percent = (int) (counting.count * 100 / archiveSize);
                            long now = SystemClock.elapsedRealtime();
                            if (percent >= lastPercent + 1 || now - lastProgressAt >= 2000) {
                                lastPercent = percent;
                                lastProgressAt = now;
                                setLoadingDetail("正在解压语音模型 " + Math.min(percent, 100)
                                        + "%（已写入 " + (writtenBytes / 1024 / 1024) + "MB）");
                            }
                            if (now - lastLogAt >= 10000) {
                                lastLogAt = now;
                                broadcastDebug("[tts] 解压 " + Math.min(percent, 100) + "%（"
                                        + (writtenBytes / 1024 / 1024) + "MB）");
                            }
                        }
                    }
                } finally {
                    out.close();
                }
                fileCount++;
            }
        } finally {
            closeQuietly(tarIn);
            closeQuietly(bzIn);
            closeQuietly(counting);
        }

        broadcastDebug("[tts] 解压完成，共 " + fileCount + " 个文件");

        if (locateModelFiles(tmpDir) == null) {
            deleteRecursive(tmpDir);
            throw new IOException("解压后缺少模型文件");
        }

        deleteRecursive(finalDir);
        if (!tmpDir.renameTo(finalDir)) {
            deleteRecursive(tmpDir);
            throw new IOException("无法写入模型目录");
        }
        broadcastDebug("[tts] 模型已就绪");
    }

    private String findFile(File dir, String suffix) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;

        for (File f : files) {
            if (!f.isDirectory() && f.getName().endsWith(suffix)) return f.getAbsolutePath();
        }
        for (File f : files) {
            if (f.isDirectory()) {
                String result = findFile(f, suffix);
                if (result != null) return result;
            }
        }
        return null;
    }

    private File findFileByName(File dir, String name) {
        String path = findFile(dir, name);
        return path != null ? new File(path) : null;
    }

    private File findDir(File dir, String name) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory() && f.getName().equals(name)) return f;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File result = findDir(f, name);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static final class ModelFiles {
        final String model;
        final String tokens;
        final String lexicon;
        final String dictDir;
        final String ruleFsts;

        ModelFiles(String model, String tokens, String lexicon, String dictDir, String ruleFsts) {
            this.model = model;
            this.tokens = tokens;
            this.lexicon = lexicon;
            this.dictDir = dictDir;
            this.ruleFsts = ruleFsts;
        }
    }

    private static final class CountingInputStream extends FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceClock:model");
                wakeLock.setReferenceCounted(false);
            }
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(15 * 60 * 1000L);
            }
        } catch (Exception e) {
            Log.w(TAG, "acquireWakeLock failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            Log.w(TAG, "releaseWakeLock failed", e);
        }
    }

    // ========== Audio Playback ==========

    private void playAudio(float[] samples, int sampleRate) {
        if (samples == null || samples.length == 0) return;

        short[] shortSamples = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = Math.max(-1.0f, Math.min(1.0f, samples[i]));
            shortSamples[i] = (short) (s * Short.MAX_VALUE);
        }

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

        try {
            Thread.sleep((long) (samples.length * 1000.0 / sampleRate) + 200);
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
            lastError = "缺少录音权限";
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
            try {
                audioRecord.startRecording();
            } catch (Exception e) {
                lastError = "无法读取麦克风: " + e.getMessage();
                broadcastDebug("[error] " + lastError);
                broadcastStatus(STATUS_ERROR);
                return;
            }
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
            broadcastDebug("[识别] " + text);
            if (containsTimeQuery(text)) {
                broadcastDebug("[命中] 检测到时间询问！");
                maybeSpeakTime();
            }
        } catch (Exception e) {
            broadcastDebug("[error] 解析结果失败");
        }
    }

    private void processPartialResult(String partialJson) {
        try {
            String text = new JSONObject(partialJson).optString("partial", "");
            if (!text.isEmpty()) {
                broadcastDebug("[语音] " + text);
            }
            if (containsTimeQuery(text)) {
                broadcastDebug("[命中] 检测到时间询问！");
                maybeSpeakTime();
            }
        } catch (Exception e) {
            Log.e(TAG, "Partial result error", e);
        }
    }

    private boolean containsTimeQuery(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("几点了") || text.contains("几点啦")
                || text.contains("什么时间") || text.contains("现在时间")
                || text.contains("几时") || text.contains("几点");
    }

    /** 同一次提问会连续出现多个 partial 结果，加个冷却避免重复播报。 */
    private void maybeSpeakTime() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSpeakAt < 4000L) return;
        lastSpeakAt = now;
        handler.post(this::speakTime);
    }

    // ========== Speak Time ==========

    private void speakTime() {
        if (!ttsReady || tts == null) {
            broadcastDebug("[tts-error] TTS 未就绪");
            return;
        }

        Calendar calendar = Calendar.getInstance();
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);

        String period;
        if (hour < 6) period = "凌晨";
        else if (hour < 12) period = "上午";
        else if (hour < 14) period = "中午";
        else if (hour < 18) period = "下午";
        else period = "晚上";

        int displayHour = hour % 12;
        if (displayHour == 0) displayHour = 12;

        String timeText = "现在是" + period + toChineseNumber(displayHour) + "点"
                + (minute == 0 ? "整" : toChineseNumber(minute) + "分");
        broadcastDebug("[tts] 播报: " + timeText);

        new Thread(() -> {
            try {
                GeneratedAudio audio = tts.generate(timeText);
                if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0) {
                    broadcastDebug("[tts-ok] 生成音频 " + audio.getSamples().length + " 采样点");
                    playAudio(audio.getSamples(), audio.getSampleRate());
                    broadcastDebug("[tts-ok] 播报完成");
                } else {
                    broadcastDebug("[tts-error] 生成音频失败");
                }
            } catch (Exception e) {
                broadcastDebug("[tts-error] " + e.getMessage());
                Log.e(TAG, "TTS error", e);
            }
        }).start();
    }

    /** 把 0~59 转成中文数字，避免 TTS 把阿拉伯数字读错或直接跳过。 */
    private static String toChineseNumber(int value) {
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (value < 10) return digits[value];
        int tens = value / 10;
        int units = value % 10;
        StringBuilder sb = new StringBuilder();
        if (tens == 1) {
            sb.append("十");
        } else {
            sb.append(digits[tens]).append("十");
        }
        if (units > 0) sb.append(digits[units]);
        return sb.toString();
    }

    // ========== File Operations ==========

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
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
        byte[] buffer = new byte[64 * 1024];
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
        if (!STATUS_LOADING.equals(status)) loadingDetail = "";
        ServiceState.save(this, true, status, lastError, loadingDetail);

        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, status);
        intent.putExtra(EXTRA_ERROR, lastError);
        intent.putExtra(EXTRA_DETAIL, loadingDetail);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        updateNotification(status);
    }

    private void setLoadingDetail(String detail) {
        loadingDetail = detail;
        ServiceState.save(this, true, currentStatus, lastError, detail);

        Intent intent = new Intent(ACTION_STATUS);
        intent.putExtra(EXTRA_STATUS, currentStatus);
        intent.putExtra(EXTRA_ERROR, lastError);
        intent.putExtra(EXTRA_DETAIL, detail);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        updateNotification(currentStatus);
    }

    private void broadcastDebug(String message) {
        Intent intent = new Intent(ACTION_DEBUG);
        intent.putExtra(EXTRA_DEBUG, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, message);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 前台通知已经在 onCreate 中启动，这里只需要保证进程被系统回收后能自动恢复
        return START_STICKY;
    }

    private void updateNotification(String status) {
        String text;
        switch (status) {
            case STATUS_LOADING:
                text = loadingDetail.isEmpty() ? "正在准备离线模型..." : loadingDetail;
                break;
            case STATUS_READY:
            case STATUS_LISTENING:
                text = "监听中... 说“几点了”获取时间";
                break;
            case STATUS_ERROR:
                text = "错误: " + lastError;
                break;
            case STATUS_STOPPED:
                text = "已停止";
                break;
            default:
                text = "运行中";
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("语音时钟运行中")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "语音时钟", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    // ========== Lifecycle ==========

    @Override
    public void onDestroy() {
        destroyed = true;
        isListening = false;
        modelReady = false;
        ttsReady = false;

        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(statusRequestReceiver);
        } catch (Exception e) { /* ignore */ }

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
        releaseWakeLock();
        handler.removeCallbacksAndMessages(null);

        ServiceState.save(this, false, STATUS_STOPPED, "", "");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return new android.os.Binder(); }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try { closeable.close(); } catch (IOException ignored) { }
    }
}
