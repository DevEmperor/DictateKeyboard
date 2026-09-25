/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

/*
 * UTF-8-only stand-ins for iconv_open/iconv/iconv_close (issue #424).
 *
 * Marian links pathie-cpp, whose convert_encodings() calls iconv. Bionic only has iconv from API 28,
 * and we ship to API 26, so the library would not even load on an Android 8 phone. That code path
 * only runs when the filesystem encoding is not UTF-8 — never on Android, and pathie is compiled with
 * PATHIE_ASSUME_UTF8_ON_UNIX besides — so an identity conversion between UTF-8 spellings is all it can
 * ever be asked for, and anything else fails the way iconv itself would (EINVAL).
 *
 * Hidden visibility binds pathie to these definitions at link time on every API level, so the
 * behaviour does not change between a phone that has bionic's iconv and one that does not.
 */

#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <strings.h>

typedef void* iconv_t;

static int dictate_is_utf8(const char* name) {
    return name != NULL && (strcasecmp(name, "UTF-8") == 0 || strcasecmp(name, "UTF8") == 0);
}

static char dictate_identity_converter;

__attribute__((visibility("hidden")))
iconv_t iconv_open(const char* to_encoding, const char* from_encoding) {
    if (!dictate_is_utf8(to_encoding) || !dictate_is_utf8(from_encoding)) {
        errno = EINVAL;
        return (iconv_t) -1;
    }
    return (iconv_t) &dictate_identity_converter;
}

__attribute__((visibility("hidden")))
size_t iconv(iconv_t converter, char** inbuf, size_t* inbytesleft, char** outbuf, size_t* outbytesleft) {
    if (converter != (iconv_t) &dictate_identity_converter) {
        errno = EBADF;
        return (size_t) -1;
    }
    if (inbuf == NULL || *inbuf == NULL) return 0; // reset request: an identity has no state
    size_t count = *inbytesleft < *outbytesleft ? *inbytesleft : *outbytesleft;
    memcpy(*outbuf, *inbuf, count);
    *inbuf += count;
    *outbuf += count;
    *inbytesleft -= count;
    *outbytesleft -= count;
    if (*inbytesleft > 0) {
        errno = E2BIG;
        return (size_t) -1;
    }
    return 0;
}

__attribute__((visibility("hidden")))
int iconv_close(iconv_t converter) {
    (void) converter;
    return 0;
}
