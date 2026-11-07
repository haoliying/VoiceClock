package com.voiceclock.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int MAX_DEBUG_LINES = 50;

    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvDebug;
    private boolean serviceRunning = false;
    private boolean bound = false;
    private final StringBuilder debugLog = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            serviceRunning = true;
            refreshStatusText();
            LocalBroadcastManager.getInstance(MainActivity.this)
                    .sendBroadcast(new Intent(VoiceService.ACTION_REQUEST_STATUS));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceRunning = false;
            applyStatus(VoiceService.STATUS_STOPPED, "", "");
        }
    };

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // 能收到服务的广播，说明服务一定还活着
            serviceRunning = true;
            applyStatus(intent.getStringExtra(VoiceService.EXTRA_STATUS),
                    intent.getStringExtra(VoiceService.EXTRA_ERROR),
                    intent.getStringExtra(VoiceService.EXTRA_DETAIL));
        }
    };

    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(VoiceService.EXTRA_DEBUG);
            if (message != null) {
                appendDebug(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnStartStop = findViewById(R.id.btnStartStop);
        tvStatus = findViewById(R.id.tvStatus);
        tvDebug = findViewById(R.id.tvDebug);

        requestPermissions();

        btnStartStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serviceRunning) {
                    startVoiceService();
                } else {
                    stopVoiceService();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(statusReceiver, new IntentFilter(VoiceService.ACTION_STATUS));
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(debugReceiver, new IntentFilter(VoiceService.ACTION_DEBUG));
        refreshServiceState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(debugReceiver);
        if (bound) {
            try {
                unbindService(serviceConnection);
            } catch (Exception ignored) {
                // 已经解绑
            }
            bound = false;
        }
    }

    /**
     * 用绑定服务的方式确认服务是否真的在运行，绑定失败即表示服务没启动。
     * 同时读取服务写入的状态，保证按钮和下面文字始终一致。
     */
    private void refreshServiceState() {
        boolean alive;
        try {
            alive = bindService(new Intent(this, VoiceService.class), serviceConnection, 0);
        } catch (Exception e) {
            alive = false;
        }

        if (alive) {
            bound = true;
            serviceRunning = true;
            refreshStatusText();
            LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(new Intent(VoiceService.ACTION_REQUEST_STATUS));
        } else {
            bound = false;
            serviceRunning = false;
            applyStatus(VoiceService.STATUS_STOPPED, "", "");
        }
    }

    /** 先用服务保存的状态占位，随后会收到服务的实时状态。 */
    private void refreshStatusText() {
        ServiceState.Snapshot snapshot = ServiceState.load(this);
        if (snapshot.isFresh()) {
            applyStatus(snapshot.status, snapshot.error, snapshot.detail);
        } else {
            applyStatus(VoiceService.STATUS_LOADING, "", "正在连接服务...");
        }
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void startVoiceService() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            appendDebug("请先授予录音权限，再启动服务");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
            return;
        }

        debugLog.setLength(0);
        tvDebug.setText("");

        try {
            Intent intent = new Intent(this, VoiceService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            serviceRunning = true;
            applyStatus(VoiceService.STATUS_LOADING, "", "服务启动中...");
            appendDebug("服务启动中...");
        } catch (Exception e) {
            serviceRunning = false;
            applyStatus(VoiceService.STATUS_ERROR,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), "");
            appendDebug("启动失败: " + e.getMessage());
        }
    }

    private void stopVoiceService() {
        try {
            stopService(new Intent(this, VoiceService.class));
        } catch (Exception e) {
            appendDebug("停止服务失败: " + e.getMessage());
        }
        serviceRunning = false;
        ServiceState.save(this, false, VoiceService.STATUS_STOPPED, "", "");
        applyStatus(VoiceService.STATUS_STOPPED, "", "");
        appendDebug("服务已停止");
    }

    private void applyStatus(String status, String error, String detail) {
        String text;
        if (VoiceService.STATUS_LOADING.equals(status)) {
            text = "服务状态：" + (detail != null && !detail.isEmpty() ? detail : "正在加载模型...");
        } else if (VoiceService.STATUS_READY.equals(status)
                || VoiceService.STATUS_LISTENING.equals(status)) {
            text = "服务状态：监听中，说“几点了”试试";
        } else if (VoiceService.STATUS_ERROR.equals(status)) {
            text = "服务状态：出错 - " + (error != null ? error : "");
        } else {
            text = "服务状态：已停止";
        }

        tvStatus.setText(text);
        btnStartStop.setText(serviceRunning ? "停止服务" : "启动服务");
    }

    private void appendDebug(String message) {
        String time = timeFormat.format(new Date());
        String line = "[" + time + "] " + message + "\n";
        debugLog.append(line);

        // Keep only last N lines to avoid memory issues
        String[] lines = debugLog.toString().split("\n");
        if (lines.length > MAX_DEBUG_LINES) {
            debugLog.setLength(0);
            int start = lines.length - MAX_DEBUG_LINES;
            for (int i = start; i < lines.length; i++) {
                debugLog.append(lines[i]).append("\n");
            }
        }

        tvDebug.setText(debugLog.toString());
    }
}
