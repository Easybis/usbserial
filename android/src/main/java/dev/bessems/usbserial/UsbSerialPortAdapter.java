package dev.bessems.usbserial;

import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.BinaryMessenger;

/**
 * One open serial port, driven by usb-serial-for-android (mik3y) instead of felHR UsbSerial.
 * The method channel API is unchanged: open, close, write, setPortParameters, setFlowControl,
 * setDTR, setRTS, and the input stream on "<channel>/stream".
 *
 * Settings made before open() (port parameters, DTR, RTS, flow control) are kept and applied
 * when the port opens, as felHR allowed them in any order.
 */
public class UsbSerialPortAdapter implements MethodCallHandler, EventChannel.StreamHandler {

    private final String TAG = UsbSerialPortAdapter.class.getSimpleName();
    private static final int WRITE_TIMEOUT_MS = 2000;

    private final UsbDeviceConnection m_Connection;
    private final UsbSerialPort m_Port;
    private final String m_MethodChannelName;
    private final Handler m_handler;
    private EventChannel.EventSink m_EventSink;
    private SerialInputOutputManager m_IoManager;
    private volatile boolean m_Closing = false;

    // remembered settings (applied on open, re-applied when changed while open)
    private int m_BaudRate = 115200;
    private int m_DataBits = UsbSerialPort.DATABITS_8;
    private int m_StopBits = UsbSerialPort.STOPBITS_1;
    private int m_Parity = UsbSerialPort.PARITY_NONE;
    private boolean m_ParamsSet = false;
    private Boolean m_Dtr = null;
    private Boolean m_Rts = null;
    private int m_FlowControl = 0;

    UsbSerialPortAdapter(BinaryMessenger messenger, int interfaceId, UsbDeviceConnection connection, UsbSerialPort port) {
        m_Connection = connection;
        m_Port = port;
        m_MethodChannelName = "usb_serial/UsbSerialPortAdapter/" + String.valueOf(interfaceId);
        m_handler = new Handler(Looper.getMainLooper());
        final MethodChannel channel = new MethodChannel(messenger, m_MethodChannelName);
        channel.setMethodCallHandler(this);
        final EventChannel eventChannel = new EventChannel(messenger, m_MethodChannelName + "/stream");
        eventChannel.setStreamHandler(this);
    }

    String getMethodChannelName() {
        return m_MethodChannelName;
    }

    private final SerialInputOutputManager.Listener m_Listener = new SerialInputOutputManager.Listener() {
        @Override
        public void onNewData(final byte[] data) {
            if (!m_Closing && m_EventSink != null) {
                m_handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (m_EventSink != null) {
                            m_EventSink.success(data);
                        }
                    }
                });
            }
        }

        @Override
        public void onRunError(Exception e) {
            // the device was unplugged or the port closed: end the Dart stream (onDone)
            Log.d(TAG, "read stopped: " + e);
            if (m_Closing) {
                return; // closed on purpose: no end-of-stream
            }
            m_handler.post(new Runnable() {
                @Override
                public void run() {
                    if (m_EventSink != null) {
                        m_EventSink.endOfStream();
                        m_EventSink = null;
                    }
                }
            });
        }
    };

    private void applySettings() throws IOException {
        if (m_ParamsSet) {
            m_Port.setParameters(m_BaudRate, m_DataBits, m_StopBits, m_Parity);
        }
        if (m_Dtr != null) {
            m_Port.setDTR(m_Dtr);
        }
        if (m_Rts != null) {
            m_Port.setRTS(m_Rts);
        }
        applyFlowControl();
    }

    private void applyFlowControl() {
        UsbSerialPort.FlowControl fc;
        switch (m_FlowControl) {
            case 1:
                fc = UsbSerialPort.FlowControl.RTS_CTS;
                break;
            case 2:
                fc = UsbSerialPort.FlowControl.DTR_DSR;
                break;
            case 3:
                fc = UsbSerialPort.FlowControl.XON_XOFF;
                break;
            default:
                fc = UsbSerialPort.FlowControl.NONE;
        }
        try {
            if (m_Port.getSupportedFlowControl().contains(fc)) {
                m_Port.setFlowControl(fc);
            } else if (fc != UsbSerialPort.FlowControl.NONE) {
                Log.w(TAG, "flow control " + fc + " not supported by " + m_Port.getDriver().getClass().getSimpleName());
            }
        } catch (Exception e) {
            Log.w(TAG, "setFlowControl failed: " + e);
        }
    }

    private Boolean open() {
        if (m_Port.isOpen()) {
            return true;
        }
        try {
            m_Closing = false;
            m_Port.open(m_Connection);
            applySettings();
            m_IoManager = new SerialInputOutputManager(m_Port, m_Listener);
            m_IoManager.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "open failed: " + e);
            try {
                m_Port.close();
            } catch (Exception ignored) {
            }
            return false;
        }
    }

    private Boolean close() {
        m_Closing = true;
        if (m_IoManager != null) {
            m_IoManager.stop();
            m_IoManager = null;
        }
        try {
            if (m_Port.isOpen()) {
                m_Port.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "close: " + e);
        }
        return true;
    }

    private void write(byte[] data) throws IOException {
        if (data == null || !m_Port.isOpen()) {
            return;
        }
        m_Port.write(data, WRITE_TIMEOUT_MS);
    }

    public void onMethodCall(MethodCall call, Result result) {
        try {
            switch (call.method) {
                case "close":
                    result.success(close());
                    break;
                case "open":
                    result.success(open());
                    break;
                case "write":
                    write((byte[]) call.argument("data"));
                    result.success(true);
                    break;
                case "setPortParameters":
                    m_BaudRate = (int) call.argument("baudRate");
                    m_DataBits = (int) call.argument("dataBits");
                    m_StopBits = (int) call.argument("stopBits");
                    m_Parity = (int) call.argument("parity");
                    m_ParamsSet = true;
                    if (m_Port.isOpen()) {
                        m_Port.setParameters(m_BaudRate, m_DataBits, m_StopBits, m_Parity);
                    }
                    result.success(null);
                    break;
                case "setFlowControl":
                    m_FlowControl = (int) call.argument("flowControl");
                    if (m_Port.isOpen()) {
                        applyFlowControl();
                    }
                    result.success(null);
                    break;
                case "setDTR": {
                    boolean v = call.argument("value");
                    m_Dtr = v;
                    if (m_Port.isOpen()) {
                        m_Port.setDTR(v);
                    }
                    result.success(null);
                    break;
                }
                case "setRTS": {
                    boolean v = call.argument("value");
                    m_Rts = v;
                    if (m_Port.isOpen()) {
                        m_Port.setRTS(v);
                    }
                    result.success(null);
                    break;
                }
                default:
                    result.notImplemented();
            }
        } catch (Exception e) {
            // never crash the app on a serial error: report it to Dart
            Log.e(TAG, call.method + " failed: " + e);
            result.error(TAG, call.method + " failed: " + e.getMessage(), null);
        }
    }

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
        m_EventSink = eventSink;
    }

    @Override
    public void onCancel(Object o) {
        m_EventSink = null;
    }
}
