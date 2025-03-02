import 'package:device_calendar/device_calendar.dart';

class CalendarService {
  final DeviceCalendarPlugin _deviceCalendarPlugin = DeviceCalendarPlugin();

  Future<void> requestPermissions() async {
    var permissionsGranted = await _deviceCalendarPlugin.hasPermissions();
    if (permissionsGranted.isSuccess && (permissionsGranted.data == null || !permissionsGranted.data!)) {
      permissionsGranted = await _deviceCalendarPlugin.requestPermissions();
      if (!permissionsGranted.isSuccess || permissionsGranted.data == null || !permissionsGranted.data!) {
        throw Exception("Calendar permissions not granted");
      }
    }
  }

  Future<bool> _checkPermissions() async {
    var permissionsGranted = await _deviceCalendarPlugin.hasPermissions();
    return permissionsGranted.isSuccess && permissionsGranted.data != null && permissionsGranted.data!;
  }

  Future<Event?> getNextEvent() async {
    final res = await _checkPermissions();
    if (!res) {
      print("No permissions for calendar");
      return null;
    }
    final calendarsResult = await _deviceCalendarPlugin.retrieveCalendars();
    if (!calendarsResult.isSuccess || calendarsResult.data == null) {
      throw Exception("Failed to retrieve calendars");
    }

    Event? nextEvent;
    DateTime now = DateTime.now();

    for (var calendar in calendarsResult.data!) {
      final eventsResult = await _deviceCalendarPlugin.retrieveEvents(
        calendar.id,
        RetrieveEventsParams(startDate: now, endDate: now.add(const Duration(days: 1))),
      );
      if (eventsResult.isSuccess && eventsResult.data != null) {
        for (var event in eventsResult.data!) {
          if (false == event.allDay && ((nextEvent == null && event.end!.isAfter(now)) || (nextEvent != null && event.start!.isBefore(nextEvent.start!)))) {
            nextEvent = event;
          }
        }
      }
    }
    return nextEvent;
  }
}
