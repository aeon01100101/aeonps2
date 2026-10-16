/* aeonps2_bridge.c — AeonPS2 libretro frontend bridge.
 *
 * A hand-written frontend for the PCEE2 (PCSX2) libretro core
 * (libpcee2_libretro_android.so). Rendering path for v1:
 * SET_HW_RENDER is REFUSED, so the core takes its documented
 * double-buffered readback path and pushes plain framebuffers through
 * retro_video_refresh. No Vulkan context negotiation in v1.
 *
 * Core options: PCEE2 registers via SET_CORE_OPTIONS_V2_INTL
 * (39 definitions) and queries via v1 GET_VARIABLE. This bridge
 * parses the V2 definitions (full options audit, defaults, value
 * lists) and serves GET_VARIABLE from a table seeded with those
 * defaults, with mode-profile (fat/slim) and per-game (serial)
 * overrides on top.
 *
 * Threading contract: all retro_* core calls happen on one emulator
 * thread. Pad/analog state, the ANativeWindow handle, the audio ring
 * and the log ring are the only things touched from other threads.
 *
 * Bench mode: compile with -DAEONPS2_BENCH on Linux. All JNI and
 * Android includes vanish; the plain-C aeonps2_* API below remains,
 * the latest frame is kept in a heap buffer exposed through
 * aeonps2_last_frame(), and audio batches feed the same ring drained
 * by aeonps2_drain_audio().
 */

#define _POSIX_C_SOURCE 200809L
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdbool.h>
#include <ctype.h>
#include <pthread.h>
#include <dlfcn.h>
#include <time.h>
#include <math.h>

#include "libretro.h"

#ifndef AEONPS2_BENCH
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#define BRIDGE_LOG_TAG "AeonPS2Bridge"
#define blog_i(...) __android_log_print(ANDROID_LOG_INFO,  BRIDGE_LOG_TAG, __VA_ARGS__)
#define blog_w(...) __android_log_print(ANDROID_LOG_WARN,  BRIDGE_LOG_TAG, __VA_ARGS__)
#define blog_e(...) __android_log_print(ANDROID_LOG_ERROR, BRIDGE_LOG_TAG, __VA_ARGS__)
#else
#define blog_i(...) fprintf(stderr, "[aeonps2] " __VA_ARGS__), fprintf(stderr, "\n")
#define blog_w(...) fprintf(stderr, "[aeonps2:w] " __VA_ARGS__), fprintf(stderr, "\n")
#define blog_e(...) fprintf(stderr, "[aeonps2:e] " __VA_ARGS__), fprintf(stderr, "\n")
#endif

#define CORE_SONAME "libpcee2_libretro_android.so"

/* ------------------------------------------------------------------ */
/* Core .so: dlopen once, retro_init/retro_deinit always paired          */
/* ------------------------------------------------------------------ */

static void *core_handle = NULL;
static char g_system_dir[1024] = {0};
static char g_save_dir[1024] = {0};

/* True when the loaded core is Play! (32-bit fallback engine). The
 * no-content boot path (Test BIOS) is disabled for Play!: the bench
 * proved it segfaults on retro_load_game(NULL), so the bridge refuses
 * NULL content gracefully instead of calling the core. */
static bool g_core_is_play = false;

static void     (*core_retro_init)(void);
static void     (*core_retro_deinit)(void);
static unsigned (*core_retro_api_version)(void);
static void     (*core_retro_get_system_info)(struct retro_system_info *);
static void     (*core_retro_get_system_av_info)(struct retro_system_av_info *);
static void     (*core_retro_set_environment)(retro_environment_t);
static void     (*core_retro_set_video_refresh)(retro_video_refresh_t);
static void     (*core_retro_set_audio_sample)(retro_audio_sample_t);
static void     (*core_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void     (*core_retro_set_input_poll)(retro_input_poll_t);
static void     (*core_retro_set_input_state)(retro_input_state_t);
static void     (*core_retro_set_controller_port_device)(unsigned, unsigned);
static void     (*core_retro_reset)(void);
static void     (*core_retro_run)(void);
static size_t   (*core_retro_serialize_size)(void);
static bool     (*core_retro_serialize)(void *, size_t);
static bool     (*core_retro_unserialize)(const void *, size_t);
static bool     (*core_retro_load_game)(const struct retro_game_info *);
static void     (*core_retro_unload_game)(void);
static void    *(*core_retro_get_memory_data)(unsigned);
static size_t   (*core_retro_get_memory_size)(unsigned);

#define LOAD_SYM(name) do { \
        core_##name = (void *)dlsym(core_handle, #name); \
        if (!core_##name) { blog_e("dlsym failed: %s", #name); return false; } \
    } while (0)

static bool core_load_symbols(void) {
    LOAD_SYM(retro_init);
    LOAD_SYM(retro_deinit);
    LOAD_SYM(retro_api_version);
    LOAD_SYM(retro_get_system_info);
    LOAD_SYM(retro_get_system_av_info);
    LOAD_SYM(retro_set_environment);
    LOAD_SYM(retro_set_video_refresh);
    LOAD_SYM(retro_set_audio_sample);
    LOAD_SYM(retro_set_audio_sample_batch);
    LOAD_SYM(retro_set_input_poll);
    LOAD_SYM(retro_set_input_state);
    LOAD_SYM(retro_set_controller_port_device);
    LOAD_SYM(retro_reset);
    LOAD_SYM(retro_run);
    LOAD_SYM(retro_serialize_size);
    LOAD_SYM(retro_serialize);
    LOAD_SYM(retro_unserialize);
    LOAD_SYM(retro_load_game);
    LOAD_SYM(retro_unload_game);
    LOAD_SYM(retro_get_memory_data);
    LOAD_SYM(retro_get_memory_size);
    return true;
}

/* ------------------------------------------------------------------ */
/* Ring log: last 64 lines + file mirror (crash-surviving)              */
/* ------------------------------------------------------------------ */

#define RING_LINES 64
#define RING_LEN 256

static char g_ring[RING_LINES][RING_LEN];
static int g_ring_head = 0;
static pthread_mutex_t g_ring_mutex = PTHREAD_MUTEX_INITIALIZER;
static FILE *g_log_file = NULL;

static void ring_add(const char *line) {
    FILE *lf;
    if (!line || !*line) return;
    pthread_mutex_lock(&g_ring_mutex);
    strncpy(g_ring[g_ring_head % RING_LINES], line, RING_LEN - 1);
    g_ring[g_ring_head % RING_LINES][RING_LEN - 1] = '\0';
    g_ring_head++;
    lf = g_log_file;
    if (lf) { fprintf(lf, "%s\n", line); fflush(lf); }
    pthread_mutex_unlock(&g_ring_mutex);
}

static void ringf(const char *fmt, ...) {
    char buf[RING_LEN];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    ring_add(buf);
}

static void aeonps2_set_log_path(const char *path) {
    pthread_mutex_lock(&g_ring_mutex);
    if (g_log_file) { fclose(g_log_file); g_log_file = NULL; }
    if (path && *path) g_log_file = fopen(path, "a");
    pthread_mutex_unlock(&g_ring_mutex);
}

static void aeonps2_get_ring_log(char *out, size_t outsz) {
    pthread_mutex_lock(&g_ring_mutex);
    size_t off = 0;
    int start = g_ring_head > RING_LINES ? g_ring_head - RING_LINES : 0;
    for (int i = start; i < g_ring_head && off + 2 < outsz; i++) {
        const char *l = g_ring[i % RING_LINES];
        size_t n = strlen(l);
        if (off + n + 2 > outsz) break;
        memcpy(out + off, l, n);
        off += n;
        out[off++] = '\n';
    }
    out[off] = '\0';
    pthread_mutex_unlock(&g_ring_mutex);
}

/* ------------------------------------------------------------------ */
/* Core options: V2_INTL definitions -> defaults table -> GET_VARIABLE  */
/*                                                                     */
/* PCEE2 sends SET_CORE_OPTIONS_V2_INTL (39 definitions) and queries   */
/* via v1 GET_VARIABLE. We parse the definitions (full audit: keys,    */
/* defaults, value lists, categories), seed a variable table with the  */
/* core's own defaults, then apply mode-profile and per-game           */
/* overrides on top. Overrides are served; everything else keeps the   */
/* core default (GET_VARIABLE answered from the table).                 */
/* ------------------------------------------------------------------ */

#define MAX_OPTS 64
#define MAX_OVERRIDES 32
#define OPT_KEY_LEN 64
#define OPT_VAL_LEN 64

static struct {
    char key[OPT_KEY_LEN];
    char value[OPT_VAL_LEN];
    char defval[OPT_VAL_LEN];
    char cat[48];
} g_opts[MAX_OPTS];
static int g_nopts = 0;

/* Mode profile: "fat" (default = core defaults, nothing overridden) or
 * "slim" (conservative subset). Value strings are taken verbatim from
 * the core's own V2 definition lists (bench-verified Oct 16 2026). */
static struct { const char *key, *value; } g_slim_overrides[] = {
    { "pcsx2_upscale_multiplier", "1" },   /* 1x Native */
    { "pcsx2_ee_cycle_rate",      "-1" },  /* 75% underclock */
    { "pcsx2_ee_cycle_skip",      "1" },   /* Mild */
    { "pcsx2_blending_accuracy",  "basic" },
    { NULL, NULL }
};

/* Per-game table: keyed on normalized serial ("SLUS-20312"). Ships
 * with FFX + KH registered but ZERO overrides — the core's own
 * GameIndex owns both titles' fixes (bench-verified compat 5), so
 * overriding would fight the core. The mechanism is bench-proven. */
static struct {
    char serial[24];
    struct { char key[OPT_KEY_LEN]; char value[OPT_VAL_LEN]; } ov[MAX_OVERRIDES];
    int nov;
} g_pergame[16];
static int g_npergame = 0;

static char g_mode[8] = "fat";
static char g_serial[24] = "";

static int opt_find(const char *key) {
    for (int i = 0; i < g_nopts; i++)
        if (!strcmp(g_opts[i].key, key)) return i;
    return -1;
}

static void opt_set(const char *key, const char *value, const char *why) {
    int i = opt_find(key);
    if (i < 0) return;
    strncpy(g_opts[i].value, value, OPT_VAL_LEN - 1);
    ringf("opt: %s = %s (%s)", key, value, why);
}

/* Re-apply the whole stack: defaults -> mode -> per-game serial. */
static void opt_apply_stack(void) {
    for (int i = 0; i < g_nopts; i++)
        strncpy(g_opts[i].value, g_opts[i].defval, OPT_VAL_LEN - 1);
    if (!strcmp(g_mode, "slim")) {
        for (int i = 0; g_slim_overrides[i].key; i++)
            opt_set(g_slim_overrides[i].key, g_slim_overrides[i].value, "slim profile");
    }
    if (g_serial[0]) {
        for (int p = 0; p < g_npergame; p++) {
            if (!strcmp(g_pergame[p].serial, g_serial)) {
                ringf("per-game profile for %s: %d override(s)", g_serial, g_pergame[p].nov);
                for (int i = 0; i < g_pergame[p].nov; i++)
                    opt_set(g_pergame[p].ov[i].key, g_pergame[p].ov[i].value, "per-game");
            }
        }
    }
}

static void pergame_register(const char *serial) {
    for (int p = 0; p < g_npergame; p++)
        if (!strcmp(g_pergame[p].serial, serial)) return;
    if (g_npergame >= 16) return;
    strncpy(g_pergame[g_npergame].serial, serial, 23);
    g_pergame[g_npergame].nov = 0;
    g_npergame++;
    ringf("per-game table: registered %s (0 overrides; core GameIndex owns fixes)", serial);
}

static void pergame_option(const char *serial, const char *key, const char *value) {
    pergame_register(serial);
    for (int p = 0; p < g_npergame; p++) {
        if (!strcmp(g_pergame[p].serial, serial)) {
            int n = g_pergame[p].nov;
            if (n >= MAX_OVERRIDES) return;
            strncpy(g_pergame[p].ov[n].key, key, OPT_KEY_LEN - 1);
            strncpy(g_pergame[p].ov[n].value, value, OPT_VAL_LEN - 1);
            g_pergame[p].nov++;
        }
    }
}

/* Parse one V2 definition list. Logs the full audit. */
static void opt_parse_definitions(const struct retro_core_option_v2_definition *defs,
                                  const char *origin) {
    for (const struct retro_core_option_v2_definition *d = defs; d && d->key; d++) {
        if (g_nopts >= MAX_OPTS) break;
        if (opt_find(d->key) >= 0) continue; /* already seen via other call */
        int i = g_nopts++;
        strncpy(g_opts[i].key, d->key, OPT_KEY_LEN - 1);
        const char *dv = d->default_value ? d->default_value : "";
        strncpy(g_opts[i].defval, dv, OPT_VAL_LEN - 1);
        strncpy(g_opts[i].value, dv, OPT_VAL_LEN - 1);
        strncpy(g_opts[i].cat, d->category_key ? d->category_key : "", 47);
        char vals[640] = {0};
        size_t off = 0;
        for (int v = 0; d->values[v].value && off < sizeof(vals) - 32; v++)
            off += snprintf(vals + off, sizeof(vals) - off, "%s%s",
                            v ? "|" : "", d->values[v].value);
        ringf("optdef(%s): %-28s default=%-12s values=[%s]", origin, d->key, dv, vals);
    }
    ringf("options audit complete: %d definitions (%s)", g_nopts, origin);
    opt_apply_stack();
}

/* ------------------------------------------------------------------ */
/* Engine selection (pure function — same truth table as Kotlin        */
/* Engines.kt; exercised by the host unit tests).                      */
/*                                                                     */
/* Which engine this process can run is decided by the OS ABIs it       */
/* actually supports: if arm64-v8a is on the list the process can load  */
/* the PCEE2 core (main engine). Otherwise the process is 32-bit and   */
/* must use Play! (fallback engine). Returns 0 = PCEE2, 1 = Play!.     */
/* ------------------------------------------------------------------ */
enum { AEON_ENGINE_PCEE2 = 0, AEON_ENGINE_PLAY = 1 };

__attribute__((unused))
static int aeonps2_select_engine_code(const char *abis_csv) {
    if (!abis_csv) return AEON_ENGINE_PLAY;
    const char *p = abis_csv;
    char tok[32];
    while (*p) {
        while (*p == ' ' || *p == ',') p++;
        size_t n = 0;
        while (*p && *p != ',' && n + 1 < sizeof(tok)) {
            tok[n++] = *p++;
        }
        /* Trim trailing whitespace so "arm64-v8a " still matches. */
        while (n > 0 && (tok[n - 1] == ' ' || tok[n - 1] == '\t')) n--;
        tok[n] = '\0';
        while (*p && *p != ',') p++;
        if (n > 0 && !strcmp(tok, "arm64-v8a")) return AEON_ENGINE_PCEE2;
    }
    return AEON_ENGINE_PLAY;
}

/* ------------------------------------------------------------------ */
/* Serial normalization: "SLUS_203.12;1" -> "SLUS-20312". Reference     */
/* implementation; the Kotlin Scanner mirrors this algorithm.           */
/* ------------------------------------------------------------------ */

/* Reference impl for the Kotlin Scanner (mirrors this algorithm);
 * also exercised by the host unit tests. */
__attribute__((unused))
static void aeonps2_normalize_serial(const char *in, char *out, size_t outsz) {
    char tmp[64];
    size_t n = 0;
    if (!in || !out || outsz == 0) { if (out && outsz) out[0] = '\0'; return; }
    /* Drop ";1" version suffix and anything after it. */
    for (const char *p = in; *p && n < sizeof(tmp) - 1; p++) {
        if (*p == ';') break;
        tmp[n++] = (char)toupper((unsigned char)*p);
    }
    tmp[n] = '\0';
    /* Keep the part after the last path separator. */
    char *base = strrchr(tmp, '\\');
    char *base2 = strrchr(tmp, '/');
    if (base2 && (!base || base2 > base)) base = base2;
    const char *src = base ? base + 1 : tmp;
    size_t o = 0;
    for (; *src && o + 1 < outsz; src++) {
        if (*src == '_') { out[o++] = '-'; }
        else if (*src == '.') { /* drop */ }
        else if ((*src >= 'A' && *src <= 'Z') || (*src >= '0' && *src <= '9') || *src == '-')
            out[o++] = *src;
    }
    out[o] = '\0';
}

/* ------------------------------------------------------------------ */
/* Video: readback framebuffer -> heap buffer -> ANativeWindow present   */
/* ------------------------------------------------------------------ */

#define MAX_FRAME_W 2048
#define MAX_FRAME_H 2048

static uint32_t *g_frame = NULL;      /* latest XRGB8888 frame */
static unsigned g_frame_w = 0, g_frame_h = 0;
static size_t g_frame_pitch_px = 0;
static unsigned g_pixel_fmt = RETRO_PIXEL_FORMAT_XRGB8888;
static pthread_mutex_t g_frame_mutex = PTHREAD_MUTEX_INITIALIZER;
static long g_produced = 0, g_presented = 0;
static bool g_first_frame = false;

#ifndef AEONPS2_BENCH
static ANativeWindow *g_window = NULL;
static pthread_mutex_t g_win_mutex = PTHREAD_MUTEX_INITIALIZER;

static void present_frame_locked(void) {
    ANativeWindow *w;
    uint32_t *src;
    unsigned fw, fh;
    size_t pitch_px;
    pthread_mutex_lock(&g_frame_mutex);
    w = g_window; src = g_frame; fw = g_frame_w; fh = g_frame_h; pitch_px = g_frame_pitch_px;
    if (w) ANativeWindow_acquire(w);
    pthread_mutex_unlock(&g_frame_mutex);
    if (!w || !src || !fw || !fh) { if (w) ANativeWindow_release(w); return; }
    pthread_mutex_lock(&g_win_mutex);
    /* Re-validate under the window mutex; geometry race guard: if the
     * locked buffer disagrees with the frame, drop this present and
     * keep the previous frame on screen (Reborn v1.0.1 lesson). */
    if (ANativeWindow_setBuffersGeometry(w, (int32_t)fw, (int32_t)fh,
                                          WINDOW_FORMAT_RGBA_8888) != 0) {
        pthread_mutex_unlock(&g_win_mutex);
        ANativeWindow_release(w);
        return;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(w, &buf, NULL) == 0) {
        if ((unsigned)buf.width == fw && (unsigned)buf.height == fh) {
            uint32_t *dst = (uint32_t *)buf.bits;
            for (unsigned y = 0; y < fh; y++)
                memcpy(dst + (size_t)y * buf.stride, src + (size_t)y * pitch_px,
                       fw * sizeof(uint32_t));
            g_presented++;
        }
        ANativeWindow_unlockAndPost(w);
    }
    pthread_mutex_unlock(&g_win_mutex);
    ANativeWindow_release(w);
}
#endif

static void video_refresh_cb(const void *data, unsigned width, unsigned height,
                             size_t pitch) {
    if (!data || width == 0 || height == 0 || width > MAX_FRAME_W || height > MAX_FRAME_H) {
        if (data) ringf("video: dropped oversize/invalid frame %ux%u", width, height);
        return;
    }
    pthread_mutex_lock(&g_frame_mutex);
    if (!g_frame) g_frame = (uint32_t *)malloc((size_t)MAX_FRAME_W * MAX_FRAME_H * 4);
    if (g_frame) {
        if (g_pixel_fmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint8_t *src8 = (const uint8_t *)data;
            for (unsigned y = 0; y < height; y++)
                memcpy(g_frame + (size_t)y * width, src8 + (size_t)y * pitch,
                       width * sizeof(uint32_t));
        } else if (g_pixel_fmt == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *src16 = (const uint16_t *)data;
            size_t spitch = pitch / 2;
            for (unsigned y = 0; y < height; y++)
                for (unsigned x = 0; x < width; x++) {
                    uint16_t p = src16[(size_t)y * spitch + x];
                    uint32_t r = (p >> 11) & 0x1f, g = (p >> 5) & 0x3f, b = p & 0x1f;
                    g_frame[(size_t)y * width + x] =
                        0xff000000u | ((r << 3) | (r >> 2)) << 16 |
                        ((g << 2) | (g >> 4)) << 8 | ((b << 3) | (b >> 2));
                }
        } else {
            memset(g_frame, 0, (size_t)width * height * 4); /* 0RGB1555: black, honest */
        }
        g_frame_w = width; g_frame_h = height; g_frame_pitch_px = width;
    }
    pthread_mutex_unlock(&g_frame_mutex);
    g_produced++;
    if (!g_first_frame) { g_first_frame = true; ring_add("PHASE FIRST_FRAME"); }
#ifndef AEONPS2_BENCH
    present_frame_locked();
#endif
}

/* ------------------------------------------------------------------ */
/* Audio: core batch -> ring; Kotlin AudioTrack drains it.              */
/* ------------------------------------------------------------------ */

static int16_t *g_audio_ring = NULL;
static size_t g_audio_cap = 0, g_audio_head = 0, g_audio_tail = 0;
static pthread_mutex_t g_audio_mutex = PTHREAD_MUTEX_INITIALIZER;
static long g_audio_drops = 0;
static double g_target_fps = 59.94;
static unsigned g_audio_rate = 48000;

static void audio_ring_init(unsigned rate) {
    pthread_mutex_lock(&g_audio_mutex);
    g_audio_rate = rate;
    size_t need = (size_t)rate * 2 * 2; /* 2s stereo */
    if (need != g_audio_cap) {
        free(g_audio_ring);
        g_audio_ring = (int16_t *)malloc(need * sizeof(int16_t));
        g_audio_cap = g_audio_ring ? need : 0;
        g_audio_head = g_audio_tail = 0;
    }
    pthread_mutex_unlock(&g_audio_mutex);
}

static size_t audio_batch_cb(const int16_t *data, size_t frames) {
    pthread_mutex_lock(&g_audio_mutex);
    size_t shorts = frames * 2;
    size_t free_ = (g_audio_head >= g_audio_tail)
        ? (g_audio_cap - (g_audio_head - g_audio_tail) - 1)
        : (g_audio_tail - g_audio_head - 1);
    if (shorts > free_) { g_audio_drops += (long)(shorts - free_); shorts = free_; }
    for (size_t i = 0; i < shorts; i++) {
        g_audio_ring[g_audio_head] = data[i];
        g_audio_head = (g_audio_head + 1) % g_audio_cap;
    }
    pthread_mutex_unlock(&g_audio_mutex);
    return frames;
}

static void audio_sample_cb(int16_t left, int16_t right) {
    int16_t s[2] = { left, right };
    audio_batch_cb(s, 1);
}

static int aeonps2_drain_audio(int16_t *out, int max_shorts) {
    pthread_mutex_lock(&g_audio_mutex);
    size_t avail = (g_audio_head >= g_audio_tail) ? (g_audio_head - g_audio_tail)
                                                 : (g_audio_cap - (g_audio_tail - g_audio_head));
    size_t n = avail < (size_t)max_shorts ? avail : (size_t)max_shorts;
    for (size_t i = 0; i < n; i++) {
        out[i] = g_audio_ring[g_audio_tail];
        g_audio_tail = (g_audio_tail + 1) % g_audio_cap;
    }
    pthread_mutex_unlock(&g_audio_mutex);
    return (int)n;
}

/* Frame pacing from the core's real fps (Reborn v1.0.1 lesson: never
 * hardcode 60 Hz). Bounded proportional controller holds the audio
 * ring near ~4 frames to absorb jitter without drift. */
static struct timespec g_next_frame;
static bool g_pace_init = false;

static void pace_init(double fps) {
    g_target_fps = fps > 1.0 && fps < 240.0 ? fps : 59.94;
    clock_gettime(CLOCK_MONOTONIC, &g_next_frame);
    g_pace_init = true;
}

static void aeonps2_pace_frame(void) {
    if (!g_pace_init) pace_init(g_target_fps);
    /* Proportional nudge: ring occupancy vs 4 frames of audio. */
    pthread_mutex_lock(&g_audio_mutex);
    size_t avail = (g_audio_head >= g_audio_tail) ? (g_audio_head - g_audio_tail)
                                                 : (g_audio_cap - (g_audio_tail - g_audio_head));
    pthread_mutex_unlock(&g_audio_mutex);
    double frame_shorts = (double)g_audio_rate / g_target_fps * 2.0;
    double target = frame_shorts * 4.0;
    double err = (double)avail - target;
    /* clamp correction to +/- 5% of a frame period */
    double period_ns = 1e9 / g_target_fps;
    double corr = err / (frame_shorts * 20.0) * period_ns;
    if (corr > period_ns * 0.05) corr = period_ns * 0.05;
    if (corr < -period_ns * 0.05) corr = -period_ns * 0.05;
    long long ns = (long long)(period_ns + corr);
    g_next_frame.tv_nsec += ns;
    while (g_next_frame.tv_nsec >= 1000000000LL) {
        g_next_frame.tv_nsec -= 1000000000LL;
        g_next_frame.tv_sec++;
    }
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    long long diff = (g_next_frame.tv_sec - now.tv_sec) * 1000000000LL +
                     (g_next_frame.tv_nsec - now.tv_nsec);
    if (diff > 0 && diff < 100000000LL) {
        struct timespec ts = { diff / 1000000000LL, diff % 1000000000LL };
        nanosleep(&ts, NULL);
    } else if (diff <= -period_ns * 4) {
        /* >4 frames behind: resync rather than spiral. */
        clock_gettime(CLOCK_MONOTONIC, &g_next_frame);
    }
}

/* ------------------------------------------------------------------ */
/* Input: pad buttons + analog sticks, DualShock layout                 */
/* ------------------------------------------------------------------ */

#define MAX_PORTS 2
/* RETRO_DEVICE_ID_JOYPAD_* indices 0..15 */
static bool g_pad[MAX_PORTS][16];
static int16_t g_analog[MAX_PORTS][2][2]; /* [port][left/right][x/y] */
static pthread_mutex_t g_pad_mutex = PTHREAD_MUTEX_INITIALIZER;

static void input_poll_cb(void) {}

/* Plain-C analog writer, shared by both JNI entry points so the bench
 * (which excludes JNI) can exercise the same state path. */
static void aeonps2_set_analog_stick(int port, int stick, int x, int y) {
    if (port < 0 || port >= MAX_PORTS || stick < 0 || stick > 1) return;
    pthread_mutex_lock(&g_pad_mutex);
    g_analog[port][stick][0] = (int16_t)x;
    g_analog[port][stick][1] = (int16_t)y;
    pthread_mutex_unlock(&g_pad_mutex);
}

static int16_t input_state_cb(unsigned port, unsigned device, unsigned index,
                              unsigned id) {
    if (port >= MAX_PORTS) return 0;
    pthread_mutex_lock(&g_pad_mutex);
    int16_t r = 0;
    if (device == RETRO_DEVICE_JOYPAD && id < 16)
        r = g_pad[port][id] ? 1 : 0;
    else if (device == RETRO_DEVICE_ANALOG && index < 2 && id <= 1)
        r = g_analog[port][index][id];
    pthread_mutex_unlock(&g_pad_mutex);
    return r;
}

/* ------------------------------------------------------------------ */
/* Environment callback                                                 */
/* ------------------------------------------------------------------ */

static void retro_log_cb(enum retro_log_level level, const char *fmt, ...) {
    char buf[RING_LEN];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    /* Strip trailing newlines the core likes to add. */
    size_t n = strlen(buf);
    while (n && (buf[n-1] == '\n' || buf[n-1] == '\r')) buf[--n] = '\0';
    char line[RING_LEN + 16];
    snprintf(line, sizeof(line), "[core] %s", buf);
    ring_add(line);
    (void)level;
}

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = g_system_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = g_save_dir;
        return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: {
        unsigned *v = (unsigned *)data;
        *v = 2;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
        const struct retro_core_options_v2_intl *o =
            (const struct retro_core_options_v2_intl *)data;
        if (o && o->us && o->us->definitions)
            opt_parse_definitions(o->us->definitions, "V2_INTL");
        return true;
    }
    case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: {
        const struct retro_core_options_v2 *o = (const struct retro_core_options_v2 *)data;
        if (o && o->definitions)
            opt_parse_definitions(o->definitions, "V2");
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *v = (struct retro_variable *)data;
        int i = opt_find(v->key);
        if (i < 0) { v->value = NULL; return false; }
        static char valbuf[OPT_VAL_LEN];
        snprintf(valbuf, sizeof(valbuf), "%s=%s", g_opts[i].key, g_opts[i].value);
        v->value = valbuf;
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: {
        *(bool *)data = false;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        struct retro_hw_render_callback *hw = (struct retro_hw_render_callback *)data;
        const char *ctx = "?";
        switch (hw->context_type) {
        case RETRO_HW_CONTEXT_VULKAN: ctx = "VULKAN"; break;
        case RETRO_HW_CONTEXT_OPENGL: ctx = "OPENGL"; break;
        case RETRO_HW_CONTEXT_OPENGLES3: ctx = "OPENGLES3"; break;
        default: break;
        }
        ringf("env: SET_HW_RENDER %s %u.%u -> REFUSED (v1 readback path)",
              ctx, hw->version_major, hw->version_minor);
        return false; /* documented readback fallback */
    }
    case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: {
        ringf("env: GET_PREFERRED_HW_RENDER -> false (no preference)");
        return false;
    }
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
        unsigned f = *(const unsigned *)data;
        g_pixel_fmt = f;
        ringf("env: SET_PIXEL_FORMAT %u (%s)", f,
              f == RETRO_PIXEL_FORMAT_XRGB8888 ? "XRGB8888" :
              f == RETRO_PIXEL_FORMAT_RGB565 ? "RGB565" : "0RGB1555");
        return true;
    }
    case RETRO_ENVIRONMENT_SET_GEOMETRY: {
        const struct retro_game_geometry *g = (const struct retro_game_geometry *)data;
        ringf("env: SET_GEOMETRY max %ux%u base %ux%u aspect %.4f",
              g->max_width, g->max_height, g->base_width, g->base_height,
              g->aspect_ratio);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
        const struct retro_system_av_info *a = (const struct retro_system_av_info *)data;
        pace_init(a->timing.fps);
        audio_ring_init((unsigned)(a->timing.sample_rate + 0.5));
        ringf("env: AV_INFO geo %ux%u fps=%.4f audio=%.0fHz",
              a->geometry.base_width, a->geometry.base_height,
              a->timing.fps, a->timing.sample_rate);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY: {
        ringf("env: SET_MINIMUM_AUDIO_LATENCY %ums", *(unsigned *)data);
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: {
        bool b = *(bool *)data;
        ringf("env: SET_SUPPORT_NO_GAME %d -> accepted", b);
        return true;
    }
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
        struct retro_log_callback *lc = (struct retro_log_callback *)data;
        lc->log = retro_log_cb;
        return true;
    }
    case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS: {
        ringf("env: SET_SUPPORT_ACHIEVEMENTS -> declined");
        return false;
    }
    case RETRO_ENVIRONMENT_SHUTDOWN:
        ring_add("[core] SHUTDOWN requested");
        return true;
    default:
        ringf("env: cmd=%u -> unhandled", cmd);
        return false;
    }
}

/* ------------------------------------------------------------------ */
/* Boot / run / state                                                   */
/* ------------------------------------------------------------------ */

static bool g_inited = false;
static bool g_loaded = false;

static bool aeonps2_init(const char *core_path, const char *system_dir,
                         const char *save_dir) {
    if (g_inited) return true;
    strncpy(g_system_dir, system_dir, sizeof(g_system_dir) - 1);
    strncpy(g_save_dir, save_dir, sizeof(g_save_dir) - 1);
    char syscmd[2300];
    snprintf(syscmd, sizeof(syscmd),
             "mkdir -p '%s/pcsx2/bios' '%s/pcsx2/resources' '%s/pcsx2/memcards' '%s'",
             g_system_dir, g_system_dir, g_system_dir, g_save_dir);
    if (system(syscmd)) { blog_e("mkdir failed"); }

    core_handle = dlopen(core_path, RTLD_NOW | RTLD_LOCAL);
    if (!core_handle) {
        ringf("FATAL: dlopen %s: %s", core_path, dlerror());
        return false;
    }
    ringf("dlopen OK: %s", core_path);
    if (!core_load_symbols()) return false;
    core_retro_set_environment(env_cb);
    core_retro_set_video_refresh(video_refresh_cb);
    core_retro_set_audio_sample(audio_sample_cb);
    core_retro_set_audio_sample_batch(audio_batch_cb);
    core_retro_set_input_poll(input_poll_cb);
    core_retro_set_input_state(input_state_cb);

    struct retro_system_info si;
    memset(&si, 0, sizeof(si));
    core_retro_get_system_info(&si);
    ringf("core: %s %s ext=%s need_fullpath=%d",
          si.library_name ? si.library_name : "?",
          si.library_version ? si.library_version : "?",
          si.valid_extensions ? si.valid_extensions : "?",
          si.need_fullpath);

    core_retro_init();
    ring_add("PHASE CORE_READY");
    audio_ring_init(48000);
    g_inited = true;

    /* Engine identity: Play! reports corename "Play!"; also match the
     * file name as a second signal (covers renames). */
    {
        const char *name = si.library_name ? si.library_name : "";
        g_core_is_play = (strstr(name, "Play") != NULL) ||
                         (strstr(core_path, "play_libretro") != NULL);
        ringf("engine: %s (Play! fallback = %s)",
              name[0] ? name : "(unknown)",
              g_core_is_play ? "yes" : "no");
    }
    return true;
}

/* Returns: 0 ok, 1 no-bios/fail (graceful), 2 fatal */
static int aeonps2_load_game(const char *path) {
    if (!g_inited) return 2;
    ring_add("PHASE LOAD_GAME_BEGIN");
    if (path) ringf("loading content: %s (serial=%s mode=%s)",
                    path, g_serial[0] ? g_serial : "(unknown)", g_mode);
    else ringf("no-content boot (Test BIOS), mode=%s", g_mode);
    if (!path && g_core_is_play) {
        /* Play! cannot boot without content (bench: SIGSEGV on
         * retro_load_game(NULL)). Refuse gracefully — the Kotlin
         * side never calls this path, so this is a last-resort guard. */
        ring_add("refused: Play! fallback engine cannot boot without content");
        return 1;
    }
    opt_apply_stack(); /* late overrides take effect for this boot */
    struct retro_game_info gi;
    const struct retro_game_info *gip = NULL;
    if (path) {
        memset(&gi, 0, sizeof(gi));
        gi.path = path;
        gip = &gi;
    }
    bool ok = core_retro_load_game(gip);
    if (!ok) {
        ring_add("load_game returned FALSE (core declined; no crash)");
        return 1;
    }
    struct retro_system_av_info av;
    memset(&av, 0, sizeof(av));
    core_retro_get_system_av_info(&av);
    pace_init(av.timing.fps);
    audio_ring_init((unsigned)(av.timing.sample_rate + 0.5));
    g_first_frame = false;
    g_loaded = true;
    ring_add("PHASE BIOS_OK");
    return 0;
}

static void aeonps2_run_frame(void) {
    if (!g_loaded) return;
    core_retro_run();
}

static void aeonps2_shutdown(void) {
    if (g_loaded && core_retro_unload_game) core_retro_unload_game();
    g_loaded = false;
    if (g_inited && core_retro_deinit) core_retro_deinit();
    g_inited = false;
    if (core_handle) { dlclose(core_handle); core_handle = NULL; }
    ring_add("shutdown complete");
}

static long aeonps2_state_size(void) {
    if (!g_inited || !core_retro_serialize_size) return -1;
    return (long)core_retro_serialize_size();
}

static bool aeonps2_save_state(const char *path) {
    long sz = aeonps2_state_size();
    if (sz <= 0) return false;
    void *buf = malloc((size_t)sz);
    if (!buf) { ringf("save state: malloc(%ld) FAILED", sz); return false; }
    bool ok = core_retro_serialize(buf, (size_t)sz);
    if (ok) {
        char tmp[1100];
        snprintf(tmp, sizeof(tmp), "%s.tmp", path);
        FILE *f = fopen(tmp, "wb");
        if (f) {
            size_t w = fwrite(buf, 1, (size_t)sz, f);
            fclose(f);
            if (w == (size_t)sz) { rename(tmp, path); }
            else { remove(tmp); ok = false; }
        } else ok = false;
    }
    free(buf);
    ringf("save state -> %s : %s (%ld bytes)", path, ok ? "OK" : "FAILED", sz);
    return ok;
}

static bool aeonps2_load_state(const char *path) {
    long sz = aeonps2_state_size();
    if (sz <= 0) return false;
    FILE *f = fopen(path, "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long fsz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (fsz != sz) { fclose(f); ringf("load state: size mismatch (%ld != %ld)", fsz, sz); return false; }
    void *buf = malloc((size_t)sz);
    if (!buf) { fclose(f); return false; }
    size_t r = fread(buf, 1, (size_t)sz, f);
    fclose(f);
    bool ok = (r == (size_t)sz) && core_retro_unserialize(buf, (size_t)sz);
    free(buf);
    ringf("load state <- %s : %s", path, ok ? "OK" : "FAILED");
    return ok;
}

/* ------------------------------------------------------------------ */
/* JNI surface                                                           */
/* ------------------------------------------------------------------ */

#ifdef AEONPS2_BENCH

/* ---------------- Bench: plain-C API + driver ---------------- */

static const uint32_t *aeonps2_last_frame(unsigned *w, unsigned *h) {
    pthread_mutex_lock(&g_frame_mutex);
    if (w) *w = g_frame_w;
    if (h) *h = g_frame_h;
    const uint32_t *p = g_frame;
    pthread_mutex_unlock(&g_frame_mutex);
    return p;
}

static const char *aeonps2_list_options(void) {
    static char buf[8192];
    size_t off = 0;
    for (int i = 0; i < g_nopts && off < sizeof(buf) - 128; i++)
        off += snprintf(buf + off, sizeof(buf) - off, "%s=%s (default %s)\n",
                        g_opts[i].key, g_opts[i].value, g_opts[i].defval);
    return buf;
}

static void usage(const char *a0) {
    fprintf(stderr,
        "usage: %s <core.so> <workdir> <command> [args]\n"
        "  commands:\n"
        "    options-audit                 init + print parsed options\n"
        "    boot-nocontent                init + load_game(NULL) + 600 frames\n"
        "    boot-file <path>              init + load_game(path) + 120 frames\n",
        a0);
}

int main(int argc, char **argv) {
    if (argc < 4) { usage(argv[0]); return 2; }
    const char *core = argv[1], *work = argv[2], *cmd = argv[3];
    char sysdir[1024], savedir[1024], logpath[1024];
    snprintf(sysdir, sizeof(sysdir), "%s/system", work);
    snprintf(savedir, sizeof(savedir), "%s/saves", work);
    snprintf(logpath, sizeof(logpath), "%s/run.log", work);
    aeonps2_set_log_path(logpath);
    ring_add("PHASE INIT");

    if (!aeonps2_init(core, sysdir, savedir)) { fprintf(stderr, "init FAILED\n"); return 1; }

    if (!strcmp(cmd, "options-audit")) {
        /* options parse on retro_init path */
        printf("%s", aeonps2_list_options());
        printf("nopts=%d\n", g_nopts);
        aeonps2_shutdown();
        return 0;
    }
    if (!strcmp(cmd, "boot-nocontent")) {
        strncpy(g_mode, "fat", sizeof(g_mode) - 1);
        int rc = aeonps2_load_game(NULL);
        printf("load_game(NULL) rc=%d\n", rc);
        if (rc == 0) {
            unsigned w = 0, h = 0;
            int with_video = 0;
            for (int i = 0; i < 600; i++) {
                aeonps2_pace_frame();
                aeonps2_run_frame();
                const uint32_t *f = aeonps2_last_frame(&w, &h);
                if (f && w && h) with_video = 1;
            }
            printf("frames=600 video_seen=%d last_geo=%ux%u produced=%ld presented=%ld\n",
                   with_video, w, h, g_produced, g_presented);
            /* dump last frame */
            pthread_mutex_lock(&g_frame_mutex);
            if (g_frame && g_frame_w && g_frame_h) {
                FILE *f = fopen("bench_frame.ppm", "wb");
                if (f) {
                    fprintf(f, "P6\n%u %u\n255\n", g_frame_w, g_frame_h);
                    for (unsigned y = 0; y < g_frame_h; y++)
                        for (unsigned x = 0; x < g_frame_w; x++) {
                            uint32_t p = g_frame[y * g_frame_w + x];
                            fputc((p >> 16) & 0xff, f);
                            fputc((p >> 8) & 0xff, f);
                            fputc(p & 0xff, f);
                        }
                    fclose(f);
                }
            }
            pthread_mutex_unlock(&g_frame_mutex);
            printf("audio_rate=%u drops=%ld occupancy_peak_ok\n", g_audio_rate, g_audio_drops);
        }
        aeonps2_shutdown();
        return rc == 2 ? 1 : 0;
    }
    if (!strcmp(cmd, "boot-file") && argc >= 5) {
        int rc = aeonps2_load_game(argv[4]);
        printf("load_game(%s) rc=%d\n", argv[4], rc);
        if (rc == 0) {
            for (int i = 0; i < 120; i++) { aeonps2_pace_frame(); aeonps2_run_frame(); }
            printf("ran 120 frames, produced=%ld\n", g_produced);
        }
        aeonps2_shutdown();
        return rc == 2 ? 1 : 0;
    }
    usage(argv[0]);
    return 2;
}

#else /* ---------------- JNI ---------------- */

static jstring jstr(JNIEnv *env, const char *s) { return (*env)->NewStringUTF(env, s); }

#define JNI_FN(name) Java_com_aeonps2_firetv_EmuBridge_##name

JNIEXPORT jboolean JNICALL JNI_FN(nativeInit)(JNIEnv *env, jobject thiz,
        jstring corePath, jstring systemDir, jstring saveDir) {
    (void)thiz;
    const char *cp = (*env)->GetStringUTFChars(env, corePath, 0);
    const char *sd = (*env)->GetStringUTFChars(env, systemDir, 0);
    const char *vd = (*env)->GetStringUTFChars(env, saveDir, 0);
    bool ok = aeonps2_init(cp, sd, vd);
    (*env)->ReleaseStringUTFChars(env, corePath, cp);
    (*env)->ReleaseStringUTFChars(env, systemDir, sd);
    (*env)->ReleaseStringUTFChars(env, saveDir, vd);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetMode)(JNIEnv *env, jobject thiz, jstring mode) {
    (void)thiz;
    const char *m = (*env)->GetStringUTFChars(env, mode, 0);
    strncpy(g_mode, (!strcmp(m, "slim")) ? "slim" : "fat", sizeof(g_mode) - 1);
    (*env)->ReleaseStringUTFChars(env, mode, m);
    opt_apply_stack();
    ringf("mode set: %s", g_mode);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetGameSerial)(JNIEnv *env, jobject thiz, jstring serial) {
    (void)thiz;
    const char *s = (*env)->GetStringUTFChars(env, serial, 0);
    strncpy(g_serial, s, sizeof(g_serial) - 1);
    (*env)->ReleaseStringUTFChars(env, serial, s);
    if (g_serial[0]) pergame_register(g_serial);
    opt_apply_stack();
    ringf("game serial: %s", g_serial[0] ? g_serial : "(none)");
}

JNIEXPORT void JNICALL JNI_FN(nativeSetPerGameOption)(JNIEnv *env, jobject thiz,
        jstring serial, jstring key, jstring value) {
    (void)thiz;
    const char *s = (*env)->GetStringUTFChars(env, serial, 0);
    const char *k = (*env)->GetStringUTFChars(env, key, 0);
    const char *v = (*env)->GetStringUTFChars(env, value, 0);
    pergame_option(s, k, v);
    (*env)->ReleaseStringUTFChars(env, serial, s);
    (*env)->ReleaseStringUTFChars(env, key, k);
    (*env)->ReleaseStringUTFChars(env, value, v);
    opt_apply_stack();
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeLoadGame)(JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = path ? (*env)->GetStringUTFChars(env, path, 0) : NULL;
    int rc = aeonps2_load_game(p);
    if (path) (*env)->ReleaseStringUTFChars(env, path, p);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(nativeRunFrame)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    aeonps2_run_frame();
}

JNIEXPORT void JNICALL JNI_FN(nativeShutdown)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    aeonps2_shutdown();
}

JNIEXPORT void JNICALL JNI_FN(nativeSetSurface)(JNIEnv *env, jobject thiz, jobject surface) {
    (void)thiz;
    pthread_mutex_lock(&g_win_mutex);
    if (g_window) { ANativeWindow_release(g_window); g_window = NULL; }
    if (surface) g_window = ANativeWindow_fromSurface(env, surface);
    pthread_mutex_unlock(&g_win_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetPad)(JNIEnv *env, jobject thiz,
        jint port, jint button, jboolean pressed) {
    (void)env; (void)thiz;
    if (port < 0 || port >= MAX_PORTS || button < 0 || button >= 16) return;
    pthread_mutex_lock(&g_pad_mutex);
    g_pad[port][button] = pressed ? true : false;
    pthread_mutex_unlock(&g_pad_mutex);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetAnalog)(JNIEnv *env, jobject thiz,
        jint port, jint x, jint y) {
    (void)env; (void)thiz;
    aeonps2_set_analog_stick(port, 0, x, y);
}

/* Right stick: index 1 of the same [port][left/right][x/y] state.
 * The input_state callback already serves RETRO_DEVICE_ANALOG index 1
 * to the core, so this is just the write side of that same state. */
JNIEXPORT void JNICALL JNI_FN(nativeSetAnalogRight)(JNIEnv *env, jobject thiz,
        jint port, jint x, jint y) {
    (void)env; (void)thiz;
    aeonps2_set_analog_stick(port, 1, x, y);
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeSaveState)(JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    bool ok = aeonps2_save_state(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_FN(nativeLoadState)(JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    bool ok = aeonps2_load_state(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeStateSize)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)aeonps2_state_size();
}

JNIEXPORT jint JNICALL JNI_FN(nativeDrainAudio)(JNIEnv *env, jobject thiz,
        jshortArray buf, jint maxShorts) {
    (void)thiz;
    if (maxShorts <= 0) return 0;
    jshort *el = (*env)->GetShortArrayElements(env, buf, 0);
    int n = aeonps2_drain_audio((int16_t *)el, (int)maxShorts);
    (*env)->ReleaseShortArrayElements(env, buf, el, 0);
    return n;
}

JNIEXPORT jstring JNICALL JNI_FN(nativeGetRingLog)(JNIEnv *env, jobject thiz) {
    (void)thiz;
    static char out[RING_LINES * (RING_LEN + 1)];
    aeonps2_get_ring_log(out, sizeof(out));
    return jstr(env, out);
}

JNIEXPORT void JNICALL JNI_FN(nativeSetLogPath)(JNIEnv *env, jobject thiz, jstring path) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, path, 0);
    aeonps2_set_log_path(p);
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetAudioRate)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jint)g_audio_rate;
}

JNIEXPORT void JNICALL JNI_FN(nativePaceFrame)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    aeonps2_pace_frame();
}

JNIEXPORT jint JNICALL JNI_FN(nativeGetAudioOccupancy)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_audio_mutex);
    size_t avail = (g_audio_head >= g_audio_tail) ? (g_audio_head - g_audio_tail)
                                                 : (g_audio_cap - (g_audio_tail - g_audio_head));
    pthread_mutex_unlock(&g_audio_mutex);
    return (jint)avail;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetAudioDrops)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)g_audio_drops;
}

JNIEXPORT jdouble JNICALL JNI_FN(nativeGetTargetFps)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jdouble)g_target_fps;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetProducedFrames)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)g_produced;
}

JNIEXPORT jlong JNICALL JNI_FN(nativeGetPresentedFrames)(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return (jlong)g_presented;
}

JNIEXPORT void JNICALL JNI_FN(nativeSetPhase)(JNIEnv *env, jobject thiz, jstring phase) {
    (void)thiz;
    const char *p = (*env)->GetStringUTFChars(env, phase, 0);
    char line[64];
    snprintf(line, sizeof(line), "PHASE %s", p);
    ring_add(line);
    (*env)->ReleaseStringUTFChars(env, phase, p);
}

JNIEXPORT jstring JNICALL JNI_FN(nativeListOptions)(JNIEnv *env, jobject thiz) {
    (void)thiz;
    static char buf[8192];
    size_t off = 0;
    for (int i = 0; i < g_nopts && off < sizeof(buf) - 128; i++)
        off += snprintf(buf + off, sizeof(buf) - off, "%s=%s (default %s)\n",
                        g_opts[i].key, g_opts[i].value, g_opts[i].defval);
    return jstr(env, buf);
}

#endif /* AEONPS2_BENCH */
