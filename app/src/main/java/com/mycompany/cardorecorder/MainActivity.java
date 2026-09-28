package com.mycompany.cardorecorder;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int REQ_PERMS = 1;
    private static final long SCO_TIMEOUT_MS = 6000;

    private AudioManager audioManager;
    private AudioRecord recorder;
    private Thread recordThread;
    private volatile boolean isRecording = false;
    private boolean waitingForSco = false;
    private File outputFile;

    private TextView status;
    private Button btnStart;
    private Button btnStop;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scoTimeout = () -> {
        if (waitingForSco) {
            waitingForSco = false;
            setStatus("SCO no conectó en " + (SCO_TIMEOUT_MS / 1000) + "s. Grabando con mic por defecto.");
            startRecording(null);
        }
    };

    // Solo para Android < 12: estado de la línea SCO legacy.
    private final BroadcastReceiver scoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED && waitingForSco) {
                waitingForSco = false;
                handler.removeCallbacks(scoTimeout);
                setStatus("SCO conectado (legacy)");
                startRecording(findInputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        status = findViewById(R.id.status);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(v -> {
            if (hasPermissions()) {
                openScoAndRecord();
            } else {
                requestPermissions(requiredPermissions(), REQ_PERMS);
            }
        });
        btnStop.setOnClickListener(v -> stopAll());
    }

    @Override
    protected void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMS) {
            if (hasPermissions()) {
                openScoAndRecord();
            } else {
                setStatus("Permisos denegados (micrófono / Bluetooth)");
            }
        }
    }

    private String[] requiredPermissions() {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        return perms.toArray(new String[0]);
    }

    private boolean hasPermissions() {
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // 1. Abrir la línea de voz Bluetooth SCO con el Cardo
    private void openScoAndRecord() {
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo sco = null;
            for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    sco = d;
                    break;
                }
            }
            if (sco != null && audioManager.setCommunicationDevice(sco)) {
                setStatus("SCO activo: " + sco.getProductName());
                startRecording(findInputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
            } else {
                setStatus("No se encontró el Cardo como dispositivo SCO. Grabando con mic por defecto.");
                startRecording(null);
            }
        } else {
            IntentFilter filter = new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);
            registerReceiver(scoReceiver, filter);
            waitingForSco = true;
            setStatus("Conectando SCO...");
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
            handler.postDelayed(scoTimeout, SCO_TIMEOUT_MS);
        }
    }

    private AudioDeviceInfo findInputDevice(int type) {
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == type) {
                return d;
            }
        }
        return null;
    }

    // 2. Iniciar captura con VOICE_COMMUNICATION y guardar a WAV
    private void startRecording(AudioDeviceInfo preferredInput) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE); // ~0.5s
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
        } catch (SecurityException e) {
            setStatus("Sin permiso de micrófono");
            resetButtons();
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("No se pudo inicializar AudioRecord");
            recorder.release();
            recorder = null;
            resetButtons();
            return;
        }
        if (preferredInput != null) {
            recorder.setPreferredDevice(preferredInput);
        }

        File dir = getExternalFilesDir(null);
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outputFile = new File(dir, "cardo_" + name + ".wav");

        recorder.startRecording();
        isRecording = true;
        final int size = bufferSize;
        recordThread = new Thread(() -> writeWav(size), "cardo-recorder");
        recordThread.start();

        String src = preferredInput != null ? "Cardo (SCO)" : "mic por defecto";
        setStatus(status.getText() + "\n\nGrabando desde " + src + "\n" + outputFile.getAbsolutePath());
    }

    private void writeWav(int bufferSize) {
        byte[] buffer = new byte[bufferSize];
        long dataLen = 0;
        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            out.write(new byte[44]); // placeholder del header WAV
            while (isRecording) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read > 0) {
                    out.write(buffer, 0, read);
                    dataLen += read;
                }
            }
        } catch (IOException e) {
            runOnUiThread(() -> setStatus("Error escribiendo: " + e.getMessage()));
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.seek(0);
            raf.write(wavHeader(dataLen));
        } catch (IOException e) {
            runOnUiThread(() -> setStatus("Error en header WAV: " + e.getMessage()));
        }
    }

    private static byte[] wavHeader(long dataLen) {
        int channels = 1;
        int bitsPerSample = 16;
        long byteRate = (long) SAMPLE_RATE * channels * bitsPerSample / 8;
        long totalLen = dataLen + 36;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putInt(h, 4, totalLen);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putInt(h, 16, 16);          // tamaño subchunk fmt
        h[20] = 1; h[21] = 0;       // PCM
        h[22] = (byte) channels; h[23] = 0;
        putInt(h, 24, SAMPLE_RATE);
        putInt(h, 28, byteRate);
        h[32] = (byte) (channels * bitsPerSample / 8); h[33] = 0;
        h[34] = (byte) bitsPerSample; h[35] = 0;
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        putInt(h, 40, dataLen);
        return h;
    }

    private static void putInt(byte[] b, int off, long v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }

    private void stopAll() {
        handler.removeCallbacks(scoTimeout);
        boolean wasRecording = isRecording;
        isRecording = false;
        if (recordThread != null) {
            try {
                recordThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            recordThread = null;
        }
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            recorder.release();
            recorder = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice();
        } else {
            if (waitingForSco || wasRecording) {
                try {
                    unregisterReceiver(scoReceiver);
                } catch (IllegalArgumentException ignored) {
                }
            }
            waitingForSco = false;
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
        }
        audioManager.setMode(AudioManager.MODE_NORMAL);

        if (wasRecording && outputFile != null) {
            setStatus("Guardado:\n" + outputFile.getAbsolutePath());
        }
        resetButtons();
    }

    private void resetButtons() {
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void setStatus(String s) {
        status.setText(s);
    }
}
