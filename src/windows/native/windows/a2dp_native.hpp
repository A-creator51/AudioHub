#pragma once
#include <string>
#include <vector>
#include <atomic>
#include <thread>
#include <mutex>
namespace audiohub {
struct A2dpDevice { std::wstring id,name; };
// Owns only this application's WinRT connection. Never changes audio routing.
class NativeA2dp {
    std::atomic<bool> cancel_{false};
    std::atomic<bool> connected_{false};
    std::thread worker_;
    std::mutex mutex_;
    std::vector<A2dpDevice> devices_;
    std::wstring status_=L"A2DP 未连接";
    void state(std::wstring text);
    void run(bool list,A2dpDevice device);
public:
    ~NativeA2dp(){stop();}
    void refresh();
    void connect(A2dpDevice device);
    void stop();
    std::vector<A2dpDevice> devices();
    std::wstring status();
    bool connected() const{return connected_;}
};
}
