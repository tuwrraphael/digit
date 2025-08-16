# how to connect phone to remote dev machine
~~~
adb.exe -a nodaemon server start
ssh -N -R 5038:127.0.0.1:5037 -R 10389:127.0.0.1:10389 raphael-ubuntu
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5038
flutter run --host-vmservice-port=10389 --dds-port=43552
~~~