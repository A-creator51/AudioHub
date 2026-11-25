#pragma once
#include "audio.hpp"
namespace audiohub {
// Snapshot the live tail only after the producer has filled the requested
// hardware capacity + scheduling reserve. Every endpoint gets its own cursor.
inline bool output_ready(uint64_t tail,size_t capacity,size_t reserve){return tail>=capacity+reserve;}
inline uint64_t output_cursor(uint64_t tail,size_t capacity,size_t reserve){return output_ready(tail,capacity,reserve)?tail-capacity-reserve:0;}
}
