#include "stacker_core.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <thread>

namespace proshot {

namespace {

constexpr int kFactors[4] = {8, 4, 2, 1};
constexpr int kMaxShift = 64;

/** Runs fn(begin, end) over [0, n) split across up to 8 threads. */
void parallelRows(int n, const std::function<void(int, int)>& fn) {
    unsigned t = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    if (t == 1 || n < 64) {
        fn(0, n);
        return;
    }
    std::vector<std::thread> threads;
    int chunk = (static_cast<int>(n) + static_cast<int>(t) - 1) / static_cast<int>(t);
    for (unsigned i = 0; i < t; i++) {
        int a = static_cast<int>(i) * chunk;
        int b = std::min(n, a + chunk);
        if (a >= b) break;
        threads.emplace_back(fn, a, b);
    }
    for (auto& th : threads) th.join();
}

std::vector<uint8_t> downscale(const uint8_t* src, int w, int h, int f) {
    if (f == 1) return std::vector<uint8_t>(src, src + static_cast<size_t>(w) * h);
    int lw = w / f, lh = h / f;
    std::vector<uint8_t> out(static_cast<size_t>(lw) * lh);
    parallelRows(lh, [&](int y0, int y1) {
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < lw; x++) {
                int s = 0;
                for (int j = 0; j < f; j++) {
                    const uint8_t* row = src + static_cast<size_t>(y * f + j) * w + x * f;
                    for (int i = 0; i < f; i++) s += row[i];
                }
                out[static_cast<size_t>(y) * lw + x] = static_cast<uint8_t>(s / (f * f));
            }
        }
    });
    return out;
}

int64_t sad(const uint8_t* r, const uint8_t* c, int w, int h, int sx, int sy, int inset) {
    int step = w > 1500 ? 3 : 1;
    int64_t sum = 0;
    for (int y = inset; y < h - inset; y += step) {
        const uint8_t* ro = r + static_cast<size_t>(y) * w;
        const uint8_t* co = c + static_cast<size_t>(y + sy) * w + sx;
        for (int x = inset; x < w - inset; x += step) sum += std::abs(int(ro[x]) - int(co[x]));
    }
    return sum;
}

inline int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

}  // namespace

NativeStacker::NativeStacker(const uint8_t* y, const uint8_t* u, const uint8_t* v, int w, int h, float sigma)
    : w_(w), h_(h), cw_(w / 2), ch_(h / 2), k2_((4.f * sigma) * (4.f * sigma)) {
    const size_t n = static_cast<size_t>(w) * h;
    const size_t cn = static_cast<size_t>(cw_) * ch_;
    refY_.assign(y, y + n);
    for (int f : kFactors) refLevels_.push_back(downscale(y, w, h, f));
    sumY_.resize(n);
    wgtY_.assign(n, 1.f);
    sumU_.resize(cn);
    sumV_.resize(cn);
    wgtC_.assign(cn, 1.f);
    for (size_t i = 0; i < n; i++) sumY_[i] = y[i];
    for (size_t i = 0; i < cn; i++) {
        sumU_[i] = u[i];
        sumV_[i] = v[i];
    }
}

void NativeStacker::estimate(const uint8_t* cur, int out[2]) const {
    int dx = 0, dy = 0;
    for (int i = 0; i < 4; i++) {
        int f = kFactors[i];
        int lw = w_ / f, lh = h_ / f;
        std::vector<uint8_t> c = downscale(cur, w_, h_, f);
        const std::vector<uint8_t>& r = refLevels_[i];
        int radius = (i == 0) ? 6 : 2;
        int cx = dx / f, cy = dy / f;
        int limit = kMaxShift / f;
        int inset = limit + 2;
        int64_t best = INT64_MAX;
        int bx = cx, by = cy;
        for (int sy = cy - radius; sy <= cy + radius; sy++) {
            if (sy < -limit || sy > limit) continue;
            for (int sx = cx - radius; sx <= cx + radius; sx++) {
                if (sx < -limit || sx > limit) continue;
                int64_t s = sad(r.data(), c.data(), lw, lh, sx, sy, inset);
                if (s < best) {
                    best = s;
                    bx = sx;
                    by = sy;
                }
            }
        }
        dx = bx * f;
        dy = by * f;
    }
    out[0] = dx;
    out[1] = dy;
}

void NativeStacker::add(const uint8_t* y, const uint8_t* u, const uint8_t* v, int shift[2]) {
    estimate(y, shift);
    const int dx = shift[0], dy = shift[1];
    const int w = w_, h = h_, cw = cw_, ch = ch_;
    const float k2 = k2_;
    const uint8_t* ry = refY_.data();

    parallelRows(h, [&](int y0, int y1) {
        for (int row = y0; row < y1; row++) {
            int sy = row + dy;
            if (sy < 0 || sy >= h) continue;
            const uint8_t* rr = ry + static_cast<size_t>(row) * w;
            const uint8_t* cr = y + static_cast<size_t>(sy) * w;
            float* sum = &sumY_[static_cast<size_t>(row) * w];
            float* wgt = &wgtY_[static_cast<size_t>(row) * w];
            int xs = std::max(0, -dx), xe = std::min(w, w - dx);
            for (int x = xs; x < xe; x++) {
                float d = static_cast<float>(int(rr[x]) - int(cr[x + dx]));
                float wt = 1.f - d * d / k2;
                if (wt > 0.f) {
                    sum[x] += cr[x + dx] * wt;
                    wgt[x] += wt;
                }
            }
        }
    });

    const int cdx = dx >> 1, cdy = dy >> 1;
    parallelRows(ch, [&](int y0, int y1) {
        for (int row = y0; row < y1; row++) {
            int sy = row + cdy;
            if (sy < 0 || sy >= ch) continue;
            int ly = 2 * row, lsy = ly + dy;
            if (lsy < 0 || lsy >= h) continue;
            for (int x = 0; x < cw; x++) {
                int sx = x + cdx;
                if (sx < 0 || sx >= cw) continue;
                int lx = 2 * x, lsx = lx + dx;
                if (lsx < 0 || lsx >= w) continue;
                float d = static_cast<float>(int(ry[static_cast<size_t>(ly) * w + lx]) -
                                             int(y[static_cast<size_t>(lsy) * w + lsx]));
                float wt = 1.f - d * d / k2;
                if (wt > 0.f) {
                    size_t i = static_cast<size_t>(row) * cw + x;
                    size_t j = static_cast<size_t>(sy) * cw + sx;
                    sumU_[i] += u[j] * wt;
                    sumV_[i] += v[j] * wt;
                    wgtC_[i] += wt;
                }
            }
        }
    });
    frames_++;
}

void NativeStacker::result(uint8_t* y, uint8_t* u, uint8_t* v) const {
    const size_t n = static_cast<size_t>(w_) * h_;
    const size_t cn = static_cast<size_t>(cw_) * ch_;
    for (size_t i = 0; i < n; i++) y[i] = static_cast<uint8_t>(clampInt(static_cast<int>(std::lround(sumY_[i] / wgtY_[i])), 0, 255));
    for (size_t i = 0; i < cn; i++) {
        u[i] = static_cast<uint8_t>(clampInt(static_cast<int>(std::lround(sumU_[i] / wgtC_[i])), 0, 255));
        v[i] = static_cast<uint8_t>(clampInt(static_cast<int>(std::lround(sumV_[i] / wgtC_[i])), 0, 255));
    }
}

}  // namespace proshot
