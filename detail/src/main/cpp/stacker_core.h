#pragma once

#include <cstdint>
#include <vector>

namespace proshot {

/**
 * Burst merger, the native twin of the Kotlin Stacker: aligns every added frame to the reference
 * on a luma pyramid (8x, 4x, 2x, 1x), then averages it in with a weight that falls to zero where
 * it disagrees with the reference. Planes are tightly packed 4:2:0; width and height are even.
 */
class NativeStacker {
public:
    NativeStacker(const uint8_t* y, const uint8_t* u, const uint8_t* v, int w, int h, float sigma);

    /** Adds a frame; writes the found (dx, dy) shift into [shift]. */
    void add(const uint8_t* y, const uint8_t* u, const uint8_t* v, int shift[2]);

    /** Writes the merged frame into the given planes. */
    void result(uint8_t* y, uint8_t* u, uint8_t* v) const;

    int frames() const { return frames_; }

private:
    void estimate(const uint8_t* cur, int out[2]) const;

    int w_, h_, cw_, ch_;
    float k2_;
    int frames_ = 1;
    std::vector<uint8_t> refY_;
    std::vector<std::vector<uint8_t>> refLevels_;
    std::vector<float> sumY_, wgtY_, sumU_, sumV_, wgtC_;
};

}  // namespace proshot
