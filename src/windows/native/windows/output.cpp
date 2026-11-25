#define NOMINMAX
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <ks.h>
#include <ksmedia.h>
#include <chrono>
#include <cstdio>
#include <cwctype>
#include "output.hpp"
#include "log.hpp"
#include "settings.hpp"
#include "../core/output_queue.hpp"

namespace audiohub {
namespace {
const PROPERTYKEY friendly_name_key = {{0xa45c254e,0xdf1c,0x4efd,{0x80,0x20,0x67,0xd1,0x46,0xa8,0x50,0xe0}},14};
void check(HRESULT hr, const char* operation) {
    if (FAILED(hr)) {
        char text[160]; std::snprintf(text, sizeof(text), "%s: HRESULT 0x%08lX", operation, static_cast<unsigned long>(hr));
        throw std::runtime_error(text);
    }
}
template<class T> struct Com {
    T* ptr = nullptr;
    ~Com() { if (ptr) ptr->Release(); }
    T* operator->() const { return ptr; }
    T** out() { return &ptr; }
};
struct Apartment {
    Apartment() { check(CoInitializeEx(nullptr, COINIT_MULTITHREADED), "COM init"); }
    ~Apartment() { CoUninitialize(); }
};
struct Event {
    HANDLE handle = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    ~Event() { if (handle) CloseHandle(handle); }
};
std::wstring widen(const char* text) {
    std::wstring result;
    while (*text) result += wchar_t(static_cast<unsigned char>(*text++));
    return result;
}
}

class OutputSession {
    Device device_;
    BroadcastBuffer& buffer_;
    std::atomic<bool> stopping_{false};
    std::atomic<float> gain_{.25f};
    std::thread thread_;
    std::mutex mutex_;
    OutputStatus status_;
    void state(const std::wstring& text) { std::lock_guard<std::mutex> lock(mutex_); status_.state = text; log(text.find(L"Failed")==0?LogLevel::Error:LogLevel::Info,L"OUTPUT "+device_.name+L": "+text); }
    void run() {
        try {
            Apartment apartment;
            AudioPriority priority;
            Com<IMMDeviceEnumerator> enumerator;
            check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                   __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(enumerator.out())), "Enumerator");
            Com<IMMDevice> device;
            check(enumerator->GetDevice(device_.id.c_str(), device.out()), "Get output");
            Com<IAudioClient> client;
            check(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(client.out())), "Activate output");
            // Windows audio engine performs native rate/channel conversion, not a custom linear resampler.
            WAVEFORMATEX format{};
            format.wFormatTag = WAVE_FORMAT_IEEE_FLOAT; format.nChannels = 2;
            format.nSamplesPerSec = sample_rate; format.wBitsPerSample = 32;
            format.nBlockAlign = 8; format.nAvgBytesPerSec = sample_rate*8;
            constexpr DWORD auto_convert = 0x80000000u, src_quality = 0x08000000u;
            check(client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                  AUDCLNT_STREAMFLAGS_EVENTCALLBACK | auto_convert | src_quality,
                  REFERENCE_TIME(AudioSettings::instance().output_ms.load())*10000, 0, &format, nullptr), "Initialize shared output");
            Event event;
            if (!event.handle) throw std::runtime_error("Cannot create output event");
            check(client->SetEventHandle(event.handle), "Output event");
            UINT32 capacity = 0;
            check(client->GetBufferSize(&capacity), "Output capacity");
            Com<IAudioRenderClient> render;
            check(client->GetService(__uuidof(IAudioRenderClient), reinterpret_cast<void**>(render.out())), "Render interface");
            std::vector<Frame> frames(capacity);
            size_t reserve=sample_rate*AudioSettings::instance().reserve_ms.load()/1000;
            if(capacity+reserve>sample_rate*2)throw std::runtime_error("Driver buffer exceeds supported two-second queue");
            while(!stopping_&&!output_ready(buffer_.tail(),capacity,reserve))std::this_thread::sleep_for(std::chrono::milliseconds(2));
            if(stopping_)return;
            uint64_t cursor=output_cursor(buffer_.tail(),capacity,reserve),underruns=0,overruns=0;
            float current_gain = 0;
            // Prime the actual WASAPI buffer before Start. Do not start an
            // empty endpoint then fill 100+ ms from a 40 ms software backlog.
            buffer_.read(cursor,frames.data(),capacity,overruns);
            BYTE* initial=nullptr;check(render->GetBuffer(capacity,&initial),"Prime output");
            auto pcm_initial=reinterpret_cast<float*>(initial);
            for(size_t i=0;i<capacity;++i){current_gain+=std::max(-1.f/240,std::min(1.f/240,gain_.load()-current_gain));pcm_initial[2*i]=std::max(-1.f,std::min(1.f,frames[i].left*current_gain));pcm_initial[2*i+1]=std::max(-1.f,std::min(1.f,frames[i].right*current_gain));}
            check(render->ReleaseBuffer(capacity,0),"Prime submit");
            check(client->Start(), "Start output");
            struct Stop { IAudioClient* client; ~Stop() { client->Stop(); } } stop{client.ptr};
            state(L"Playing / awaiting listening confirmation");
            log(LogLevel::Info,L"OUTPUT buffer frames="+std::to_wstring(capacity)+L" reserveFrames="+std::to_wstring(reserve)+L" device="+device_.name);
            auto log_at=std::chrono::steady_clock::now();
            uint64_t device_gaps=0,event_timeouts=0;
            while (!stopping_) {
                DWORD result = WaitForSingleObject(event.handle, 100);
                if (result == WAIT_TIMEOUT) {
                    // A missed event must not prevent servicing an otherwise
                    // healthy endpoint. Padding below decides how much to fill.
                    ++event_timeouts;
                }
                if (result != WAIT_OBJECT_0&&result!=WAIT_TIMEOUT) throw std::runtime_error("Output event wait failed");
                UINT32 padding = 0; check(client->GetCurrentPadding(&padding), "Output padding");
                if(!padding)++device_gaps;
                UINT32 count = capacity-padding;
                if (!count) continue;
                size_t received = buffer_.read(cursor, frames.data(), count, overruns);
                if (received < count) ++underruns;
                BYTE* destination = nullptr;
                check(render->GetBuffer(count, &destination), "Output buffer");
                auto* pcm = reinterpret_cast<float*>(destination);
                float target = gain_;
                float peak=0;
                for (size_t i = 0; i < count; ++i) {
                    current_gain += std::max(-1.f/240, std::min(1.f/240, target-current_gain));
                    pcm[2*i] = std::max(-1.f,std::min(1.f,frames[i].left*current_gain));
                    pcm[2*i+1] = std::max(-1.f,std::min(1.f,frames[i].right*current_gain));
                    peak=std::max(peak,std::max(std::abs(pcm[2*i]),std::abs(pcm[2*i+1])));
                }
                check(render->ReleaseBuffer(count, 0), "Submit output");
                { std::lock_guard<std::mutex> lock(mutex_); status_.underflows = underruns; status_.overruns = overruns;status_.peak=peak;status_.device_gaps=device_gaps;status_.event_timeouts=event_timeouts; }
                auto now=std::chrono::steady_clock::now();if(now-log_at>=std::chrono::seconds(5)){log(underruns||overruns||device_gaps||event_timeouts?LogLevel::Warn:LogLevel::Info,L"OUTPUT health device="+device_.name+L" underflows="+std::to_wstring(underruns)+L" overruns="+std::to_wstring(overruns)+L" deviceEmpty="+std::to_wstring(device_gaps)+L" eventTimeouts="+std::to_wstring(event_timeouts)+L" peak="+std::to_wstring(peak));log_at=now;}
            }
            state(L"Stopped");
        } catch (const std::exception& error) { state(L"Failed: " + widen(error.what())); }
    }
public:
    OutputSession(Device device, BroadcastBuffer& buffer) : device_(std::move(device)), buffer_(buffer) {
        status_.name = device_.name; status_.state = L"Starting";
    }
    ~OutputSession() { stop(); }
    void start() { thread_ = std::thread([this] { run(); }); }
    void stop() { stopping_ = true; if (thread_.joinable()) thread_.join(); }
    void gain(float value) { gain_ = value; }
    OutputStatus snapshot() { std::lock_guard<std::mutex> lock(mutex_); return status_; }
};

OutputManager::OutputManager(MixEngine& mixer) : mixer_(mixer),buffer_(sample_rate*2) {}
OutputManager::~OutputManager() { stop(); }
std::wstring OutputManager::default_id() {
    Apartment apartment;Com<IMMDeviceEnumerator> enumerator;
    check(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),reinterpret_cast<void**>(enumerator.out())),"Default enumerator");
    Com<IMMDevice> device;check(enumerator->GetDefaultAudioEndpoint(eRender,eMultimedia,device.out()),"Default playback endpoint");
    LPWSTR id=nullptr;check(device->GetId(&id),"Default endpoint ID");std::wstring result=id;CoTaskMemFree(id);return result;
}
std::vector<Device> OutputManager::enumerate() {
    Apartment apartment;
    Com<IMMDeviceEnumerator> enumerator;
    check(CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                           __uuidof(IMMDeviceEnumerator), reinterpret_cast<void**>(enumerator.out())), "Enumerator");
    Com<IMMDeviceCollection> collection;
    check(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, collection.out()), "Enumerate outputs");
    UINT count = 0; check(collection->GetCount(&count), "Device count");
    std::vector<Device> devices;
    for (UINT i = 0; i < count; ++i) {
        Com<IMMDevice> device;
        check(collection->Item(i, device.out()), "Device item");
        LPWSTR id = nullptr; check(device->GetId(&id), "Device ID");
        Device entry; entry.id = id; CoTaskMemFree(id);
        Com<IPropertyStore> properties;
        check(device->OpenPropertyStore(STGM_READ, properties.out()), "Device properties");
        PROPVARIANT value; PropVariantInit(&value);
        HRESULT hr = properties->GetValue(friendly_name_key, &value);
        if (SUCCEEDED(hr) && value.vt == VT_LPWSTR) entry.name = value.pwszVal;
        PropVariantClear(&value);
        if (entry.name.empty()) entry.name = L"Unnamed output";
        Com<IAudioClient> client;
        if (SUCCEEDED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr, reinterpret_cast<void**>(client.out())))) {
            WAVEFORMATEX* format = nullptr;
            if (SUCCEEDED(client->GetMixFormat(&format))) { entry.mix_rate = format->nSamplesPerSec; CoTaskMemFree(format); }
        }
        devices.push_back(std::move(entry));
    }
    return devices;
}
void OutputManager::start(const std::vector<Device>& devices) {
    if (devices.empty() || devices.size() > 5) throw std::invalid_argument("Select 1..5 outputs");
    for(const auto& device:devices)if(device.capture)throw std::invalid_argument("Capture endpoint cannot be an output");
    for (size_t i = 0; i < devices.size(); ++i)
        for (size_t j = 0; j < i; ++j)
            if (devices[i].id == devices[j].id) throw std::invalid_argument("Duplicate output");
    stop(); buffer_.reset(); mixer_.reset();
    // Prime 40 ms of the same total mix for independent output readers.
    Frame frames[block_frames];
    for (int i = 0; i < 4; ++i) { mixer_.render(frames, block_frames); buffer_.write(frames, block_frames); }
    running_ = true;
    try {
        producer_ = std::thread([this] {
            AudioPriority priority;
            auto next = std::chrono::steady_clock::now();
            Frame block[block_frames];
            while (running_) {
                mixer_.render(block, block_frames); buffer_.write(block, block_frames);
                next += std::chrono::milliseconds(10);
                auto now = std::chrono::steady_clock::now();
                if (next < now-std::chrono::milliseconds(100)) next = now;
                std::this_thread::sleep_until(next);
            }
        });
        for (const auto& device : devices) {
            outputs_.emplace_back(new OutputSession(device, buffer_)); outputs_.back()->start();
        }
    } catch (...) { stop(); throw; }
}
std::vector<Device> OutputManager::a2dp_sources(){
    auto result=enumerate();
    Apartment apartment;Com<IMMDeviceEnumerator> enumerator;
    check(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),reinterpret_cast<void**>(enumerator.out())),"A2DP source enumerator");
    Com<IMMDeviceCollection> collection;check(enumerator->EnumAudioEndpoints(eCapture,DEVICE_STATE_ACTIVE,collection.out()),"A2DP capture enumeration");
    UINT count=0;check(collection->GetCount(&count),"A2DP capture count");
    for(UINT i=0;i<count;++i){
        Com<IMMDevice> endpoint;check(collection->Item(i,endpoint.out()),"A2DP capture item");
        Com<IPropertyStore> props;check(endpoint->OpenPropertyStore(STGM_READ,props.out()),"A2DP capture properties");
        PROPVARIANT v;PropVariantInit(&v);std::wstring name;
        if(SUCCEEDED(props->GetValue(friendly_name_key,&v))&&v.vt==VT_LPWSTR)name=v.pwszVal;
        PropVariantClear(&v);auto lower=name;std::transform(lower.begin(),lower.end(),lower.begin(),[](wchar_t c){return std::towlower(c);});
        // Explicit candidates only: never list ordinary/headset microphones.
        // A name is a candidate label, not verified phone identity or PCM ownership.
        if(lower.find(L"a2dp")==std::wstring::npos&&lower.find(L"snk")==std::wstring::npos)continue;
        LPWSTR id=nullptr;check(endpoint->GetId(&id),"A2DP capture ID");Device d;d.id=id;CoTaskMemFree(id);d.name=name;d.capture=true;
        result.push_back(std::move(d));
    }
    return result;
}
void OutputManager::stop() {
    running_ = false;
    for (auto& output : outputs_) output->stop();
    outputs_.clear();
    if (producer_.joinable()) producer_.join();
}
void OutputManager::set_gain(size_t output, float gain) {
    if (!std::isfinite(gain) || gain < 0 || gain > 8) throw std::invalid_argument("Output gain must be 0..8");
    if (output < outputs_.size()) outputs_[output]->gain(gain);
}
std::vector<OutputStatus> OutputManager::status() {
    std::vector<OutputStatus> states;
    for (auto& output : outputs_) states.push_back(output->snapshot());
    return states;
}
}
