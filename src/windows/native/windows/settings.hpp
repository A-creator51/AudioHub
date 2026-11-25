#pragma once
#include <windows.h>
#include <string>
#include <atomic>
#include <algorithm>
#include <stdexcept>
#include <cstring>
namespace audiohub {
// Settings are snapshotted at session start; changing UI never races a worker.
struct AudioSettings {
    std::atomic<int> output_ms{160}, reserve_ms{100}, input_ms{100};
    std::atomic<bool> mmcss{true};
    std::atomic<int> bluetooth_max{400};
    std::atomic<int> lan_max{200};
    std::atomic<bool> capture_detail{false};
    static AudioSettings& instance(){static AudioSettings s;return s;}
    static std::wstring path(){
        wchar_t root[32768]{};DWORD n=GetEnvironmentVariableW(L"LOCALAPPDATA",root,32768);
        if(!n||n>=32768)throw std::runtime_error("LOCALAPPDATA unavailable");
        return std::wstring(root)+L"\\AudioHub\\settings.ini";
    }
    void load(){auto p=path();capture_detail=GetPrivateProfileIntW(L"Audio",L"CaptureDetail",0,p.c_str())!=0;lan_max=GetPrivateProfileIntW(L"Audio",L"LanMaxPercent",200,p.c_str());bluetooth_max=GetPrivateProfileIntW(L"Audio",L"BluetoothMaxPercent",400,p.c_str());output_ms=GetPrivateProfileIntW(L"Audio",L"OutputMs",160,p.c_str());reserve_ms=GetPrivateProfileIntW(L"Audio",L"ReserveMs",100,p.c_str());input_ms=GetPrivateProfileIntW(L"Audio",L"InputMs",100,p.c_str());mmcss=GetPrivateProfileIntW(L"Audio",L"Mmcss",1,p.c_str())!=0;sanitize();}
    void sanitize(){lan_max=std::max(100,std::min(800,lan_max.load()));bluetooth_max=std::max(100,std::min(800,bluetooth_max.load()));output_ms=std::max(100,std::min(400,output_ms.load()));reserve_ms=std::max(40,std::min(200,reserve_ms.load()));input_ms=std::max(60,std::min(160,input_ms.load()));}
    void save(){sanitize();auto p=path();auto dir=p.substr(0,p.find_last_of(L'\\'));if(!CreateDirectoryW(dir.c_str(),nullptr)&&GetLastError()!=ERROR_ALREADY_EXISTS)throw std::runtime_error("Cannot create settings directory");
        auto write=[&](const wchar_t* key,int value){if(!WritePrivateProfileStringW(L"Audio",key,std::to_wstring(value).c_str(),p.c_str()))throw std::runtime_error("Cannot save settings");};
        write(L"OutputMs",output_ms);write(L"ReserveMs",reserve_ms);write(L"InputMs",input_ms);write(L"Mmcss",mmcss?1:0);
        write(L"BluetoothMaxPercent",bluetooth_max);
        write(L"LanMaxPercent",lan_max);
        write(L"CaptureDetail",capture_detail?1:0);
    }
};
// Native MMCSS, no helper process and no global timer-resolution changes.
class AudioPriority {
    HMODULE module_=nullptr;HANDLE task_=nullptr;
    using Revert=BOOL(WINAPI*)(HANDLE);Revert revert_=nullptr;
public:
    AudioPriority(){if(!AudioSettings::instance().mmcss)return;module_=LoadLibraryExW(L"avrt.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);if(!module_)return;
        using Set=HANDLE(WINAPI*)(LPCWSTR,LPDWORD);Set set=nullptr;
        auto set_proc=GetProcAddress(module_,"AvSetMmThreadCharacteristicsW"),revert_proc=GetProcAddress(module_,"AvRevertMmThreadCharacteristics");
        static_assert(sizeof(set)==sizeof(set_proc),"Windows function pointer size");std::memcpy(&set,&set_proc,sizeof(set));std::memcpy(&revert_,&revert_proc,sizeof(revert_));
        DWORD index=0;if(set&&revert_)task_=set(L"Audio",&index);
    }
    ~AudioPriority(){if(task_&&revert_)revert_(task_);if(module_)FreeLibrary(module_);}
};
}
