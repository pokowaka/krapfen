// Offline measurements of the dough synth engine, used to calibrate
// Krapfen's mapping of Strudel controls onto dough.
//
//   level  : peak/RMS of a sustained tone, to match superdough's loudness
//   rt60   : reverb decay time for each dough `verbdecay` value, to map
//            superdough's `roomsize` (an RT60 in seconds) onto dough
//
// Build & run: tools/dough-bench/run.sh
//
// Uses the same dough configuration as the app (see
// krapfen/src/main/cpp/dough_impl.c and krapfen_jni.c).

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dough.h"

void dough_render(float *out, int frames, double start_time);
void dough_reset(void);

#define SR 48000
#define CH 2

static double now = 0;

static void render(float *buf, int frames)
{
    // Render in blocks like the audio callback does.
    for (int off = 0; off < frames; off += 256)
    {
        int n = frames - off < 256 ? frames - off : 256;
        dough_render(buf + off * CH, n, now);
        now += n / (double)SR;
    }
}

// Renders `seconds` of a sustained tone and reports per-channel peak and RMS
// over the steady-state part (after 0.5 s).
static void level(const char *label, const char *params)
{
    int frames = SR * 2;
    float *buf = calloc(frames * CH, sizeof(float));
    char msg[512];
    snprintf(msg, sizeof msg, "dough/play/duration/1.8/%s", params);
    dough_evaluate(msg);
    render(buf, frames);

    double peak[CH] = {0}, sq[CH] = {0};
    int n = 0;
    for (int i = SR / 2; i < (int)(SR * 1.5); i++, n++)
        for (int c = 0; c < CH; c++)
        {
            double v = fabs(buf[i * CH + c]);
            if (v > peak[c])
                peak[c] = v;
            sq[c] += v * v;
        }
    printf("level  %-28s peakL %.4f peakR %.4f rmsL %.4f rmsR %.4f\n", label, peak[0], peak[1],
           sqrt(sq[0] / n), sqrt(sq[1] / n));
    free(buf);
    dough_evaluate("dough/hush");
    float tail[SR * CH];
    render(tail, SR); // let releases/tails finish
}

// Feeds a 5 ms noise burst into the reverb and measures RT60 from the
// Schroeder backward-integrated energy decay (T20: fit -5..-25 dB, x3).
static double rt60(double decay)
{
    int frames = SR * 30;
    float *buf = calloc(frames * CH, sizeof(float));
    char msg[256];
    snprintf(msg, sizeof msg,
             "dough/play/s/white/duration/0.005/attack/0.0005/release/0.001/gain/1/verb/1/verbdecay/%.4f/verbpredelay/0",
             decay);
    dough_evaluate(msg);
    render(buf, frames);

    // Energy (mono sum), starting after the dry burst.
    int start = SR / 50; // 20 ms
    int n = frames - start;
    double *e = calloc(n, sizeof(double));
    for (int i = 0; i < n; i++)
    {
        double m = 0.5 * (buf[(start + i) * CH] + buf[(start + i) * CH + 1]);
        e[i] = m * m;
    }
    // Schroeder integral
    for (int i = n - 2; i >= 0; i--)
        e[i] += e[i + 1];
    double total = e[0];
    double t5 = -1, t25 = -1;
    for (int i = 0; i < n; i++)
    {
        double db = 10 * log10(e[i] / total + 1e-30);
        if (t5 < 0 && db <= -5)
            t5 = i / (double)SR;
        if (t25 < 0 && db <= -25)
        {
            t25 = i / (double)SR;
            break;
        }
    }
    free(e);
    free(buf);
    dough_evaluate("dough/hush");
    // Flush the reverb tank completely before the next measurement.
    dough_init(SR, 64, 1, 1, 1 << 16, 1024, 4);
    now = 0;
    if (t5 < 0 || t25 < 0)
        return INFINITY;
    return (t25 - t5) * 3.0;
}

// Renders one dough message for 4 s, applies Krapfen's master gain (3x)
// and reports the wet level (RMS 0.1-0.6 s) and RT60 from 0.1 s -- the
// same measurements as tools/analyze/reverb.py makes on recordings.
static void measure_message(const char *msg)
{
    int frames = SR * 4;
    float *buf = calloc(frames * CH, sizeof(float));
    dough_evaluate(msg);
    render(buf, frames);
    int n = frames - SR / 10;
    double *e = calloc(n, sizeof(double)), wet = 0;
    for (int i = 0; i < n; i++)
    {
        double m = 1.5 * (buf[(SR / 10 + i) * CH] + buf[(SR / 10 + i) * CH + 1]); // 3x gain, mono mean
        e[i] = m * m;
        if (i < SR / 2)
            wet += m * m;
    }
    for (int i = n - 2; i >= 0; i--)
        e[i] += e[i + 1];
    double t5 = -1, t25 = -1;
    for (int i = 0; i < n && t25 < 0; i++)
    {
        double d = 10 * log10(e[i] / e[0] + 1e-30);
        if (t5 < 0 && d <= -5)
            t5 = i / (double)SR;
        if (d <= -25)
            t25 = i / (double)SR;
    }
    printf("msg    wet %6.1f dB  RT60 %5.2f s  %s\n", 10 * log10(wet / (SR / 2) + 1e-30), (t25 - t5) * 3, msg);
    free(e);
    free(buf);
    dough_evaluate("dough/hush");
    dough_init(SR, 64, 1, 1, 1 << 16, 1024, 4);
    now = 0;
}

int main(int argc, char **argv)
{
    const char *mode = argc > 1 ? argv[1] : "all";
    dough_init(SR, 64, 1, 1, 1 << 16, 1024, 4);

    if (!strcmp(mode, "level") || !strcmp(mode, "all"))
    {
        // Reference: superdough's basic synths are osc * 0.3 * ADSR * gain,
        // so sine/gain 1/sustain 1 should peak at 0.3 per channel there.
        level("sine g1 s1", "s/sine/freq/440/gain/1/attack/0.001/decay/0.05/sustain/1");
        level("sine g1 s1 pan.5", "s/sine/freq/440/gain/1/attack/0.001/decay/0.05/sustain/1/pan/0.5");
        level("sine g1 s1 pan0", "s/sine/freq/440/gain/1/attack/0.001/decay/0.05/sustain/1/pan/0");
        level("sine g1 s1 pan1", "s/sine/freq/440/gain/1/attack/0.001/decay/0.05/sustain/1/pan/1");
        level("sine g1 s1 pan.25", "s/sine/freq/440/gain/1/attack/0.001/decay/0.05/sustain/1/pan/0.25");
        level("sine g0.5 (defaults)", "s/sine/freq/440/gain/0.5");
        level("sine (all defaults)", "s/sine/freq/440");
        level("sawtooth g1 s1", "s/sawtooth/freq/220/gain/1/attack/0.001/decay/0.05/sustain/1");
        level("triangle g1 s1", "s/triangle/freq/440/gain/1/attack/0.001/decay/0.05/sustain/1");
        level("square g1 s1", "s/square/freq/220/gain/1/attack/0.001/decay/0.05/sustain/1");
    }
    if (!strcmp(mode, "filter"))
    {
        // Harmonic levels (dB re fundamental) of a sawtooth through lpf, to
        // compare with the ideal Web Audio biquad.
        int frames = SR;
        float *buf = calloc(frames * CH, sizeof(float));
        dough_evaluate("dough/play/s/sawtooth/freq/261.63/duration/1/gain/1/attack/0.001/decay/0.05/sustain/1/lpf/1000/lpq/1");
        render(buf, frames);
        double h[9] = {0};
        for (int k = 1; k <= 8; k++)
        {
            double re = 0, im = 0, f = 261.63 * k;
            for (int i = SR / 4; i < SR; i++)
            {
                double w = 0.5 - 0.5 * cos(2 * M_PI * (i - SR / 4) / (SR * 0.75));
                re += w * buf[i * CH] * cos(2 * M_PI * f * i / SR);
                im += w * buf[i * CH] * sin(2 * M_PI * f * i / SR);
            }
            h[k] = sqrt(re * re + im * im);
        }
        printf("filter saw lpf 1000 lpq 1:");
        for (int k = 1; k <= 8; k++)
            printf(" %6.1f", 20 * log10(h[k] / h[1]));
        printf("\n");
        free(buf);
        return 0;
    }
    if (!strcmp(mode, "msg"))
    {
        for (int i = 2; i < argc; i++)
            measure_message(argv[i]);
        return 0;
    }
    if (!strcmp(mode, "rt60") && argc > 2)
    {
        // Measure specific decay values, e.g. to verify a mapping.
        for (int i = 2; i < argc; i++)
            printf("rt60   decay %.4f  %.3f s\n", atof(argv[i]), rt60(atof(argv[i])));
        return 0;
    }
    if (!strcmp(mode, "rt60") || !strcmp(mode, "all"))
    {
        static const double decays[] = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.75, 0.8, 0.85,
                                        0.9, 0.92, 0.94, 0.96, 0.97, 0.98, 0.99};
        for (size_t i = 0; i < sizeof decays / sizeof *decays; i++)
            printf("rt60   decay %.2f  %.3f s\n", decays[i], rt60(decays[i]));
    }
    return 0;
}
