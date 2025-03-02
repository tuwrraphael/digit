# how to connect phone to remote dev machine
~~~
adb.exe -a nodaemon server start
ssh -N -R 0:127.0.0.1:5037 -R 10389:localhost:10389 flutter
export ADB_SERVER_SOCKET=tcp:172.17.0.1:44363
flutter run --host-vmservice-port=10389 --dds-port=43552
~~~