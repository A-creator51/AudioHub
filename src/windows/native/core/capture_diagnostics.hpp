#pragma once
#include <cstdint>
namespace audiohub {
// Audio-frame timeline, independent of polling jitter and five-second windows.
// SILENT means a Windows endpoint flag, not evidence of radio packet loss.
struct SilenceSegment {uint64_t start=0,frames=0;bool ended=false;};
class CaptureDiagnostics {
    uint64_t position_=0,start_=0,run_=0;
public:
    uint64_t packets=0,frames=0,silent=0,segments=0,longest=0;
    SilenceSegment packet(unsigned count,bool flagged){
        ++packets;frames+=count;SilenceSegment result{};
        if(flagged){silent+=count;if(!run_){start_=position_;++segments;}run_+=count;}
        else if(run_){result={start_,run_,true};run_=0;}
        if(run_>longest)longest=run_;
        position_+=count;return result;
    }
    SilenceSegment pending()const{return {start_,run_,false};}
    void window(){packets=frames=silent=segments=0;longest=run_;}
};
}
