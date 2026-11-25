#pragma once
#include "../core/audio.hpp"
#include <thread>

namespace audiohub {
struct Device { std::wstring id, name; unsigned mix_rate = 0; bool capture=false; };
struct OutputStatus {
    std::wstring name, state;
    uint64_t underflows = 0, overruns = 0;
    float peak=0;
    uint64_t device_gaps=0,event_timeouts=0;
};
class OutputSession;
class OutputManager {
    MixEngine& mixer_;
    BroadcastBuffer buffer_;
    std::vector<std::unique_ptr<OutputSession>> outputs_;
    std::atomic<bool> running_{false};
    std::thread producer_;
public:
    explicit OutputManager(MixEngine& mixer);
    ~OutputManager();
    static std::vector<Device> enumerate();
    static std::vector<Device> a2dp_sources();
    static std::wstring default_id();
    void start(const std::vector<Device>& devices);
    void stop();
    void set_gain(size_t output, float gain);
    std::vector<OutputStatus> status();
};
}
