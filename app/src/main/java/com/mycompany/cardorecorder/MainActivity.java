package com.mycompany.cardorecorder;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRouting;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int REQ_PERMS = 1;
    private static final long LINK_TIMEOUT_MS = 8000;
    // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
    private static final int TYPE_BLE_HEADSET = 26;

    private static final int DEVICE_DEFAULT = -1;

    private static final int M_AUTO = 0;
    private static final int M_COMM = 1;
    private static final int M_LEGACY = 2;
    private static final int M_BOTH = 3;

    private AudioManager audioManager;
    private AudioRecord recorder;
    private Thread recordThread;
    private volatile boolean isRecording = false;
    private File outputFile;
    private int sampleRate;
    private MediaPlayer player;

    private AudioDeviceInfo pendingInput;
    private boolean waitingForLink = false;
    private boolean scoReceiverRegistered = false;
    private boolean usedComm = false;
    private boolean usedLegacy = false;
    private Object commListener; // AudioManager.OnCommunicationDeviceChangedListener (API 31+)
    private AudioRouting.OnRoutingChangedListener routingListener;

    private RadioGroup groupDevice;
    private RadioGroup groupMethod;
    private RadioGroup groupSource;
    private RadioGroup groupRate;
    private RadioGroup groupMode;
    private CheckBox chkPreferred;
    private Button btnStart;
    private Button btnStop;
    private Button btnPlay;
    private Button btnRefresh;
    private ProgressBar level;
    private TextView status;
    private TextView log;

    private final List<AudioDeviceInfo> inputDevices = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable linkTimeout = () -> {
        if (waitingForLink) {
            log("TIMEOUT esperando línea BT");
            setStatus("La línea de voz Bluetooth no se activó en " + (LINK_TIMEOUT_MS / 1000)
                    + "s. Prueba otro método en la sección 2.");
            stopAll();
        }
    };

    private final BroadcastReceiver scoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            log("SCO state = " + scoStateName(state));
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                onLinkReady();
            }
        }
    };

    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            if (!isBusy()) {
                refreshDevices();
            }
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            if (!isBusy()) {
                refreshDevices();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        groupDevice = findViewById(R.id.groupDevice);
        groupMethod = findViewById(R.id.groupMethod);
        groupSource = findViewById(R.id.groupSource);
        groupRate = findViewById(R.id.groupRate);
        groupMode = findViewById(R.id.groupMode);
        chkPreferred = findViewById(R.id.chkPreferred);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnPlay = findViewById(R.id.btnPlay);
        btnRefresh = findViewById(R.id.btnRefresh);
        level = findViewById(R.id.level);
        status = findViewById(R.id.status);
        log = findViewById(R.id.log);

        buildStaticOptions();

        btnRefresh.setOnClickListener(v -> refreshDevices());
        btnStart.setOnClickListener(v -> {
            if (hasPermissions()) {
                startWithSelection();
            } else {
                requestPermissions(requiredPermissions(), REQ_PERMS);
            }
        });
        btnStop.setOnClickListener(v -> stopAll());
        btnPlay.setOnClickListener(v -> togglePlayback());

        audioManager.registerAudioDeviceCallback(deviceCallback, handler);
        if (hasPermissions()) {
            refreshDevices();
        } else {
            requestPermissions(requiredPermissions(), REQ_PERMS);
        }
        log("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL);
    }

    @Override
    protected void onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
        stopPlayback();
        stopAll();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMS) {
            if (hasPermissions()) {
                refreshDevices();
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

    private boolean isBusy() {
        return isRecording || waitingForLink;
    }

    // ---------- Opciones de UI ----------

    private void buildStaticOptions() {
        boolean s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        addRadio(groupMethod, "Automático (recomendado por Android)", M_AUTO, true);
        if (s) {
            addRadio(groupMethod, "setCommunicationDevice (Android 12+)", M_COMM, false);
        }
        addRadio(groupMethod, "startBluetoothSco (clásico)", M_LEGACY, false);
        if (s) {
            addRadio(groupMethod, "Ambos", M_BOTH, false);
        }

        addRadio(groupSource, "VOICE_COMMUNICATION (llamada)", MediaRecorder.AudioSource.VOICE_COMMUNICATION, true);
        addRadio(groupSource, "MIC", MediaRecorder.AudioSource.MIC, false);
        addRadio(groupSource, "VOICE_RECOGNITION", MediaRecorder.AudioSource.VOICE_RECOGNITION, false);
        addRadio(groupSource, "DEFAULT", MediaRecorder.AudioSource.DEFAULT, false);
        addRadio(groupSource, "UNPROCESSED", MediaRecorder.AudioSource.UNPROCESSED, false);

        // SCO clásico (CVSD) es 8 kHz; varios HAL (Huawei) caen al mic interno con otra frecuencia.
        addRadio(groupRate, "8 kHz", 8000, true);
        addRadio(groupRate, "16 kHz", 16000, false);
        addRadio(groupRate, "48 kHz", 48000, false);

        addRadio(groupMode, "IN_COMMUNICATION (VoIP)", AudioManager.MODE_IN_COMMUNICATION, true);
        addRadio(groupMode, "IN_CALL (llamada telefónica)", AudioManager.MODE_IN_CALL, false);
        addRadio(groupMode, "NORMAL", AudioManager.MODE_NORMAL, false);
    }

    private void addRadio(RadioGroup group, String text, int value, boolean checked) {
        RadioButton rb = new RadioButton(this);
        rb.setId(View.generateViewId());
        rb.setText(text);
        rb.setTag(value);
        rb.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        group.addView(rb);
        if (checked) {
            group.check(rb.getId());
        }
    }

    private int selectedValue(RadioGroup group, int fallback) {
        View v = group.findViewById(group.getCheckedRadioButtonId());
        return v != null && v.getTag() instanceof Integer ? (Integer) v.getTag() : fallback;
    }

    private void setOptionsEnabled(boolean enabled) {
        for (RadioGroup g : new RadioGroup[]{groupDevice, groupMethod, groupSource, groupRate, groupMode}) {
            for (int i = 0; i < g.getChildCount(); i++) {
                g.getChildAt(i).setEnabled(enabled);
            }
        }
        chkPreferred.setEnabled(enabled);
        btnRefresh.setEnabled(enabled);
    }

    private void refreshDevices() {
        int previous = selectedValue(groupDevice, Integer.MIN_VALUE);
        groupDevice.removeAllViews();
        inputDevices.clear();

        addRadio(groupDevice, "Por defecto (lo que Android decida)", DEVICE_DEFAULT, previous == DEVICE_DEFAULT);
        StringBuilder found = new StringBuilder("Entradas:");
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            found.append("\n  id=").append(d.getId()).append(" ").append(label(d));
            if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY || d.getType() == AudioDeviceInfo.TYPE_FM_TUNER
                    || d.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                continue;
            }
            inputDevices.add(d);
            addRadio(groupDevice, label(d), d.getId(), d.getId() == previous);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            found.append("\nDispositivos de llamada:");
            for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
                found.append("\n  id=").append(d.getId()).append(" ").append(label(d));
            }
        }
        log(found.toString());

        boolean anyBt = false;
        for (AudioDeviceInfo d : inputDevices) {
            anyBt |= isBluetooth(d);
        }
        if (!anyBt) {
            setStatus("No aparece ningún micrófono Bluetooth. Revisa que el Cardo tenga "
                    + "activadas las \"llamadas telefónicas\" en ajustes Bluetooth del teléfono.");
        }
    }

    private AudioDeviceInfo selectedDevice() {
        int id = selectedValue(groupDevice, DEVICE_DEFAULT);
        for (AudioDeviceInfo d : inputDevices) {
            if (d.getId() == id) {
                return d;
            }
        }
        return null;
    }

    private static boolean isBluetooth(AudioDeviceInfo d) {
        return d != null && (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == TYPE_BLE_HEADSET);
    }

    private static String label(AudioDeviceInfo d) {
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                type = "Mic del teléfono";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                type = "Auricular del teléfono";
                break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                type = "Altavoz del teléfono";
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                type = "Bluetooth llamada (SCO)";
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                type = "Bluetooth música (A2DP)";
                break;
            case TYPE_BLE_HEADSET:
                type = "Bluetooth LE";
                break;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                type = "Audífonos con cable";
                break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                type = "USB";
                break;
            case AudioDeviceInfo.TYPE_TELEPHONY:
                type = "Telefonía";
                break;
            default:
                type = "Tipo " + d.getType();
        }
        StringBuilder sb = new StringBuilder(type);
        CharSequence name = d.getProductName();
        if (name != null && name.length() > 0) {
            sb.append(" · ").append(name);
        }
        String addr = d.getAddress();
        if (addr != null && !addr.isEmpty()) {
            sb.append(" [").append(addr).append("]");
        }
        return sb.toString();
    }

    private static String scoStateName(int s) {
        switch (s) {
            case AudioManager.SCO_AUDIO_STATE_DISCONNECTED: return "DISCONNECTED";
            case AudioManager.SCO_AUDIO_STATE_CONNECTED: return "CONNECTED";
            case AudioManager.SCO_AUDIO_STATE_CONNECTING: return "CONNECTING";
            case AudioManager.SCO_AUDIO_STATE_ERROR: return "ERROR";
            default: return String.valueOf(s);
        }
    }

    // ---------- Arranque ----------

    private void startWithSelection() {
        stopPlayback();
        AudioDeviceInfo input = selectedDevice();
        int method = selectedValue(groupMethod, M_AUTO);
        sampleRate = selectedValue(groupRate, 16000);

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        btnPlay.setEnabled(false);
        setOptionsEnabled(false);
        pendingInput = input;

        int mode = selectedValue(groupMode, AudioManager.MODE_IN_COMMUNICATION);
        log("--- Iniciar: mic=" + (input != null ? label(input) : "por defecto")
                + " método=" + method + " fuente=" + selectedValue(groupSource, -1) + " rate=" + sampleRate
                + " modo=" + mode + " preferred=" + chkPreferred.isChecked());

        if (!isBluetooth(input)) {
            setStatus("Usando " + (input != null ? label(input) : "mic por defecto"));
            startRecording(input);
            return;
        }

        try {
            audioManager.setMode(mode);
        } catch (SecurityException e) {
            log("setMode(" + mode + ") rechazado: " + e.getMessage());
        }
        log("setMode(" + mode + ") -> mode=" + audioManager.getMode());
        waitingForLink = true;
        handler.postDelayed(linkTimeout, LINK_TIMEOUT_MS);
        setStatus("Abriendo línea de voz con " + label(input) + "...");

        boolean s = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
        boolean useComm = s && (method == M_AUTO || method == M_COMM || method == M_BOTH);
        boolean useLegacy = method == M_LEGACY || method == M_BOTH || (method == M_AUTO && !s);

        if (useLegacy) {
            startLegacySco();
        }
        if (useComm && !startCommDevice(input) && !useLegacy) {
            waitingForLink = false;
            stopAll();
        }
    }

    private void startLegacySco() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(scoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        }
        scoReceiverRegistered = true;
        usedLegacy = true;
        log("isBluetoothScoAvailableOffCall=" + audioManager.isBluetoothScoAvailableOffCall());
        // setBluetoothScoOn(true) se llama al recibir CONNECTED (algunos HAL lo ignoran antes).
        audioManager.startBluetoothSco();
        log("startBluetoothSco() llamado");
    }

    private boolean startCommDevice(AudioDeviceInfo input) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        AudioDeviceInfo comm = null;
        for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
            if (d.getType() != input.getType()) {
                continue;
            }
            if (comm == null || (d.getAddress() != null && d.getAddress().equals(input.getAddress()))) {
                comm = d;
            }
        }
        if (comm == null) {
            log("No hay dispositivo de llamada tipo " + input.getType());
            setStatus("Android no ofrece " + label(input) + " como dispositivo de llamada.");
            return false;
        }
        final int commType = comm.getType();
        AudioManager.OnCommunicationDeviceChangedListener l = device -> {
            log("CommunicationDevice -> " + (device != null ? label(device) : "null"));
            if (device != null && device.getType() == commType) {
                onLinkReady();
            }
        };
        commListener = l;
        audioManager.addOnCommunicationDeviceChangedListener(getMainExecutor(), l);
        usedComm = true;
        boolean ok = audioManager.setCommunicationDevice(comm);
        log("setCommunicationDevice(" + label(comm) + ") -> " + ok);
        if (!ok) {
            setStatus("setCommunicationDevice falló");
            return false;
        }
        AudioDeviceInfo current = audioManager.getCommunicationDevice();
        if (current != null && current.getType() == commType) {
            handler.postDelayed(this::onLinkReady, 300);
        }
        return true;
    }

    private void onLinkReady() {
        if (!waitingForLink) {
            return;
        }
        waitingForLink = false;
        handler.removeCallbacks(linkTimeout);
        if (usedLegacy) {
            audioManager.setBluetoothScoOn(true);
        }
        log("Línea BT lista; isBluetoothScoOn=" + audioManager.isBluetoothScoOn()
                + " mode=" + audioManager.getMode());
        handler.postDelayed(() -> {
            if (pendingInput != null && !isRecording) {
                startRecording(findSameInput(pendingInput));
            }
        }, 500);
    }

    // El id del dispositivo puede cambiar cuando se activa SCO; re-buscarlo.
    private AudioDeviceInfo findSameInput(AudioDeviceInfo wanted) {
        AudioDeviceInfo sameType = null;
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getId() == wanted.getId()) {
                return d;
            }
            if (d.getType() == wanted.getType() && sameType == null) {
                sameType = d;
            }
        }
        return sameType != null ? sameType : wanted;
    }

    private void startRecording(AudioDeviceInfo input) {
        int source = selectedValue(groupSource, MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        int minBuffer = AudioRecord.getMinBufferSize(sampleRate, CHANNEL, ENCODING);
        if (minBuffer <= 0) {
            setStatus(sampleRate + " Hz no soportado");
            stopAll();
            return;
        }
        int bufferSize = Math.max(minBuffer * 2, sampleRate);
        try {
            recorder = new AudioRecord(source, sampleRate, CHANNEL, ENCODING, bufferSize);
        } catch (SecurityException e) {
            setStatus("Sin permiso de micrófono");
            stopAll();
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("No se pudo inicializar AudioRecord con esa fuente/frecuencia");
            stopAll();
            return;
        }
        log("AudioRecord: fuente=" + recorder.getAudioSource() + " rate=" + recorder.getSampleRate()
                + " buffer=" + bufferSize);
        if (input != null && chkPreferred.isChecked()) {
            boolean ok = recorder.setPreferredDevice(input);
            log("setPreferredDevice(" + label(input) + ") -> " + ok);
        }
        routingListener = router -> {
            AudioDeviceInfo r = router.getRoutedDevice();
            log("Ruta cambió -> " + (r != null ? label(r) : "null"));
        };
        recorder.addOnRoutingChangedListener(routingListener, handler);

        File dir = getExternalFilesDir(null);
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outputFile = new File(dir, "cardo_" + name + ".wav");

        recorder.startRecording();
        isRecording = true;
        recordThread = new Thread(this::writeWav, "cardo-recorder");
        recordThread.start();

        handler.postDelayed(() -> showRoute(input), 700);
    }

    private void showRoute(AudioDeviceInfo wanted) {
        if (recorder == null || !isRecording) {
            return;
        }
        AudioDeviceInfo routed = recorder.getRoutedDevice();
        String routedLabel = routed != null ? label(routed) : "desconocido";
        log("getRoutedDevice -> " + routedLabel);
        String msg = "Grabando. Android reporta: " + routedLabel
                + "\nHabla al Cardo y mira si la barra se mueve; luego habla al teléfono con el Cardo lejos.";
        if (wanted != null && (routed == null || routed.getType() != wanted.getType())) {
            msg = "⚠️ Pediste " + label(wanted) + "\n" + msg;
        }
        setStatus(msg);
    }

    private void writeWav() {
        int chunk = sampleRate / 20; // ~50 ms
        short[] samples = new short[chunk];
        byte[] bytes = new byte[chunk * 2];
        long dataLen = 0;
        try (FileOutputStream out = new FileOutputStream(outputFile)) {
            out.write(new byte[44]); // placeholder del header WAV
            while (isRecording) {
                int read = recorder.read(samples, 0, chunk);
                if (read <= 0) {
                    continue;
                }
                int peak = 0;
                for (int i = 0; i < read; i++) {
                    short v = samples[i];
                    peak = Math.max(peak, Math.abs((int) v));
                    bytes[i * 2] = (byte) (v & 0xff);
                    bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
                }
                out.write(bytes, 0, read * 2);
                dataLen += read * 2L;
                final int pct = Math.min(100, peak * 100 / 32767);
                level.post(() -> level.setProgress(pct));
            }
        } catch (IOException e) {
            runOnUiThread(() -> setStatus("Error escribiendo: " + e.getMessage()));
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            raf.seek(0);
            raf.write(wavHeader(dataLen, sampleRate));
        } catch (IOException e) {
            runOnUiThread(() -> setStatus("Error en header WAV: " + e.getMessage()));
        }
    }

    private static byte[] wavHeader(long dataLen, int rate) {
        int channels = 1;
        int bitsPerSample = 16;
        long byteRate = (long) rate * channels * bitsPerSample / 8;
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        putInt(h, 4, dataLen + 36);
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putInt(h, 16, 16);          // tamaño subchunk fmt
        h[20] = 1; h[21] = 0;       // PCM
        h[22] = (byte) channels; h[23] = 0;
        putInt(h, 24, rate);
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

    // ---------- Detener / reproducir ----------

    private void stopAll() {
        handler.removeCallbacks(linkTimeout);
        waitingForLink = false;
        pendingInput = null;
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
            if (routingListener != null) {
                recorder.removeOnRoutingChangedListener(routingListener);
                routingListener = null;
            }
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            recorder.release();
            recorder = null;
        }

        if (usedComm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (commListener != null) {
                audioManager.removeOnCommunicationDeviceChangedListener(
                        (AudioManager.OnCommunicationDeviceChangedListener) commListener);
                commListener = null;
            }
            audioManager.clearCommunicationDevice();
        }
        if (usedLegacy) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
        }
        if (scoReceiverRegistered) {
            unregisterReceiver(scoReceiver);
            scoReceiverRegistered = false;
        }
        usedComm = false;
        usedLegacy = false;
        audioManager.setMode(AudioManager.MODE_NORMAL);
        level.setProgress(0);

        if (wasRecording && outputFile != null) {
            setStatus("Guardado:\n" + outputFile.getAbsolutePath());
            log("Guardado " + outputFile.getName() + " (" + outputFile.length() / 1024 + " KB)");
        }
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        btnPlay.setEnabled(outputFile != null && outputFile.exists());
        setOptionsEnabled(true);
    }

    private void togglePlayback() {
        if (player != null) {
            stopPlayback();
            return;
        }
        if (outputFile == null || !outputFile.exists()) {
            return;
        }
        try {
            player = new MediaPlayer();
            player.setDataSource(outputFile.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
            btnPlay.setText("Parar");
        } catch (IOException e) {
            setStatus("No se pudo reproducir: " + e.getMessage());
            stopPlayback();
        }
    }

    private void stopPlayback() {
        if (player != null) {
            player.release();
            player = null;
        }
        btnPlay.setText("Escuchar");
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void log(String s) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String line = t + " " + s + "\n";
        runOnUiThread(() -> log.append(line));
    }
}
