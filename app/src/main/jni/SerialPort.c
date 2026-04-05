/*
 * Native serial port access for Android.
 * Based on Google's android-serialport-api.
 *
 * Opens a serial device file with proper termios configuration
 * (baud rate, 8N1, raw mode) — something that can't be done
 * from Java/Kotlin alone on Android.
 */

#include <jni.h>
#include <termios.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <android/log.h>

#define TAG "NativeSerial"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static speed_t getBaudRate(jint baudRate) {
    switch (baudRate) {
        case 1200:    return B1200;
        case 2400:    return B2400;
        case 4800:    return B4800;
        case 9600:    return B9600;
        case 19200:   return B19200;
        case 38400:   return B38400;
        case 57600:   return B57600;
        case 115200:  return B115200;
        case 230400:  return B230400;
        case 460800:  return B460800;
        case 500000:  return B500000;
        case 576000:  return B576000;
        case 921600:  return B921600;
        case 1000000: return B1000000;
        default:      return B9600;
    }
}

/*
 * Open a serial port with the specified baud rate.
 * Returns the file descriptor, or -1 on error.
 *
 * JNI signature: (Ljava/lang/String;I)I
 */
JNIEXPORT jint JNICALL
Java_com_locqar_winnsentest_hardware_SerialPortJNI_nativeOpen(
    JNIEnv *env, jclass clazz, jstring path, jint baudRate)
{
    const char *pathStr = (*env)->GetStringUTFChars(env, path, NULL);
    if (!pathStr) {
        LOGE("Failed to get path string");
        return -1;
    }

    LOGI("Opening %s at %d baud", pathStr, baudRate);

    // Open the device
    int fd = open(pathStr, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) {
        LOGE("Cannot open %s: %s", pathStr, strerror(errno));
        (*env)->ReleaseStringUTFChars(env, path, pathStr);
        return -1;
    }

    // Configure termios
    struct termios cfg;
    if (tcgetattr(fd, &cfg) != 0) {
        LOGE("tcgetattr failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path, pathStr);
        return -1;
    }

    // Set baud rate
    speed_t speed = getBaudRate(baudRate);
    cfsetispeed(&cfg, speed);
    cfsetospeed(&cfg, speed);

    // 8N1: 8 data bits, no parity, 1 stop bit
    cfg.c_cflag &= ~PARENB;    // No parity
    cfg.c_cflag &= ~CSTOPB;    // 1 stop bit
    cfg.c_cflag &= ~CSIZE;
    cfg.c_cflag |= CS8;         // 8 data bits
    cfg.c_cflag |= CLOCAL;     // Ignore modem control lines
    cfg.c_cflag |= CREAD;      // Enable receiver

    // Raw mode (no echo, no canonical processing, no signals)
    cfg.c_lflag &= ~(ICANON | ECHO | ECHOE | ISIG);

    // No software flow control
    cfg.c_iflag &= ~(IXON | IXOFF | IXANY);

    // No output processing
    cfg.c_oflag &= ~OPOST;

    // Read timeout: return after 200ms or when 1+ bytes available
    cfg.c_cc[VTIME] = 2;  // 200ms timeout (in tenths of second)
    cfg.c_cc[VMIN] = 0;   // Non-blocking read

    // Apply configuration
    if (tcsetattr(fd, TCSANOW, &cfg) != 0) {
        LOGE("tcsetattr failed: %s", strerror(errno));
        close(fd);
        (*env)->ReleaseStringUTFChars(env, path, pathStr);
        return -1;
    }

    // Flush any pending data
    tcflush(fd, TCIOFLUSH);

    // Clear O_NONBLOCK after configuration
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);

    LOGI("Opened %s at %d baud, fd=%d", pathStr, baudRate, fd);
    (*env)->ReleaseStringUTFChars(env, path, pathStr);
    return fd;
}

/*
 * Close a serial port file descriptor.
 */
JNIEXPORT void JNICALL
Java_com_locqar_winnsentest_hardware_SerialPortJNI_nativeClose(
    JNIEnv *env, jclass clazz, jint fd)
{
    LOGI("Closing fd=%d", fd);
    close(fd);
}
