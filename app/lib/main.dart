import 'dart:ffi';

import 'package:device_calendar/device_calendar.dart';
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:flutter_timezone/flutter_timezone.dart';
import 'package:workmanager/workmanager.dart';
import 'package:app/services/calendar_service.dart';

const checkEventsKey = "com.example.app.checkevents";

@pragma(
    'vm:entry-point') // Mandatory if the App is obfuscated or using Flutter 3.1+
void callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    print(
        "Native called background task: $task"); //simpleTask will be emitted here.
    switch (task) {
      case checkEventsKey:
        final CalendarService calendarService = CalendarService();
        final event = await calendarService.getNextEvent();
        if (null != event) {
          print("Next event: ${event.title} at ${event.start}");
        } else {
          print("No events found");
        }
        break;
    }
    return Future.value(true);
  });
}

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Digit',
      theme: ThemeData(
        // This is the theme of your application.
        //
        // TRY THIS: Try running your application with "flutter run". You'll see
        // the application has a purple toolbar. Then, without quitting the app,
        // try changing the seedColor in the colorScheme below to Colors.green
        // and then invoke "hot reload" (save your changes or press the "hot
        // reload" button in a Flutter-supported IDE, or press "r" if you used
        // the command line to start the app).
        //
        // Notice that the counter didn't reset back to zero; the application
        // state is not lost during the reload. To reset the state, use hot
        // restart instead.
        //
        // This works for code too, not just values: Most code changes can be
        // tested with just a hot reload.
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blueGrey),
        useMaterial3: true,
        textTheme: Theme.of(context).textTheme.apply(
              fontSizeFactor:
                  1.2, // Adjust this factor to increase/decrease the font size
            ),
      ),
      home: const MyHomePage(title: 'Digit'),
    );
  }
}

class MyHomePage extends StatefulWidget {
  const MyHomePage({super.key, required this.title});

  // This widget is the home page of your application. It is stateful, meaning
  // that it has a State object (defined below) that contains fields that affect
  // how it looks.

  // This class is the configuration for the state. It holds the values (in this
  // case the title) provided by the parent (in this case the App widget) and
  // used by the build method of the State. Fields in a Widget subclass are
  // always marked "final".

  final String title;

  @override
  State<MyHomePage> createState() => _MyHomePageState();
}

class _MyHomePageState extends State<MyHomePage> {
  int _batteryLevelRaw = 0;
  bool _workManagerInitialized = false;
  final CalendarService _calendarService = CalendarService();
  Location? _currentLocation;

  Future<BluetoothService> findDigitService(BluetoothDevice device) async {
    var digitServiceUUID = Guid("00001523-1212-efde-1523-785fef13d123");
    for (var s in device.servicesList) {
      if (s.uuid == digitServiceUUID) {
        return s;
      }
    }
    throw Exception("Could not find Digit service");
  }

  Future<BluetoothCharacteristic> findCtsCharacteristic(
      BluetoothService service) async {
    var ctsCharacteristicUUID = Guid("00001805-1212-efde-1523-785fef13d123");
    for (var c in service.characteristics) {
      if (c.uuid == ctsCharacteristicUUID) {
        return c;
      }
    }
    throw Exception("Could not find CTS characteristic");
  }

  Future<BluetoothCharacteristic> findEventCharacteristic(
      BluetoothService service) async {
    var eventCharacteristicUUID = Guid("00001524-1212-efde-1523-785fef13d123");
    for (var c in service.characteristics) {
      if (c.uuid == eventCharacteristicUUID) {
        return c;
      }
    }
    throw Exception("Could not find Event characteristic");
  }

  Future<BluetoothService> findBatteryService(BluetoothDevice device) async {
    var batteryServiceUUID = Guid("180f");
    for (var s in device.servicesList) {
      if (s.uuid == batteryServiceUUID) {
        return s;
      }
    }
    throw Exception("Could not find Battery service");
  }

  Future<BluetoothCharacteristic> findBatteryLevelCharacteristic(
      BluetoothService service) async {
    var batteryLevelCharacteristicUUID = Guid("2a19");
    for (var c in service.characteristics) {
      if (c.uuid == batteryLevelCharacteristicUUID) {
        return c;
      }
    }
    throw Exception("Could not find Battery Level characteristic");
  }

  List<int> _dateToCts(DateTime date) {
    var yearValue = date.year + 1900;
    return [
      yearValue & 0xff,
      yearValue >> 8,
      date.month + 1,
      date.day,
      date.hour,
      date.minute,
      date.second,
    ];
  }

  void _sendCurrentTime() async {
    var device = BluetoothDevice.fromId("the_id");
    // device.
    await device.connect(autoConnect: false);
    await device.discoverServices(subscribeToServicesChanged: false);
    var service = await findDigitService(device);
    var ctsCharacteristic = await findCtsCharacteristic(service);
    var date = DateTime.now();
    var cts = _dateToCts(date);
    await ctsCharacteristic.write(cts);
    await device.disconnect();
  }

  Future _setCurentLocation() async {
  String timezone = 'Etc/UTC';
  try {
    timezone = await FlutterTimezone.getLocalTimezone();
  } catch (e) {
    print('Could not get the local timezone');
  }
  _currentLocation = getLocation(timezone);
  setLocalLocation(_currentLocation!);
}

  Future _sendEvent(Event? ev) async {
    var writeData = [0];
    
    _setCurentLocation();
    // var device = BluetoothDevice.fromId("dev_id");
    // await device.connect(autoConnect: false);
    // await device.discoverServices(subscribeToServicesChanged: false);
    // var service = await findDigitService(device);
    // var eventCharacteristic = await findEventCharacteristic(service);
    if (ev != null) {
      if (ev.start == null) {
        print("Event has no start time");
      } else {
        var date = ev.start!.toLocal();
        var cts = _dateToCts(date);
        var subject = ev.title ?? "";
        if (subject.length > 20) {
          subject = subject.substring(0, 20);
        }
        var subjectBytes = subject.codeUnits;
        writeData = cts + subjectBytes;
      }
    }
    // await eventCharacteristic.write(writeData);
    // await device.disconnect();
    print(writeData);
  }
  

  double _convertToVoltage(int g) {
    var y = (179 * g) / 100 + 711;
    var x = 9 * y / 2560;
    return x;
  }

  void _readBattery() async {
    var device = BluetoothDevice.fromId("dev_id");
    await device.connect(autoConnect: false);
    await device.discoverServices(subscribeToServicesChanged: false);
    var service = await findBatteryService(device);
    var batteryLevelCharacteristic =
        await findBatteryLevelCharacteristic(service);
    var value = await batteryLevelCharacteristic.read();
    setState(() {
      _batteryLevelRaw = value[0];
    });
    await device.disconnect();
  }

  void _retrieveNextEvent() async {
    try {
      await _calendarService.requestPermissions();
      final event = await _calendarService.getNextEvent();
      await _sendEvent(event);
      if (event != null) {
        print("Next event: ${event.title} at ${event.start}");
      } else {
        print("No events found");
      }
    } catch (e) {
      print("Error retrieving next event: $e");
    }
  }

  void _registerWorkManager() {
    if (!_workManagerInitialized) {
      _workManagerInitialized = true;
      Workmanager().initialize(
          callbackDispatcher, // The top level function, aka callbackDispatcher
          isInDebugMode:
              true // If enabled it will post a notification whenever the task is running. Handy for debugging tasks
          );
    }

    // Workmanager().registerPeriodicTask(
    //   checkEventsKey,
    //   checkEventsKey,
    //   frequency: const Duration(minutes: 15),
    // );
    Workmanager().registerOneOffTask(
      checkEventsKey,
      checkEventsKey,
    );
  }

  @override
  Widget build(BuildContext context) {
    // This method is rerun every time setState is called, for instance as done
    // by the _incrementCounter method above.
    //
    // The Flutter framework has been optimized to make rerunning build methods
    // fast, so that you can just rebuild anything that needs updating rather
    // than having to individually change instances of widgets.
    return Scaffold(
      appBar: AppBar(
        // TRY THIS: Try changing the color here to a specific color (to
        // Colors.amber, perhaps?) and trigger a hot reload to see the AppBar
        // change color while the other colors stay the same.
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
        // Here we take the value from the MyHomePage object that was created by
        // the App.build method, and use it to set our appbar title.
        title: Text(widget.title),
      ),
      body: Center(
        // Center is a layout widget. It takes a single child and positions it
        // in the middle of the parent.
        child: Column(
          mainAxisAlignment: MainAxisAlignment.start,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                const Icon(Icons.battery_full, color: Colors.green),
                Text(
                    '$_batteryLevelRaw% (${_convertToVoltage(_batteryLevelRaw).toStringAsFixed(2)}V)',
                    style: Theme.of(context).textTheme.bodyMedium),
              ],
            ),
            ElevatedButton.icon(
              onPressed: _sendCurrentTime,
              icon: const Icon(Icons.access_time),
              label: const Text('Send current time'),
            ),
            ElevatedButton.icon(
              onPressed: _readBattery,
              icon: const Icon(Icons.battery_charging_full),
              label: const Text('Read battery level'),
            ),
            ElevatedButton.icon(
              onPressed: _retrieveNextEvent,
              icon: const Icon(Icons.event),
              label: const Text('Retrieve next event'),
            ),
            ElevatedButton.icon(
              onPressed: _registerWorkManager,
              icon: const Icon(Icons.schedule),
              label: const Text('Start background task'),
            ),
          ],
        ),
      ),
    );
  }
}
