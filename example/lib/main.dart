// EasyBIS bench app for the usb_serial fork (usb-serial-for-android drivers).
// Lists USB devices with vendor:product id, opens one with a chosen driver and port settings,
// and shows what arrives. Every step is also printed to logcat with the tag "USBTEST".
import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:easybis_usb_serial/easybis_usb_serial.dart';

void main() => runApp(const MaterialApp(home: BenchPage()));

const String kLib = String.fromEnvironment('LIB', defaultValue: 'easybis_usb_serial');

String hex4(int? v) => (v ?? 0).toRadixString(16).padLeft(4, '0');

void log(String s) => debugPrint('USBTEST $s');

class BenchPage extends StatefulWidget {
  const BenchPage({super.key});
  @override
  State<BenchPage> createState() => _BenchPageState();
}

class _BenchPageState extends State<BenchPage> {
  List<UsbDevice> _devices = [];
  UsbPort? _port;
  StreamSubscription<Uint8List>? _sub;
  StreamSubscription<UsbEvent>? _events;
  String _status = 'Idle';
  String _driver = ''; // '' = automatic
  String _settings = 'Sai scale - 9600 8N1';
  String _hex = '';
  bool _linesOn = true; // DTR+RTS on (desk/terminal) or off (what the app widgets did)
  int _bytes = 0;
  String _text = '';

  static const _drivers = ['', 'pl2303', 'ch34x', 'cp210x', 'ftdi', 'cdc'];
  // the serial settings of the analyser / scale profiles on the ERP (mrd_t_analyserscaleprofiles)
  static const _settingsList = [
    'Sai scale - 9600 8N1',
    'Continuous scale - 2400 8N1',
    'EkoMilk-P / EkoMilk-U - 1200 8E1',
    'Essae MA02 - 1200 8N1',
    'Essae BT - 9600 8N1',
    'EkoMilk Ultra / DPS - 9600 8E1',
    'other - 4800 8N1',
    'other - 19200 8N1',
    'other - 2400 8E1',
    'other - 4800 8E1',
  ];

  @override
  void initState() {
    super.initState();
    _events = UsbSerial.usbEventStream?.listen((e) {
      log('event ${e.event} ${hex4(e.device?.vid)}:${hex4(e.device?.pid)}');
      _list();
    });
    _list();
  }

  Future<void> _list() async {
    final d = await UsbSerial.listDevices();
    for (final x in d) {
      log('device id=${x.deviceId} ${hex4(x.vid)}:${hex4(x.pid)} ${x.manufacturerName} / ${x.productName}');
    }
    if (mounted) setState(() => _devices = d);
  }

  Future<void> _close() async {
    await _sub?.cancel();
    _sub = null;
    await _port?.close();
    _port = null;
  }

  Future<void> _open(UsbDevice d) async {
    await _close();
    final parts = _settings.split(' - ').last.split(' ');
    final baud = int.parse(parts[0]);
    final fmt = parts[1];
    final parity = {'N': UsbPort.PARITY_NONE, 'E': UsbPort.PARITY_EVEN, 'O': UsbPort.PARITY_ODD}[fmt[1]]!;
    final name = '${hex4(d.vid)}:${hex4(d.pid)} driver=${_driver.isEmpty ? 'auto' : _driver} $_settings lines=${_linesOn ? 'ON' : 'OFF'} lib=$kLib';
    setState(() {
      _status = 'Opening $name ...';
      _bytes = 0;
      _text = '';
      _hex = '';
    });
    log('open $name');
    try {
      final p = await UsbSerial.createFromDeviceId(d.deviceId, _driver);
      if (p == null) {
        _set('FAILED: no port ($name)');
        return;
      }
      if (await p.open() != true) {
        _set('FAILED: did not open ($name)');
        return;
      }
      await p.setDTR(_linesOn);
      await p.setRTS(_linesOn);
      await p.setPortParameters(baud, int.parse(fmt[0]), UsbPort.STOPBITS_1, parity);
      _port = p;
      _sub = p.inputStream?.listen((b) {
        _bytes += b.length;
        _hex = (_hex + b.map((x) => x.toRadixString(16).padLeft(2, '0')).join(' ') + ' ');
        if (_hex.length > 240) _hex = _hex.substring(_hex.length - 240);
        final t = latin1.decode(b, allowInvalid: true).replaceAll('\r', '').replaceAll('\n', '|');
        _text = (_text + t);
        if (_text.length > 300) _text = _text.substring(_text.length - 300);
        log('rx ${b.length} total=$_bytes "$t" hex=${b.map((x) => x.toRadixString(16).padLeft(2, '0')).join(' ')}');
        if (mounted) setState(() {});
      }, onDone: () {
        log('stream done');
        _set('Stream ended (unplugged?) after $_bytes bytes');
      });
      _set('OPEN $name - waiting for data');
    } catch (e) {
      _set('FAILED: $e ($name)');
    }
  }

  void _set(String s) {
    log(s);
    if (mounted) setState(() => _status = s);
  }

  @override
  void dispose() {
    _events?.cancel();
    _close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('USB bench - $kLib')),
      body: ListView(padding: const EdgeInsets.all(12), children: [
        Row(children: [
          const Text('Driver: '),
          DropdownButton<String>(
            value: _driver,
            items: _drivers.map((d) => DropdownMenuItem(value: d, child: Text(d.isEmpty ? 'auto' : d))).toList(),
            onChanged: (v) => setState(() => _driver = v ?? ''),
          ),
        ]),
        Row(children: [
          DropdownButton<String>(
            value: _settings,
            items: _settingsList.map((d) => DropdownMenuItem(value: d, child: Text(d))).toList(),
            isExpanded: false,
            onChanged: (v) => setState(() => _settings = v ?? _settingsList.first),
          ),
        ]),
        SwitchListTile(
          title: Text(_linesOn ? 'DTR + RTS ON' : 'DTR + RTS OFF (as the app did)'),
          value: _linesOn,
          onChanged: (v) => setState(() => _linesOn = v),
        ),
        ElevatedButton(onPressed: _list, child: const Text('Refresh devices')),
        for (final d in _devices)
          Card(
            child: ListTile(
              title: Text('${hex4(d.vid)}:${hex4(d.pid)}  (id ${d.deviceId})'),
              subtitle: Text('${d.manufacturerName ?? ''} / ${d.productName ?? ''}'),
              trailing: ElevatedButton(onPressed: () => _open(d), child: const Text('Open')),
            ),
          ),
        const Divider(),
        Text(_status, style: const TextStyle(fontWeight: FontWeight.bold)),
        Text('Bytes received: $_bytes'),
        const SizedBox(height: 8),
        Text(_text, style: const TextStyle(fontFamily: 'monospace')),
        const SizedBox(height: 8),
        Text(_hex, style: const TextStyle(fontFamily: 'monospace', fontSize: 11, color: Colors.blueGrey)),
      ]),
    );
  }
}
