#pragma once
#include "audio.hpp"
#include <array>
namespace audiohub {
// LAN and Bluetooth acquire the same two slots, including identity arbitration.
class MobileInputs {
    std::mutex mutex_;
    bool used_[2]{};
    std::string identities_[2];
public:
    std::array<std::shared_ptr<LiveInput>,2> audio;
    explicit MobileInputs(std::array<std::shared_ptr<LiveInput>,2> value):audio(std::move(value)){}
    int acquire(){std::lock_guard<std::mutex> lock(mutex_);for(int i=0;i<2;++i)if(!used_[i]){used_[i]=true;return i;}return -1;}
    void identify(int slot,const std::string& id){std::lock_guard<std::mutex> lock(mutex_);if(used_[1-slot] && identities_[1-slot]==id)throw std::runtime_error("Device already connected on LAN or Bluetooth");identities_[slot]=id;}
    void release(int slot){std::lock_guard<std::mutex> lock(mutex_);audio[slot]->clear();identities_[slot].clear();used_[slot]=false;}
};
}
