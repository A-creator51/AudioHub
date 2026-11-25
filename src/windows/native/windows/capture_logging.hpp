#pragma once
#include "settings.hpp"
#include "log.hpp"
#include "../core/capture_diagnostics.hpp"
namespace audiohub {
inline void log_silence_segment(SilenceSegment segment,const wchar_t* end,unsigned& emitted,unsigned& suppressed){
    if(!AudioSettings::instance().capture_detail.load()||!segment.frames)return;
    if(emitted++>=20){++suppressed;return;}
    log(LogLevel::Info,L"CAPTURE-SILENCE startAudioMs="+std::to_wstring(segment.start/48)+L" durationMs="+std::to_wstring(segment.frames/48)+L" frames="+std::to_wstring(segment.frames)+L" end="+end+L"; Windows SILENT flag, NOT proof of Bluetooth loss");
}
}
