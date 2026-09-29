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
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
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
    private static final long LINK_TIMEOUT_MS = 8000;
    // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31)
    private static final int TYPE_BLE_HEADSET = 26;

    private AudioManager audioManager;
    private AudioRecord recorder;
    private Thread recordThread;
    private volatile boolean isRecording = false;
    private File outputFile;

    // Dispositivo elegido y espera de la línea de voz BT
    private AudioDeviceInfo pendingInput;
    private boolean waitingForLink = false;
    private boolean scoReceiverRegistered = false;
    private Object commListener; // AudioManager.OnCommunicationDeviceChangedListener (API 31+)

    private TextView status;
    private Button btnStart;
    private Button btnStop;
    private Spinner deviceSpinner;
    private final List<AudioDeviceInfo> inputDevices = new ArrayList<>();
    private ArrayAdapter<String> deviceAdapter;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable linkTimeout = () -> {
        if (waitingForLink) {
            setStatus("La línea de voz Bluetooth no se activó en " + (LINK_TIMEOUT_MS / 1000)
                    + "s.\nRevisa que el Cardo esté conectado con perfil de llamadas (HFP).");
            stopAll();
        }
    };

    // Android < 12: estado de la línea SCO legacy.
    private final BroadcastReceiver scoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                onLinkReady();
            }
        }
    };

    // Refresca la lista si se conecta/desconecta un dispositivo.
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
            refreshDevices();
        }

        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
            refreshDevices();
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
        deviceSpinner = findViewById(R.id.deviceSpinner);

        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(deviceAdapter);

        btnStart.setOnClickListener(v -> {
            if (hasPermissions()) {
                startWithSelectedDevice();
            } else {
                requestPermissions(requiredPermissions(), REQ_PERMS);
            }
        });
        btnStop.setOnClickListener(v -> stopAll());

        audioManager.registerAudioDeviceCallback(deviceCallback, handler);
        if (hasPermissions()) {
            refreshDevices();
        } else {
            requestPermissions(requiredPermissions(), REQ_PERMS);
        }
    }

    @Override
    protected void onDestroy() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback);
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

    // ---------- Selector de dispositivo ----------

    private void refreshDevices() {
        AudioDeviceInfo previous = selectedDevice();
        inputDevices.clear();
        List<String> labels = new ArrayList<>();
        for (AudioDeviceInfo d : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY || d.getType() == AudioDeviceInfo.TYPE_FM_TUNER) {
                continue;
            }
            inputDevices.add(d);
            labels.add(label(d));
        }
        deviceAdapter.clear();
        deviceAdapter.addAll(labels);
        deviceAdapter.notifyDataSetChanged();

        // Mantener la selección previa; si no, preferir Bluetooth.
        int sel = -1;
        for (int i = 0; i < inputDevices.size(); i++) {
            if (previous != null && inputDevices.get(i).getId() == previous.getId()) {
                sel = i;
                break;
            }
        }
        if (sel < 0) {
            for (int i = 0; i < inputDevices.size(); i++) {
                if (isBluetooth(inputDevices.get(i))) {
                    sel = i;
                    break;
                }
            }
        }
        if (sel >= 0) {
            deviceSpinner.setSelection(sel);
        }
        if (inputDevices.isEmpty()) {
            setStatus("No hay micrófonos disponibles");
        }
    }

    private AudioDeviceInfo selectedDevice() {
        int pos = deviceSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= inputDevices.size()) {
            return null;
        }
        return inputDevices.get(pos);
    }

    private static boolean isBluetooth(AudioDeviceInfo d) {
        return d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == TYPE_BLE_HEADSET;
    }

    private static String label(AudioDeviceInfo d) {
        String type;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                type = "Mic del teléfono";
                break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                type = "Bluetooth (llamada)";
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
            default:
                type = "Tipo " + d.getType();
        }
        CharSequence name = d.getProductName();
        String addr = d.getAddress();
        StringBuilder sb = new StringBuilder(type);
        if (name != null && name.length() > 0) {
            sb.append(" · ").append(name);
        }
        if (addr != null && !addr.isEmpty() && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
            sb.append(" (").append(addr).append(")");
        }
        return sb.toString();
    }

    // ---------- Arranque ----------

    private void startWithSelectedDevice() {
        AudioDeviceInfo input = selectedDevice();
        if (input == null) {
            setStatus("Selecciona un micrófono");
            return;
        }
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        deviceSpinner.setEnabled(false);
        pendingInput = input;

        if (!isBluetooth(input)) {
            // Mic local / cable / USB: no hace falta abrir línea BT.
            setStatus("Usando " + label(input));
            startRecording(input);
            return;
        }

        // 1. Abrir la línea de voz Bluetooth con el Cardo y ESPERAR a que esté activa.
        audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
        waitingForLink = true;
        handler.postDelayed(linkTimeout, LINK_TIMEOUT_MS);
        setStatus("Abriendo línea de voz con " + label(input) + "...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioDeviceInfo comm = findCommDevice(input);
            if (comm == null) {
                waitingForLink = false;
                handler.removeCallbacks(linkTimeout);
                setStatus("Android no ofrece " + label(input) + " como dispositivo de llamada.");
                stopAll();
                return;
            }
            AudioManager.OnCommunicationDeviceChangedListener l = device -> {
                if (device != null && device.getType() == comm.getType()) {
                    onLinkReady();
                }
            };
            commListener = l;
            audioManager.addOnCommunicationDeviceChangedListener(getMainExecutor(), l);
            if (!audioManager.setCommunicationDevice(comm)) {
                waitingForLink = false;
                handler.removeCallbacks(linkTimeout);
                setStatus("setCommunicationDevice falló para " + label(comm));
                stopAll();
                return;
            }
            // Puede que ya estuviera activo y no llegue callback.
            AudioDeviceInfo current = audioManager.getCommunicationDevice();
            if (current != null && current.getType() == comm.getType()) {
                handler.postDelayed(this::onLinkReady, 300);
            }
        } else {
            registerReceiver(scoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
            scoReceiverRegistered = true;
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }
    }

    // Busca el dispositivo de comunicación (salida) que corresponde al mic elegido.
    private AudioDeviceInfo findCommDevice(AudioDeviceInfo input) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null;
        }
        AudioDeviceInfo sameType = null;
        for (AudioDeviceInfo d : audioManager.getAvailableCommunicationDevices()) {
            if (d.getType() != input.getType()) {
                continue;
            }
            if (d.getAddress() != null && d.getAddress().equals(input.getAddress())) {
                return d;
            }
            if (sameType == null) {
                sameType = d;
            }
        }
        return sameType;
    }

    private void onLinkReady() {
        if (!waitingForLink) {
            return;
        }
        waitingForLink = false;
        handler.removeCallbacks(linkTimeout);
        // Pequeña espera para que el audio SCO se estabilice antes de abrir el mic.
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

    // 2. Iniciar captura con VOICE_COMMUNICATION y guardar a WAV
    private void startRecording(AudioDeviceInfo input) {
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        int bufferSize = Math.max(minBuffer, SAMPLE_RATE);
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL, ENCODING, bufferSize);
        } catch (SecurityException e) {
            setStatus("Sin permiso de micrófono");
            stopAll();
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("No se pudo inicializar AudioRecord");
            stopAll();
            return;
        }
        recorder.setPreferredDevice(input);

        File dir = getExternalFilesDir(null);
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outputFile = new File(dir, "cardo_" + name + ".wav");

        recorder.startRecording();
        isRecording = true;
        final int size = bufferSize;
        recordThread = new Thread(() -> writeWav(size), "cardo-recorder");
        recordThread.start();

        // Mostrar el micrófono por el que realmente entra el audio.
        handler.postDelayed(() -> showRoute(input), 700);
    }

    private void showRoute(AudioDeviceInfo wanted) {
        if (recorder == null || !isRecording) {
            return;
        }
        AudioDeviceInfo routed = recorder.getRoutedDevice();
        String routedLabel = routed != null ? label(routed) : "desconocido";
        boolean ok = routed != null && routed.getType() == wanted.getType();
        setStatus((ok ? "✅ Grabando desde: " : "⚠️ Pediste " + label(wanted) + "\npero Android enruta a: ")
                + routedLabel + "\n\n" + outputFile.getAbsolutePath());
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

    // ---------- Detener ----------

    private void stopAll() {
        handler.removeCallbacksAndMessages(null);
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
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            recorder.release();
            recorder = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (commListener != null) {
                audioManager.removeOnCommunicationDeviceChangedListener(
                        (AudioManager.OnCommunicationDeviceChangedListener) commListener);
                commListener = null;
            }
            audioManager.clearCommunicationDevice();
        } else {
            if (scoReceiverRegistered) {
                unregisterReceiver(scoReceiver);
                scoReceiverRegistered = false;
            }
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
        }
        audioManager.setMode(AudioManager.MODE_NORMAL);

        if (wasRecording && outputFile != null) {
            setStatus("Guardado:\n" + outputFile.getAbsolutePath());
        }
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        deviceSpinner.setEnabled(true);
    }

    private void setStatus(String s) {
        status.setText(s);
    }
}
