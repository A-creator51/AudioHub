#pragma once
#include <windows.h>
#include <deque>
#include <mutex>
#include <regex>
#include <string>
namespace audiohub {
enum class LogLevel { Debug, Info, Warn, Error, Off };
class AppLog {
    // Retain bounded history independently of display threshold. Off disables
    // collection, while preserving the last visible filter and existing text.
    struct Entry { LogLevel level; std::wstring text; };
    std::mutex mutex_; LogLevel level_=LogLevel::Info; bool enabled_=true; std::deque<Entry> lines_;
public:
    static AppLog& instance() { static auto* value=new AppLog; return *value; }
    void level(LogLevel value) {std::lock_guard<std::mutex> lock(mutex_);enabled_=value!=LogLevel::Off;if(enabled_)level_=value;}
    void write(LogLevel level,std::wstring value) {
        std::lock_guard<std::mutex> lock(mutex_); if(level==LogLevel::Off || !enabled_) return;
        static const std::wregex code(L"AH(B)?[12]\\|[^\\s]+",std::regex_constants::icase),secret(L"[0-9a-f]{32,}",std::regex_constants::icase);
        value=std::regex_replace(std::regex_replace(value,code,L"<REDACTED-CODE>"),secret,L"<REDACTED>");
        for(auto& c:value) if(c==L'\r'||c==L'\n')c=L' ';
        if(value.size()>1500)value.resize(1500);
        SYSTEMTIME now{};GetLocalTime(&now);wchar_t stamp[96];const wchar_t* levels[]={L"DEBUG",L"INFO",L"WARN",L"ERROR",L"OFF"};
        swprintf(stamp,96,L"%04u-%02u-%02u %02u:%02u:%02u [%ls] ",now.wYear,now.wMonth,now.wDay,now.wHour,now.wMinute,now.wSecond,levels[static_cast<int>(level)]);
        if(lines_.size()>=500)lines_.pop_front();
        lines_.push_back({level,std::wstring(stamp)+value});
    }
    std::wstring snapshot(){std::lock_guard<std::mutex> lock(mutex_);std::wstring out;for(auto& line:lines_)if(line.level>=level_)out+=line.text+L"\r\n";return out;}
    void clear(){std::lock_guard<std::mutex> lock(mutex_);lines_.clear();}
};
inline void log(LogLevel level,const std::wstring& text){AppLog::instance().write(level,text);}
inline std::wstring log_wide(const char* value){int n=MultiByteToWideChar(CP_UTF8,0,value,-1,nullptr,0);if(n<=0)return L"Unknown error";std::wstring out(n,L'\0');MultiByteToWideChar(CP_UTF8,0,value,-1,&out[0],n);out.resize(n-1);return out;}
}
