#pragma once
#include "audio.hpp"
namespace audiohub {
// Independent 10ms stereo IMA ADPCM blocks: 2*(PCM predictor LE16,index,0),
// followed by one byte per stereo frame (left low nibble, right high nibble).
// Predictor/index reset each block, no unbounded state or loss propagation.
inline void decode_bluetooth(const uint8_t* data,size_t size,Frame* frames,size_t count) {
    if(count<1 || count>block_frames || size!=8+count)throw std::runtime_error("Invalid Bluetooth ADPCM block");
    static const int steps[]={7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767};
    static const int adjust[]={-1,-1,-1,-1,2,4,6,8};
    int predictor[2],index[2];
    for(int c=0;c<2;c++){predictor[c]=int16_t(unsigned(data[c*4])|(unsigned(data[c*4+1])<<8));index[c]=data[c*4+2];if(index[c]>88 || data[c*4+3])throw std::runtime_error("Invalid ADPCM state");}
    for(size_t i=0;i<count;i++){
        float value[2];for(int c=0;c<2;c++) {int nibble=(data[8+i]>>(c*4))&15,step=steps[index[c]],diff=step/8;
            if(nibble&4)diff+=step;
            if(nibble&2)diff+=step/2;
            if(nibble&1)diff+=step/4;
            predictor[c]=std::max(-32768,std::min(32767,predictor[c]+((nibble&8)?-diff:diff)));
            index[c]=std::max(0,std::min(88,index[c]+adjust[nibble&7]));value[c]=predictor[c]/32768.f;}
        frames[i]={value[0],value[1]};
    }
}
}
