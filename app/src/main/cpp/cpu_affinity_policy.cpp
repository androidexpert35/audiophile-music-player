// ─────────────────────────────────────────────────────────────────────────────
// cpu_affinity_policy.cpp
//
// Implementation of the audio-thread CPU cluster policy.
//
// Rationale
// ─────────
// Naive "pick the top half of cores" logic is actively harmful on contemporary
// mobile silicon:
//
//   • Snapdragon 8 Gen 3    → 1 × Cortex-X4 (Prime) + 5 × A720 + 2 × A520
//   • Dimensity 9300 / 9400 → 1 × X4/X5 Prime + 3 × X4 sub-prime + 4 × A720
//   • Tensor G3             → 1 × X3 + 4 × A715 + 4 × A510
//   • SD 8 Elite Gen 5 /    → All-big; even the "lowest" tier runs ≥ 2 GHz
//     Dimensity 9500
//
// Binding the audio thread to the Prime core forces the DVFS governor to hold
// the X-series core at its peak OPP for every wakeup — a large battery and
// thermal regression for a workload that needs only a few hundred MHz of
// headroom.  Equally, pinning a DSD / 384 kHz decode to a 1.5 GHz A55 on an
// older SoC risks underruns.
//
// Strategy
// ────────
//   1. Enumerate distinct `cpuinfo_max_freq` tiers (ascending).
//   2. Tiers == 1 → homogeneous SoC; no pinning (kernel scheduler is fine).
//   3. LIGHT load → ALWAYS pin to the lowest tier.
//   4. HEAVY load → lowest tier if it clocks ≥ 1.8 GHz (modern all-big SoC);
//      otherwise the big cluster on 2-tier SoCs, or the middle tier on
//      ≥ 3-tier SoCs.
//   5. Tiers ≥ 3  → NEVER pin to the highest (Prime) tier.
// ─────────────────────────────────────────────────────────────────────────────

#include "cpu_affinity_policy.h"

#include <android/log.h>
#include <sched.h>
#include <sys/resource.h>   // setpriority / PRIO_PROCESS
#include <unistd.h>
#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <vector>

#define AFFINITY_LOG_TAG "AudiophileNative"
#define AFLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, AFFINITY_LOG_TAG, __VA_ARGS__)
#define AFLOGW(...) __android_log_print(ANDROID_LOG_WARN,  AFFINITY_LOG_TAG, __VA_ARGS__)

namespace {

struct CpuPerformanceCandidate {
    int  cpu_id       = 0;
    long max_freq_khz = 0;
};

// Threshold at which the "lowest" tier is trusted to carry HEAVY loads.
// Modern all-big SoCs ship "efficiency" cores at ~2.0 GHz; anything below this
// is a classic A510 / A520 / A55 LITTLE core that will choke on DSD decimation
// or 192 kHz+ PCM.
constexpr long kHeavyLoadMinLittleFreqKhz = 1'800'000;

bool read_long_from_file(const char *path, long *value)
{
    if (path == nullptr || value == nullptr) return false;
    FILE *file = std::fopen(path, "r");
    if (file == nullptr) return false;

    char buffer[32] = {0};
    const char *read_result = std::fgets(buffer, sizeof(buffer), file);
    std::fclose(file);

    if (read_result == nullptr) return false;

    char *end_ptr = nullptr;
    errno = 0;
    const long parsed = std::strtol(buffer, &end_ptr, 10);
    if (errno != 0 || end_ptr == buffer || parsed <= 0) return false;

    *value = parsed;
    return true;
}

} // namespace

bool bind_current_thread_for_decode_load(DecodeLoad load) noexcept
{
    const long cpu_count = sysconf(_SC_NPROCESSORS_ONLN);
    if (cpu_count <= 1) {
        return false;
    }

    std::vector<CpuPerformanceCandidate> candidates;
    candidates.reserve(static_cast<size_t>(std::min<long>(cpu_count, CPU_SETSIZE)));
    bool has_freq_data = false;

    for (int cpu = 0; cpu < cpu_count && cpu < CPU_SETSIZE; ++cpu) {
        char freq_path[128] = {0};
        std::snprintf(
            freq_path,
            sizeof(freq_path),
            "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq",
            cpu);

        long max_freq_khz = 0;
        if (read_long_from_file(freq_path, &max_freq_khz)) {
            has_freq_data = true;
        }

        candidates.push_back(CpuPerformanceCandidate{
            .cpu_id = cpu,
            .max_freq_khz = max_freq_khz,
        });
    }

    if (!has_freq_data || candidates.empty()) {
        AFLOGW("Affinity hint skipped: cpuinfo_max_freq unavailable on this device");
        return false;
    }

    // Build the ascending list of distinct max-frequency tiers.  Cores whose
    // freq read failed (max_freq_khz == 0) are excluded from tier analysis so
    // they cannot collapse Prime + LITTLE into the same bucket.
    std::vector<long> tiers;
    tiers.reserve(candidates.size());
    for (const auto &c : candidates) {
        if (c.max_freq_khz > 0) {
            tiers.push_back(c.max_freq_khz);
        }
    }
    std::sort(tiers.begin(), tiers.end());
    tiers.erase(std::unique(tiers.begin(), tiers.end()), tiers.end());

    if (tiers.size() <= 1) {
        AFLOGD(
            "Affinity hint skipped: homogeneous CPU (%zu tier%s detected)",
            tiers.size(),
            tiers.size() == 1 ? "" : "s");
        return false;
    }

    const long lowest_freq_khz  = tiers.front();
    const long highest_freq_khz = tiers.back();

    long target_freq_khz = 0;
    const char *tier_label = "";

    if (load == DecodeLoad::LIGHT) {
        target_freq_khz = lowest_freq_khz;
        tier_label = "little (light load)";
    } else if (lowest_freq_khz >= kHeavyLoadMinLittleFreqKhz) {
        // Modern all-big SoC: even the "efficiency" cluster handles 192/384 kHz
        // and DSD decimation; stay there for the battery win.
        target_freq_khz = lowest_freq_khz;
        tier_label = "little (heavy load, modern all-big)";
    } else if (tiers.size() == 2) {
        // Dual-cluster big.LITTLE with a weak LITTLE: escalate to the big
        // cluster.  With only two tiers there is no Prime to avoid.
        target_freq_khz = highest_freq_khz;
        tier_label = "big (heavy load, dual-cluster)";
    } else {
        // Tri-cluster (or finer) + weak LITTLE: pick the median of the middle
        // tiers, biased to the upper-middle, without ever touching Prime.
        const size_t middle_count  = tiers.size() - 2;
        const size_t middle_start  = 1;
        const size_t median_offset = middle_count / 2;
        target_freq_khz = tiers[middle_start + median_offset];
        tier_label = "middle (heavy load, tri-cluster+)";
    }

    // Safety net: on ≥ 3-tier SoCs never land on the Prime tier regardless of
    // how the branches above computed the target.
    if (tiers.size() >= 3 && target_freq_khz == highest_freq_khz) {
        const size_t safe_count  = tiers.size() - 1;      // exclude Prime only
        const size_t safe_median = (safe_count - 1) / 2;  // lower-biased median
        target_freq_khz = tiers[safe_median];
        tier_label = "non-prime fallback";
    }

    cpu_set_t affinity_mask;
    CPU_ZERO(&affinity_mask);
    size_t selected_count = 0;
    for (const auto &c : candidates) {
        if (c.max_freq_khz == target_freq_khz) {
            CPU_SET(c.cpu_id, &affinity_mask);
            ++selected_count;
        }
    }

    if (selected_count == 0) {
        AFLOGW("Affinity hint skipped: no cores matched target tier %ldkHz", target_freq_khz);
        return false;
    }

    if (sched_setaffinity(0, sizeof(affinity_mask), &affinity_mask) != 0) {
        AFLOGW(
            "sched_setaffinity failed errno=%d; continuing without CPU pinning",
            errno);
        return false;
    }

    AFLOGD(
        "Applied audio-thread affinity to %zu %s core(s) @ %ldkHz "
        "(tiers=%zu, prime=%ldkHz, little=%ldkHz, load=%s)",
        selected_count,
        tier_label,
        target_freq_khz,
        tiers.size(),
        tiers.back(),
        tiers.front(),
        load == DecodeLoad::HEAVY ? "HEAVY" : "LIGHT");
    return true;
}

void configure_current_thread_priority() noexcept
{
    if (setpriority(PRIO_PROCESS, 0, -16) != 0) {
        AFLOGW("setpriority(PRIO_PROCESS, 0, -16) failed errno=%d", errno);
    }
}
