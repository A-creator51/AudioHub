#define NOMINMAX
#include <windows.h>
#include <jni.h>
#include "windows/capture.hpp"
#include "windows/settings.hpp"
#include "windows/log.hpp"
#include <mutex>
#include <sstream>

using namespace audiohub;
namespace {
std::mutex gate;
auto captured=std::make_shared<LiveInput>();
auto incoming=std::make_shared<LiveInput>();
MixEngine mixer;
std::unique_ptr<LoopbackInput> capture;
std::unique_ptr<OutputManager> output;
bool sending=false;
std::wstring current_source;
std::vector<std::wstring> current_outputs;
std::wstring wide(JNIEnv* env,jstring s){if(!s)return L"";const jchar* p=env->GetStringChars(s,nullptr);std::wstring result(reinterpret_cast<const wchar_t*>(p),env->GetStringLength(s));env->ReleaseStringChars(s,p);return result;}
jstring string(JNIEnv* env,const std::wstring& s){return env->NewString(reinterpret_cast<const jchar*>(s.data()),jsize(s.size()));}
void fail(JNIEnv* env,const char* text){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),text);}
void stop(){if(capture)capture->stop();if(output)output->stop();captured->clear();incoming->clear();sending=false;current_source.clear();current_outputs.clear();}
void setup(){if(!capture){mixer.add_live(incoming);mixer.control(1,1,false);capture=std::make_unique<LoopbackInput>(captured);output=std::make_unique<OutputManager>(mixer);}}
}
extern "C" {
JNIEXPORT jobjectArray JNICALL Java_org_audiohub_desktop_NativeAudio_devices(JNIEnv* env,jclass){std::lock_guard<std::mutex> lock(gate);try{
    auto devices=OutputManager::enumerate();auto result=env->NewObjectArray(jsize(devices.size()*2),env->FindClass("java/lang/String"),nullptr);
    for(size_t i=0;i<devices.size();++i){auto a=string(env,devices[i].id),b=string(env,devices[i].name);env->SetObjectArrayElement(result,jsize(i*2),a);env->SetObjectArrayElement(result,jsize(i*2+1),b);env->DeleteLocalRef(a);env->DeleteLocalRef(b);}return result;
}catch(const std::exception& e){fail(env,e.what());return nullptr;}}
JNIEXPORT jobjectArray JNICALL Java_org_audiohub_desktop_NativeAudio_inputDevices(JNIEnv* env,jclass){std::lock_guard<std::mutex> lock(gate);try{
    auto devices=OutputManager::a2dp_sources();auto result=env->NewObjectArray(jsize(devices.size()*2),env->FindClass("java/lang/String"),nullptr);
    for(size_t i=0;i<devices.size();++i){auto a=string(env,devices[i].id),b=string(env,devices[i].name);env->SetObjectArrayElement(result,jsize(i*2),a);env->SetObjectArrayElement(result,jsize(i*2+1),b);env->DeleteLocalRef(a);env->DeleteLocalRef(b);}return result;
}catch(const std::exception& e){fail(env,e.what());return nullptr;}}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_start(JNIEnv* env,jclass,jboolean send,jstring source,jobjectArray outputs){std::lock_guard<std::mutex> lock(gate);try{
    setup();auto devices=OutputManager::enumerate();auto source_id=wide(env,source);Device source_device;bool found=false;std::vector<Device> selected;std::vector<std::wstring> selected_ids;
    for(const auto& d:OutputManager::a2dp_sources())if(d.id==source_id){source_device=d;found=true;}
    for(jsize i=0;i<env->GetArrayLength(outputs);++i){auto s=static_cast<jstring>(env->GetObjectArrayElement(outputs,i));auto key=wide(env,s);env->DeleteLocalRef(s);bool exists=false;for(const auto& d:devices)if(d.id==key){if(send&&key==source_id)throw std::runtime_error("Feedback protection: capture endpoint cannot be an output");selected.push_back(d);selected_ids.push_back(key);exists=true;}if(!exists)throw std::runtime_error("Output device removed; refresh devices");}
    if(send&&!found)throw std::runtime_error("Capture source removed; refresh devices");
    if(selected_ids!=current_outputs){output->stop();incoming->clear();incoming->prebuffer(AudioSettings::instance().input_ms);if(!selected.empty())output->start(selected);current_outputs=selected_ids;}
    if(bool(send)!=sending||(send&&source_id!=current_source)){capture->stop();captured->prebuffer(AudioSettings::instance().input_ms);if(send)capture->start(source_device);sending=send;current_source=source_id;}
}catch(const std::exception& e){stop();fail(env,e.what());}}
JNIEXPORT jbyteArray JNICALL Java_org_audiohub_desktop_NativeAudio_capture(JNIEnv* env,jclass){std::lock_guard<std::mutex> lock(gate);if(!capture||!capture->running())return nullptr;Frame frames[block_frames];captured->read(frames,block_frames);jbyte pcm[1920];for(size_t i=0;i<block_frames;++i){float samples[2]{frames[i].left,frames[i].right};for(int c=0;c<2;++c){int x=int(std::max(-1.f,std::min(1.f,samples[c]))*32767);pcm[4*i+2*c]=jbyte(x&255);pcm[4*i+2*c+1]=jbyte((x>>8)&255);}}auto result=env->NewByteArray(1920);env->SetByteArrayRegion(result,0,1920,pcm);return result;}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_play(JNIEnv* env,jclass,jbyteArray data){std::lock_guard<std::mutex> lock(gate);if(env->GetArrayLength(data)!=1920)return;jbyte b[1920];env->GetByteArrayRegion(data,0,1920,b);Frame frames[480];for(int i=0;i<480;++i){auto value=[&](int j){return float(int16_t((uint8_t(b[j]))|(int(uint8_t(b[j+1]))<<8)))/32768.f;};frames[i]={value(i*4),value(i*4+2)};}incoming->write(frames,480);}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_gain(JNIEnv*,jclass,jint index,jfloat value){std::lock_guard<std::mutex> lock(gate);if(output)output->set_gain(size_t(index),value);}
JNIEXPORT jstring JNICALL Java_org_audiohub_desktop_NativeAudio_status(JNIEnv* env,jclass){std::lock_guard<std::mutex> lock(gate);std::wstring state=capture?capture->status():L"未开始音频";if(output)for(auto& s:output->status())state+=L"\n"+s.name+L": "+s.state+L" underruns="+std::to_wstring(s.underflows)+L" drops="+std::to_wstring(s.overruns);auto in=incoming->stats(),cap=captured->stats();state+=L"\n混音到播放队列：frames="+std::to_wstring(in.queued)+L" gaps="+std::to_wstring(in.gaps)+L" drops="+std::to_wstring(in.drops)+L"；回采队列：frames="+std::to_wstring(cap.queued)+L" gaps="+std::to_wstring(cap.gaps)+L" drops="+std::to_wstring(cap.drops);return string(env,state);}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_stop(JNIEnv*,jclass){std::lock_guard<std::mutex> lock(gate);stop();}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_configure(JNIEnv*,jclass,jint out,jint reserve,jint in,jboolean mmcss){std::lock_guard<std::mutex> lock(gate);auto& s=AudioSettings::instance();s.output_ms=out;s.reserve_ms=reserve;s.input_ms=in;s.mmcss=mmcss;s.sanitize();}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_diagnostics(JNIEnv*,jclass,jboolean detail,jint level){AudioSettings::instance().capture_detail=detail;AppLog::instance().level(static_cast<LogLevel>(std::max(0,std::min(4,int(level)))));}
JNIEXPORT jstring JNICALL Java_org_audiohub_desktop_NativeAudio_logs(JNIEnv* env,jclass){return string(env,AppLog::instance().snapshot());}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_clearLogs(JNIEnv*,jclass){AppLog::instance().clear();}
JNIEXPORT jboolean JNICALL Java_org_audiohub_desktop_NativeAudio_autostart(JNIEnv*,jclass){HKEY key=nullptr;if(RegOpenKeyExW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",0,KEY_QUERY_VALUE,&key)!=ERROR_SUCCESS)return false;DWORD size=0;auto result=RegQueryValueExW(key,L"AudioHub-v0.9",nullptr,nullptr,nullptr,&size);RegCloseKey(key);return result==ERROR_SUCCESS&&size>2;}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_NativeAudio_setAutostart(JNIEnv* env,jclass,jboolean enabled){HKEY key=nullptr;auto result=RegCreateKeyExW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Run",0,nullptr,0,KEY_SET_VALUE,nullptr,&key,nullptr);if(result!=ERROR_SUCCESS){fail(env,"Cannot access current-user startup setting");return;}
    if(enabled){wchar_t path[32768]{};DWORD length=GetModuleFileNameW(nullptr,path,32768);if(!length||length>=32768){RegCloseKey(key);fail(env,"Cannot resolve launcher path");return;}std::wstring value=L"\""+std::wstring(path)+L"\"";result=RegSetValueExW(key,L"AudioHub-v0.9",0,REG_SZ,reinterpret_cast<const BYTE*>(value.c_str()),DWORD((value.size()+1)*sizeof(wchar_t)));}
    else{result=RegDeleteValueW(key,L"AudioHub-v0.9");if(result==ERROR_FILE_NOT_FOUND)result=ERROR_SUCCESS;}RegCloseKey(key);if(result!=ERROR_SUCCESS)fail(env,"Cannot save current-user startup setting");}
}
