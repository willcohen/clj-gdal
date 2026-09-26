// Copyright (c) 2026 Will Cohen
//
// Part of clj-gdal, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

/*
 * The CPLHTTPFetch callback of the wasm GDAL. It sends each request to
 * globalThis.__gdal_http_fetch, which returns 0 for a failure, or a _malloc
 * buffer:
 *
 *   [int32 status][int32 ctypeLen][ctype bytes][int32 bodyLen][body bytes]
 *
 * Each CPLHTTPResult buffer comes from CPLMalloc, because
 * CPLHTTPDestroyResult frees it.
 */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <emscripten.h>
#include "cpl_conv.h"
#include "cpl_string.h"
#include "cpl_http.h"

EM_JS(int, js_gdal_fetch, (const char *url_ptr, const char *headers_ptr), {
    return globalThis.__gdal_http_fetch(url_ptr, headers_ptr);
});

/* The error state carries the reason, because the GeoJSON reader returns
 * NULL for an empty body before it reads nStatus (ogrgeojsonutils.cpp). */
static CPLHTTPResult *failed_result(const char *msg)
{
    CPLHTTPResult *psResult =
        (CPLHTTPResult *)CPLCalloc(1, sizeof(CPLHTTPResult));
    psResult->nStatus = 1;
    psResult->pszErrBuf = CPLStrdup(msg);
    CPLErrorSetState(CE_Failure, CPLE_AppDefined, msg);
    return psResult;
}

static CPLHTTPResult *result_from_packed(const unsigned char *buf)
{
    int status, ctypeLen, bodyLen;
    memcpy(&status, buf, 4);
    memcpy(&ctypeLen, buf + 4, 4);
    const char *ctype = (const char *)(buf + 8);
    memcpy(&bodyLen, buf + 8 + ctypeLen, 4);
    const unsigned char *body = buf + 12 + ctypeLen;

    /* The text of the libcurl path of GDAL (cpl_http.cpp). */
    if (status < 200 || status > 299)
        return failed_result(CPLSPrintf("HTTP error code : %d", status));

    CPLHTTPResult *psResult =
        (CPLHTTPResult *)CPLCalloc(1, sizeof(CPLHTTPResult));
    if (ctypeLen > 0) {
        char *pszCT = (char *)CPLMalloc(ctypeLen + 1);
        memcpy(pszCT, ctype, ctypeLen);
        pszCT[ctypeLen] = '\0';
        psResult->pszContentType = pszCT;
    }
    if (bodyLen > 0) {
        unsigned char *pabyData = (unsigned char *)CPLMalloc(bodyLen + 1);
        memcpy(pabyData, body, bodyLen);
        pabyData[bodyLen] = '\0'; /* Some GDAL code reads pabyData as a C string. */
        psResult->pabyData = pabyData;
        psResult->nDataLen = bodyLen;
        psResult->nDataAlloc = bodyLen + 1;
    }
    return psResult;
}

/* The header lines that the libcurl path of GDAL sends (cpl_http.cpp), each
 * with CRLF, or NULL. Free the result with CPLFree. */
static char *request_headers(CSLConstList papszOptions)
{
    const char *pszHeaders = CSLFetchNameValue(papszOptions, "HEADERS");
    if (pszHeaders == NULL)
        pszHeaders = CPLGetConfigOption("GDAL_HTTP_HEADERS", NULL);
    if (pszHeaders == NULL)
        return NULL;

    char **papszLines = NULL;
    if (strstr(pszHeaders, "\r\n") != NULL) {
        papszLines = CSLTokenizeString2(pszHeaders, "\r\n", 0);
    } else {
        const char *pszComma = strchr(pszHeaders, ',');
        if (pszComma != NULL && strchr(pszComma, ':') == NULL)
            papszLines = CSLAddString(NULL, pszHeaders);
        else
            papszLines = CSLTokenizeString2(pszHeaders, ",", CSLT_HONOURSTRINGS);
    }

    size_t nLen = 1;
    for (int i = 0; papszLines != NULL && papszLines[i] != NULL; i++)
        nLen += strlen(papszLines[i]) + 2;
    char *pszOut = (char *)CPLMalloc(nLen);
    char *p = pszOut;
    for (int i = 0; papszLines != NULL && papszLines[i] != NULL; i++) {
        size_t n = strlen(papszLines[i]);
        memcpy(p, papszLines[i], n);
        memcpy(p + n, "\r\n", 2);
        p += n + 2;
    }
    *p = '\0';
    CSLDestroy(papszLines);
    return pszOut;
}

static CPLHTTPResult *stub_fetch(const char *pszURL, CSLConstList papszOptions,
                                 GDALProgressFunc pfnProgress, void *pProgressArg,
                                 CPLHTTPFetchWriteFunc pfnWrite, void *pWriteArg,
                                 void *pUserData)
{
    /* GDAL discards the result of CLOSE_PERSISTENT. A NULL result gives the
     * error "not compiled with libcurl support". */
    if (papszOptions != NULL &&
        CSLFetchNameValue(papszOptions, "CLOSE_PERSISTENT") != NULL)
        return (CPLHTTPResult *)CPLCalloc(1, sizeof(CPLHTTPResult));

    char *pszHeaders = request_headers(papszOptions);
    int packed = js_gdal_fetch(pszURL, pszHeaders);
    CPLFree(pszHeaders);
    if (packed == 0)
        return failed_result("HTTP fetch failed");
    CPLHTTPResult *psResult =
        result_from_packed((const unsigned char *)(intptr_t)packed);
    free((void *)(intptr_t)packed); /* JS _malloc and C free() use the same allocator. */
    return psResult;
}

EMSCRIPTEN_KEEPALIVE
int gdal_setup_http_callback(void)
{
    CPLHTTPSetFetchCallback(stub_fetch, NULL);
    return 0;
}
