cmake_minimum_required(VERSION 3.18)
project(serial_port)

add_library(serial_port SHARED SerialPort.c)
target_link_libraries(serial_port log)
