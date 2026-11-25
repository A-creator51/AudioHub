#pragma once
#include "output.hpp"
namespace audiohub {
class LoopbackInput {
    std::shared_ptr<LiveInput> input_;
    std::atomic<bool> running_{false};
    std::thread worker_;
    std::mutex mutex_;
    std::wstring state_=L"电脑输入未开启";
    std::atomic<uint64_t> frames_{0};
    std::atomic<float> peak_{0};
    void run(Device device,bool mute_source);
public:
    explicit LoopbackInput(std::shared_ptr<LiveInput> input):input_(std::move(input)) {}
    ~LoopbackInput() { stop(); }
    // The UI must reject using this endpoint as an AudioHub output: feedback hazard.
    void start(const Device& device,bool mute_source=false) { stop(); input_->clear(); frames_=0;peak_=0;running_=true; worker_=std::thread([this,device,mute_source]{run(device,mute_source);}); }
    void stop() { running_=false; if(worker_.joinable()) worker_.join(); input_->clear(); }
    std::wstring status() { std::lock_guard<std::mutex> lock(mutex_); return state_; }
    uint64_t frames() const{return frames_;}
    float peak() const{return peak_;}
    bool running() const{return running_;}
};
}
