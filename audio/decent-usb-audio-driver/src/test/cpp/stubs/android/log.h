// Minimal host-side android/log.h stub: logging goes to stderr so headless
// test output stays debuggable.
#ifndef USB_AUDIO_TEST_STUB_ANDROID_LOG_H_
#define USB_AUDIO_TEST_STUB_ANDROID_LOG_H_

#include <cstdarg>
#include <cstdio>

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

static inline int __android_log_print(int priority, const char* tag, const char* fmt, ...) {
  (void)priority;
  va_list args;
  va_start(args, fmt);
  int written = std::vfprintf(stderr, fmt, args);
  va_end(args);
  std::fputc('\n', stderr);
  (void)tag;
  return written;
}

#endif  // USB_AUDIO_TEST_STUB_ANDROID_LOG_H_
