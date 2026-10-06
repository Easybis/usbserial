# easybis_usb_serial

Android USB serial ports for Flutter, for USB-to-serial adapters and devices with their own USB
serial chip: CH34x, CP21xx, FTDI, CDC-ACM and Prolific PL2303 - **including the newer Prolific
PL2303 G / HXN family** (PL2303GC `067b:23a3`, GB `23b3`, GT `23c3`, GL `23d3`, GE `23e3`,
GS `23f3`), which many cheap USB-serial cables use today.

This is a fork of [usb_serial](https://pub.dev/packages/usb_serial) by Ron Bessems
([altera2015/usbserial](https://github.com/altera2015/usbserial)). The Dart API is the same; the
Android side runs on [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android)
(mik3y) instead of felHR UsbSerial 6.1.0, which rejects the PL2303 G / HXN chips with
`PlatformException(UsbSerialPortAdapter, Not an Serial device.)`.

Android only.

## Install

```yaml
dependencies:
  easybis_usb_serial: ^0.6.0
```

```dart
import 'package:easybis_usb_serial/easybis_usb_serial.dart';
```

Coming from `usb_serial`: change the dependency and the import; the classes and methods are the
same (`UsbSerial`, `UsbDevice`, `UsbPort`, `UsbEvent`, `Transaction`, the transformers).

## Use

```dart
final devices = await UsbSerial.listDevices();          // vid, pid, deviceId, names
final port = await UsbSerial.createFromDeviceId(devices.first.deviceId); // "" = probe the driver
// or force a driver: UsbSerial.createFromDeviceId(id, UsbSerial.CH34x)  // cdc, ch34x, cp210x, ftdi, pl2303
if (port != null && await port.open()) {
  await port.setDTR(true);
  await port.setRTS(true);
  await port.setPortParameters(9600, UsbPort.DATABITS_8, UsbPort.STOPBITS_1, UsbPort.PARITY_NONE);
  port.inputStream?.listen((bytes) => print(bytes), onDone: () => print('unplugged'));
  await port.write(Uint8List.fromList('hello\r\n'.codeUnits));
}

UsbSerial.usbEventStream?.listen((e) => print('${e.event} ${e.device}')); // attach / detach
```

Notes:

- Android asks the user for permission to use the device the first time it is opened.
- Android gives a USB device a **new `deviceId` every time it is plugged in**; to find "the same"
  device again later, keep its `vid` / `pid`.
- Port parameters, DTR, RTS and flow control may be set before or after `open()`.
- When the device is unplugged the input stream ends (`onDone`).
- Many devices only send while DTR / RTS are on (e.g. printer-style outputs); set them on unless
  the device needs them off.

## Tested

Android 13 (Samsung SM-G780F), Flutter 3.38, real devices, driver chosen automatically:

| Adapter | vid:pid | Device | Settings |
|---|---|---|---|
| Prolific PL2303GC | 067b:23a3 | weighing scale (continuous text) | 9600 8N1 |
| FTDI FT231X | 0403:6015 | weighing scale | 9600 8N1 |
| Prolific PL2303HX | 067b:2303 | weighing scale | 9600 8N1 |
| Prolific PL2303HX / PL2303GC | 067b:2303 / 23a3 | milk analyser, 25-byte binary frame | 1200 8E1 |
| Prolific PL2303GC | 067b:23a3 | milk analyser, ASCII printout | 9600 8E1 |

## Licence

BSD 3-clause, as the original `usb_serial` (see LICENSE). usb-serial-for-android is MIT licensed.
