#pragma once
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace audiohub {
constexpr unsigned sample_rate = 48000;
constexpr unsigned block_frames = 480;
struct Frame { float left = 0, right = 0; };
struct Clip { std::wstring name; std::vector<Frame> frames; };
// Disk parsing is outside all audio callbacks. Only 48 kHz PCM16 WAV in v0.1.
std::shared_ptr<const Clip> load_wave(const std::wstring& path);

// A bounded live-input module. Network/capture writers never block on a device.
// Underflow re-primes 60 ms; overflow drops old audio rather than accumulating latency.
class LiveInput {
    std::mutex mutex_;
    std::vector<Frame> data_;
    size_t head_ = 0, size_ = 0;
    bool primed_ = false;
    uint64_t drops_ = 0, gaps_ = 0;
    size_t prebuffer_ = block_frames*6;
public:
    explicit LiveInput(size_t capacity = sample_rate / 4) : data_(capacity) {
        if (capacity < block_frames * 6) throw std::invalid_argument("Live capacity below prebuffer");
    }
    void clear() { std::lock_guard<std::mutex> lock(mutex_); head_=size_=0; primed_=false; }
    void prebuffer(unsigned ms){std::lock_guard<std::mutex> lock(mutex_);prebuffer_=std::min(data_.size(),size_t(std::max(60u,std::min(160u,ms)))*sample_rate/1000);primed_=false;}
    void write(const Frame* frames, size_t count) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (count > data_.size()) { frames += count-data_.size(); count=data_.size(); }
        if (size_+count > data_.size()) {
            size_t discard=size_+count-data_.size(); head_=(head_+discard)%data_.size(); size_-=discard; ++drops_;
        }
        for(size_t i=0;i<count;++i) data_[(head_+size_+i)%data_.size()]=frames[i];
        size_+=count;
    }
    void read(Frame* frames, size_t count) {
        std::lock_guard<std::mutex> lock(mutex_);
        std::fill(frames,frames+count,Frame{});
        if (!primed_) { if(size_<prebuffer_) return; primed_=true; }
        size_t available=std::min(size_,count);
        for(size_t i=0;i<available;++i) frames[i]=data_[(head_+i)%data_.size()];
        head_=(head_+available)%data_.size(); size_-=available;
        if(available<count) { primed_=false; ++gaps_; }
    }
    struct Stats { size_t queued; uint64_t drops, gaps; };
    Stats stats() { std::lock_guard<std::mutex> lock(mutex_); return {size_,drops_,gaps_}; }
};

class MixEngine {
    struct Input {
        unsigned id=0;
        std::shared_ptr<const Clip> clip;
        size_t position = 0;
        float gain = .5f;
        float current_gain = 0;
        bool muted = false;
        std::shared_ptr<LiveInput> live;
        float peak=0;
    };
    std::mutex mutex_;
    std::vector<Input> inputs_;
    unsigned next_id_ = 1;
public:
    unsigned add(std::shared_ptr<const Clip> clip) {
        if (!clip || clip->frames.empty()) throw std::invalid_argument("Empty audio clip");
        std::lock_guard<std::mutex> lock(mutex_);
        if (inputs_.size() >= 3) throw std::runtime_error("v0.1 supports at most 3 WAV inputs");
        unsigned id = next_id_++;
        Input input; input.id=id; input.clip=std::move(clip); inputs_.push_back(std::move(input));
        return id;
    }
    unsigned add_live(std::shared_ptr<LiveInput> live) {
        if(!live) throw std::invalid_argument("Missing live input");
        std::lock_guard<std::mutex> lock(mutex_);
        if(inputs_.size()>=3) throw std::runtime_error("At most 3 total inputs");
        unsigned id=next_id_++;
        Input input; input.id=id; input.live=std::move(live); inputs_.push_back(std::move(input));
        return id;
    }
    void control(unsigned id, float gain, bool muted) {
        if (!std::isfinite(gain) || gain < 0 || gain > 8) throw std::invalid_argument("Gain must be 0..8");
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& input : inputs_) if (input.id == id) { input.gain = gain; input.muted = muted; return; }
        throw std::invalid_argument("Unknown input");
    }
    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& input : inputs_) { input.position = 0; input.current_gain = 0; input.peak=0; if(input.live) input.live->clear(); }
    }
    float peak(unsigned id){std::lock_guard<std::mutex> lock(mutex_);for(auto& i:inputs_)if(i.id==id)return i.peak;return 0;}
    void clear() { std::lock_guard<std::mutex> lock(mutex_); inputs_.clear(); }
    // Inputs play once; ended inputs contribute silence. Render is single-consumer.
    void render(Frame* output, size_t count) {
        std::fill(output, output + count, Frame{});
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& input : inputs_) {
            input.peak*=.85f;
            float target = input.muted ? 0 : input.gain;
            // Stack-sized chunks keep render allocation-free, even for larger callers.
            Frame live_frames[block_frames];
            for (size_t i = 0; i < count; ++i) {
                if(input.live && i%block_frames==0) input.live->read(live_frames,std::min<size_t>(block_frames,count-i));
                float delta = std::max(-1.f/240, std::min(1.f/240, target-input.current_gain));
                input.current_gain += delta;
                if (input.live || input.position < input.clip->frames.size()) {
                    const auto& f = input.live ? live_frames[i%block_frames] : input.clip->frames[input.position++];
                    input.peak=std::max(input.peak,std::max(std::abs(f.left*input.current_gain),std::abs(f.right*input.current_gain)));
                    output[i].left += f.left * input.current_gain;
                    output[i].right += f.right * input.current_gain;
                }
            }
        }
        for (size_t i = 0; i < count; ++i) {
            output[i].left = std::max(-1.f, std::min(1.f, output[i].left));
            output[i].right = std::max(-1.f, std::min(1.f, output[i].right));
        }
    }
};

// One writer, independent cursors for every output. Never wait for a slow reader.
class BroadcastBuffer {
    std::mutex mutex_;
    std::vector<Frame> data_;
    uint64_t written_ = 0;
public:
    explicit BroadcastBuffer(size_t capacity = sample_rate / 2) : data_(capacity) {
        if (!capacity) throw std::invalid_argument("Zero buffer capacity");
    }
    void reset() { std::lock_guard<std::mutex> lock(mutex_); written_ = 0; }
    uint64_t tail() { std::lock_guard<std::mutex> lock(mutex_); return written_; }
    void write(const Frame* data, size_t count) {
        std::lock_guard<std::mutex> lock(mutex_);
        for (size_t i = 0; i < count; ++i) data_[written_++ % data_.size()] = data[i];
    }
    // Missing data => silence. Overrun => discard stale backlog, record event.
    size_t read(uint64_t& cursor, Frame* output, size_t count, uint64_t& overruns) {
        std::lock_guard<std::mutex> lock(mutex_);
        std::fill(output, output + count, Frame{});
        uint64_t oldest = written_ > data_.size() ? written_ - data_.size() : 0;
        if (cursor < oldest) { cursor = std::max(oldest, written_ > block_frames*4 ? written_-block_frames*4 : oldest); ++overruns; }
        if (cursor > written_) cursor = written_;
        size_t available = static_cast<size_t>(std::min<uint64_t>(count, written_ - cursor));
        for (size_t i = 0; i < available; ++i) output[i] = data_[cursor++ % data_.size()];
        return available;
    }
};
}
