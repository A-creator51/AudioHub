#define NOMINMAX
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <endpointvolume.h>
#include <cstdio>
#include <chrono>
#include "capture.hpp"
#include "log.hpp"
#include "settings.hpp"
#include "../core/capture_diagnostics.hpp"
#include "capture_logging.hpp"

namespace audiohub {
namespace {
void check_capture(HRESULT hr,const char* operation) {
    if(FAILED(hr)) { char message[160]; std::snprintf(message,sizeof(message),"%s: 0x%08lX",operation,static_cast<unsigned long>(hr)); throw std::runtime_error(message); }
}
template<class T> struct CaptureCom { T* p=nullptr; ~CaptureCom(){if(p)p->Release();} T** out(){return &p;} T* operator->(){return p;} };
}
void LoopbackInput::run(Device device,bool mute_source) {
    struct Finished {std::atomic<bool>& flag;~Finished(){flag=false;}} finished{running_};
    bool com=false;
    try {
        AudioPriority priority;
        check_capture(CoInitializeEx(nullptr,COINIT_MULTITHREADED),"Capture COM"); com=true;
        // Destruction of all COM objects precedes CoUninitialize.
        {
            CaptureCom<IMMDeviceEnumerator> enumerator;
            check_capture(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),reinterpret_cast<void**>(enumerator.out())),"Capture enumerator");
            CaptureCom<IMMDevice> endpoint; check_capture(enumerator->GetDevice(device.id.c_str(),endpoint.out()),"Capture endpoint");
            // Explicit opt-in only. Endpoint mute affects every app on this device.
            // Whether WASAPI loopback survives mute is hardware/driver dependent.
            CaptureCom<IAudioEndpointVolume> volume;
            struct Restore {
                IAudioEndpointVolume* volume=nullptr;BOOL original=FALSE;bool changed=false;
                ~Restore(){if(changed){BOOL now=FALSE;HRESULT hr=volume->GetMute(&now);if(SUCCEEDED(hr)&&now)hr=volume->SetMute(original,nullptr);
                    log(FAILED(hr)?LogLevel::Error:LogLevel::Info,FAILED(hr)?L"SOURCE MUTE restore failed; unmute source manually in Windows":L"SOURCE MUTE original mute restored (no volume level changed)");}}
            } restore;
            if(mute_source&&device.capture)throw std::invalid_argument("Source mute experiment is only supported for playback loopback");
            if(mute_source){
                check_capture(endpoint->Activate(__uuidof(IAudioEndpointVolume),CLSCTX_ALL,nullptr,reinterpret_cast<void**>(volume.out())),"Source mute interface");
                restore.volume=volume.p;check_capture(volume->GetMute(&restore.original),"Read source mute");
                if(!restore.original){check_capture(volume->SetMute(TRUE,nullptr),"Mute source endpoint");restore.changed=true;}
                log(LogLevel::Warn,L"SOURCE MUTE experiment enabled for "+device.name+L"; all apps on this endpoint are affected; capture may become silent");
            }
            CaptureCom<IAudioClient> client;
            check_capture(endpoint->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,reinterpret_cast<void**>(client.out())),"Capture activate");
            WAVEFORMATEX format{}; format.wFormatTag=WAVE_FORMAT_IEEE_FLOAT; format.nChannels=2;
            format.nSamplesPerSec=48000; format.wBitsPerSample=32; format.nBlockAlign=8; format.nAvgBytesPerSec=384000;
            check_capture(client->Initialize(AUDCLNT_SHAREMODE_SHARED,(device.capture?0:AUDCLNT_STREAMFLAGS_LOOPBACK)|0x80000000u|0x08000000u,1000000,0,&format,nullptr),"Audio source initialize");
            CaptureCom<IAudioCaptureClient> capture;
            check_capture(client->GetService(__uuidof(IAudioCaptureClient),reinterpret_cast<void**>(capture.out())),"Capture interface");
            check_capture(client->Start(),"Capture start");
            struct Stop { IAudioClient* p; ~Stop(){p->Stop();} } stop{client.p};
            { std::lock_guard<std::mutex> lock(mutex_); state_=(device.capture?L"蓝牙采集候选: ":L"播放设备回采: ")+device.name+(mute_source?L"（源端点静音试验）":L"（未抑制系统原声）"); }
            log(LogLevel::Info,L"LOOPBACK started "+device.name+(device.capture?L" mode=capture-candidate; identity/isolation NOT verified":L" mode=render-loopback; all endpoint audio"));
            auto sample_at=std::chrono::steady_clock::now(),log_at=sample_at;float interval_peak=0;uint64_t discontinuities=0;
            auto last_packet=sample_at;uint64_t max_gap_us=0,silent_frames=0,total_frames=0,timestamp_errors=0;
            CaptureDiagnostics diagnostics;unsigned detail_count=0,suppressed=0;
            auto segment_log=[&](SilenceSegment segment,const wchar_t* end){log_silence_segment(segment,end,detail_count,suppressed);};
            while(running_) {
                UINT32 packet=0; check_capture(capture->GetNextPacketSize(&packet),"Capture health");
                while(packet && running_) {
                    BYTE* data=nullptr; UINT32 count=0; DWORD flags=0;
                    check_capture(capture->GetBuffer(&data,&count,&flags,nullptr,nullptr),"Capture read");
                    auto arrived=std::chrono::steady_clock::now();max_gap_us=std::max(max_gap_us,uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(arrived-last_packet).count()));last_packet=arrived;
                    total_frames+=count;if(flags&AUDCLNT_BUFFERFLAGS_SILENT)silent_frames+=count;
                    if(flags&AUDCLNT_BUFFERFLAGS_TIMESTAMP_ERROR)++timestamp_errors;
                    auto segment=diagnostics.packet(count,(flags&AUDCLNT_BUFFERFLAGS_SILENT)!=0);if(segment.ended)segment_log(segment,L"audio-resumed");
                    if(flags&AUDCLNT_BUFFERFLAGS_DATA_DISCONTINUITY)++discontinuities;
                    // All normal packets are interleaved float32 stereo after Windows SRC.
                    if(flags&AUDCLNT_BUFFERFLAGS_SILENT) {
                        Frame silence[block_frames]{};
                        for(UINT32 i=0;i<count;i+=block_frames) input_->write(silence,std::min<unsigned>(block_frames,count-i));
                    } else {
                        const auto* samples=reinterpret_cast<const Frame*>(data);
                        for(UINT32 i=0;i<count;++i)interval_peak=std::max(interval_peak,std::max(std::abs(samples[i].left),std::abs(samples[i].right)));
                        input_->write(samples,count);
                    }
                    frames_+=count;
                    check_capture(capture->ReleaseBuffer(count),"Capture release");
                    check_capture(capture->GetNextPacketSize(&packet),"Capture next");
                }
                auto now=std::chrono::steady_clock::now();
                if(now-sample_at>=std::chrono::milliseconds(500)){peak_=interval_peak;interval_peak=0;sample_at=now;}
                if(now-log_at>=std::chrono::seconds(5)){
                    auto stats=input_->stats();log(LogLevel::Info,L"LOOPBACK PCM frames="+std::to_wstring(frames_.load())+L" peak="+std::to_wstring(peak_.load())+L" discontinuities="+std::to_wstring(discontinuities)+L" inputGaps="+std::to_wstring(stats.gaps)+L" inputDrops="+std::to_wstring(stats.drops)+L" queued="+std::to_wstring(stats.queued)+(mute_source?L" sourceMute=experiment":L" sourceMute=unchanged"));log_at=now;
                    auto idle=uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(now-last_packet).count());
                    log(LogLevel::Info,L"CAPTURE-TIMING windowFrames="+std::to_wstring(total_frames)+L" packets="+std::to_wstring(diagnostics.packets)+L" flaggedSilentFrames="+std::to_wstring(silent_frames)+L" silentPercent="+std::to_wstring(total_frames?100.0*silent_frames/total_frames:0)+L" silenceStarts="+std::to_wstring(diagnostics.segments)+L" longestSilentRunMs="+std::to_wstring(diagnostics.longest/48)+L" pendingSilentRunMs="+std::to_wstring(diagnostics.pending().frames/48)+L" maxPacketGapMs="+std::to_wstring(std::max(max_gap_us,idle)/1000)+L" timestampErrors="+std::to_wstring(timestamp_errors)+L" detailSuppressed="+std::to_wstring(suppressed)+L"; endpoint delivery, NOT Bluetooth radio loss");
                    diagnostics.window();detail_count=suppressed=0;
                    max_gap_us=0;silent_frames=0;total_frames=0;timestamp_errors=0;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
            }
            segment_log(diagnostics.pending(),L"capture-stopped");
        }
        { std::lock_guard<std::mutex> lock(mutex_); state_=L"电脑输入已停止"; }
    } catch(const std::exception& error) {
        std::string value=error.what(); std::lock_guard<std::mutex> lock(mutex_); state_=L"电脑回采失败: "+std::wstring(value.begin(),value.end());
        log(LogLevel::Error,L"LOOPBACK "+log_wide(error.what()));
    }
    if(com) CoUninitialize();
    peak_=0;
}
}
