/*
 * Forwarding shim between Kotlin and TDLib's JSON interface.
 *
 * libtdjson exports plain C functions, which Java cannot call directly, so this file turns four of
 * them into JNI entry points and nothing else: no policy, no parsing, no state. It is deliberately
 * free of TDLib headers — the prototypes below are copied from td/telegram/td_json_client.h at the
 * commit pinned in .github/workflows/build-tdlib.yml, so a mismatch fails at link time rather than
 * silently.
 *
 * The pinned TDLib exposes two client APIs. Only the integer one is used, because a session is
 * shared by the authentication flow and the cloud scanner:
 *   int  td_create_client_id(void)
 *   void td_send(int client_id, const char *request)
 *   const char *td_receive(double timeout)      -- no client id: one receive loop per process
 *   const char *td_execute(const char *request)
 * td_json_client_destroy has no integer-API counterpart; instances close themselves once Kotlin
 * sends a `close` request, which is why there is no nativeDestroy here.
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

extern int td_create_client_id(void);
extern void td_send(int client_id, const char *request);
extern const char *td_receive(double timeout);

/*
 * td_receive "must not be called simultaneously from two different threads" and the pointer it
 * returns is freed by the next call. Both invariants are enforced here rather than trusted to
 * Kotlin, so a second receive loop can never corrupt or double-free a response.
 */
static pthread_mutex_t receive_lock = PTHREAD_MUTEX_INITIALIZER;

static jstring copy_as_string(JNIEnv *env, const char *raw) {
  static const jchar empty[1] = {0};

  if (raw == NULL) {
    return NULL;
  }

  /*
   * TDLib returns standard UTF-8. NewStringUTF expects modified UTF-8 and mangles code points
   * above U+FFFF, which file names and captions routinely contain, so decode by hand.
   */
  size_t length = strlen(raw);
  jsize capacity = (jsize)length;
  if (capacity <= 0) {
    return (*env)->NewString(env, empty, 0);
  }

  jchar *buffer = (jchar *)malloc(sizeof(jchar) * (size_t)(capacity + 1));
  if (buffer == NULL) {
    (*env)->ExceptionClear(env);
    return (*env)->NewString(env, empty, 0);
  }

  jsize written = 0;
  const unsigned char *cursor = (const unsigned char *)raw;
  while (*cursor != '\0' && written < capacity) {
    unsigned int code_point = *cursor;

    if (code_point < 0x80) {
      cursor += 1;
    } else if ((code_point & 0xE0) == 0xC0 && (cursor[1] & 0xC0) == 0x80) {
      code_point = ((code_point & 0x1FU) << 6) | (cursor[1] & 0x3FU);
      cursor += 2;
    } else if ((code_point & 0xF0) == 0xE0 && (cursor[1] & 0xC0) == 0x80 && (cursor[2] & 0xC0) == 0x80) {
      code_point = ((code_point & 0x0FU) << 12) | ((cursor[1] & 0x3FU) << 6) | (cursor[2] & 0x3FU);
      cursor += 3;
    } else if ((code_point & 0xF8) == 0xF0 && (cursor[1] & 0xC0) == 0x80 && (cursor[2] & 0xC0) == 0x80 &&
               (cursor[3] & 0xC0) == 0x80) {
      code_point = ((code_point & 0x07U) << 18) | ((cursor[1] & 0x3FU) << 12) | ((cursor[2] & 0x3FU) << 6) |
                   (cursor[3] & 0x3FU);
      cursor += 4;
    } else {
      /* Not valid UTF-8: substitute rather than trusting the byte sequence to line up again. */
      code_point = 0xFFFD;
      cursor += 1;
    }

    if (code_point > 0xFFFF) {
      /* Into the supplementary range, so Kotlin sees one String with the real character. */
      unsigned int surrogate_base = code_point - 0x10000U;
      buffer[written++] = (jchar)(0xD800U + (surrogate_base >> 10));
      if (written >= capacity) {
        break;
      }
      buffer[written++] = (jchar)(0xDC00U + (surrogate_base & 0x3FFU));
    } else {
      buffer[written++] = (jchar)code_point;
    }
  }

  jstring result = (*env)->NewString(env, buffer, written);
  free(buffer);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_lumovault_app_data_remote_telegram_JniTdLibNative_nativeCreateClientId(JNIEnv *env, jclass clazz) {
  (void)env;
  (void)clazz;
  return (jint)td_create_client_id();
}

/*
 * The request is ASCII-only by construction: Kotlin escapes every non-ASCII character before
 * sending, so GetStringUTFChars cannot be handed a sequence Modified UTF-8 would mangle, and
 * TDLib's parser accepts the \u escapes unchanged.
 */
JNIEXPORT void JNICALL
Java_com_lumovault_app_data_remote_telegram_JniTdLibNative_nativeSend(JNIEnv *env, jclass clazz, jint client_id,
                                                                     jstring request) {
  (void)clazz;
  const char *utf = (*env)->GetStringUTFChars(env, request, NULL);
  if (utf == NULL) {
    return;
  }
  td_send((int)client_id, utf);
  (*env)->ReleaseStringUTFChars(env, request, utf);
}

JNIEXPORT jstring JNICALL
Java_com_lumovault_app_data_remote_telegram_JniTdLibNative_nativeReceive(JNIEnv *env, jclass clazz,
                                                                        jdouble timeout_seconds) {
  (void)clazz;
  pthread_mutex_lock(&receive_lock);
  const char *raw = td_receive((double)timeout_seconds);
  jstring result = copy_as_string(env, raw);
  pthread_mutex_unlock(&receive_lock);
  return result;
}
