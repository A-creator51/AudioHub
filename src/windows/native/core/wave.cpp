#include "audio.hpp"
#include <fstream>
#include <cstring>
#ifdef _WIN32
#include <windows.h>
#endif

namespace audiohub {
namespace {
uint16_t u16(const unsigned char* p) { return p[0] | (uint16_t(p[1]) << 8); }
uint32_t u32(const unsigned char* p) { return u16(p) | (uint32_t(u16(p+2)) << 16); }
}
std::shared_ptr<const Clip> load_wave(const std::wstring& path) {
    // Windows wide-character file access; bounded load, no filesystem locale conversion.
    HANDLE handle = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle == INVALID_HANDLE_VALUE) throw std::runtime_error("Cannot open WAV file");
    struct Close { HANDLE h; ~Close() { CloseHandle(h); } } close{handle};
    LARGE_INTEGER length{};
    if (!GetFileSizeEx(handle, &length) || length.QuadPart < 12 || length.QuadPart > 64*1024*1024)
        throw std::runtime_error("WAV must be between 12 bytes and 64 MiB");
    std::vector<unsigned char> bytes(static_cast<size_t>(length.QuadPart));
    DWORD read = 0;
    if (!ReadFile(handle, bytes.data(), static_cast<DWORD>(bytes.size()), &read, nullptr) || read != bytes.size())
        throw std::runtime_error("Cannot read complete WAV file");
    if (std::memcmp(bytes.data(), "RIFF", 4) || std::memcmp(bytes.data()+8, "WAVE", 4))
        throw std::runtime_error("Expected RIFF/WAVE file");
    size_t limit = size_t(u32(bytes.data()+4)) + 8;
    if (limit > bytes.size() || limit < 12) throw std::runtime_error("Truncated RIFF");
    const unsigned char *format = nullptr, *pcm = nullptr;
    size_t pcm_size = 0;
    for (size_t offset = 12; offset+8 <= limit;) {
        uint32_t size = u32(bytes.data()+offset+4);
        if (size > limit-offset-8) throw std::runtime_error("Truncated WAV chunk");
        if (!std::memcmp(bytes.data()+offset, "fmt ", 4) && size >= 16) format = bytes.data()+offset+8;
        if (!std::memcmp(bytes.data()+offset, "data", 4)) { pcm = bytes.data()+offset+8; pcm_size = size; }
        offset += 8 + size_t(size) + (size & 1);
    }
    if (!format || !pcm) throw std::runtime_error("Missing WAV format or data");
    unsigned channels = u16(format+2);
    if (u16(format) != 1 || (channels != 1 && channels != 2) || u32(format+4) != sample_rate || u16(format+14) != 16)
        throw std::runtime_error("v0.1 accepts 48 kHz PCM16 mono/stereo WAV only");
    if (u16(format+12) != channels*2 || u32(format+8) != sample_rate*channels*2 || pcm_size % (channels*2))
        throw std::runtime_error("Invalid PCM alignment");
    if (!pcm_size) throw std::runtime_error("Empty WAV data");
    auto clip = std::make_shared<Clip>();
    clip->name = path.substr(path.find_last_of(L"/\\") + 1);
    clip->frames.resize(pcm_size / (channels*2));
    for (size_t i = 0; i < clip->frames.size(); ++i) {
        float left = int16_t(u16(pcm+i*channels*2)) / 32768.f;
        float right = channels == 2 ? int16_t(u16(pcm+i*channels*2+2)) / 32768.f : left;
        clip->frames[i] = {left, right};
    }
    return clip;
}
}
