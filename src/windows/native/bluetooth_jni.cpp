#define NOMINMAX
#include <winsock2.h>
#include <ws2bth.h>
#include <windows.h>
#include <bluetoothapis.h>
#include <jni.h>
#include <mutex>
#include <map>
#include <memory>
#include <atomic>
#include <stdexcept>
#include <vector>
#include "windows/a2dp_native.hpp"

namespace {
GUID service{0x91b86d5a,0xb742,0x40cc,{0x96,0xa0,0xc9,0x70,0x66,0x7b,0xfd,0xa4}};
std::once_flag startup;
void initialize(){std::call_once(startup,[]{WSADATA w{};if(WSAStartup(MAKEWORD(2,2),&w))throw std::runtime_error("Winsock initialization failed");});}
std::wstring wide(JNIEnv* e,jstring s){if(!s)return L"";const jchar* p=e->GetStringChars(s,nullptr);std::wstring v(reinterpret_cast<const wchar_t*>(p),e->GetStringLength(s));e->ReleaseStringChars(s,p);return v;}
jstring str(JNIEnv* e,const std::wstring& s){return e->NewString(reinterpret_cast<const jchar*>(s.data()),jsize(s.size()));}
void fail(JNIEnv* e,const std::exception& x){e->ThrowNew(e->FindClass("java/io/IOException"),x.what());}
void check(bool ok,const char* text){if(!ok)throw std::runtime_error(std::string(text)+" (Windows="+std::to_string(WSAGetLastError())+")");}
std::wstring address(ULONGLONG value){wchar_t s[32];swprintf(s,32,L"%02X:%02X:%02X:%02X:%02X:%02X",unsigned(value>>40&255),unsigned(value>>32&255),unsigned(value>>24&255),unsigned(value>>16&255),unsigned(value>>8&255),unsigned(value&255));return s;}
struct Socket {
 std::atomic<SOCKET> value{INVALID_SOCKET};SOCKADDR_BTH local{};bool advertised=false;
 void advertise(WSAESETSERVICEOP operation){CSADDR_INFO a{};a.LocalAddr.lpSockaddr=reinterpret_cast<sockaddr*>(&local);a.LocalAddr.iSockaddrLength=sizeof(local);a.iSocketType=SOCK_STREAM;a.iProtocol=BTHPROTO_RFCOMM;WSAQUERYSETW q{};q.dwSize=sizeof(q);q.lpszServiceInstanceName=const_cast<wchar_t*>(L"AudioHub AHB2 encrypted duplex room");q.lpServiceClassId=&service;q.dwNameSpace=NS_BTH;q.dwNumberOfCsAddrs=1;q.lpcsaBuffer=&a;check(WSASetServiceW(&q,operation,0)==0,"RFCOMM SDP registration failed");}
 ~Socket(){close();}
 void close(){SOCKET s=value.exchange(INVALID_SOCKET);if(s!=INVALID_SOCKET){shutdown(s,SD_BOTH);closesocket(s);}if(advertised){advertised=false;try{advertise(RNRSERVICE_DELETE);}catch(...){}}}
};
std::mutex gate;std::map<jlong,std::shared_ptr<Socket>> handles;std::atomic<jlong> sequence{1};
jlong keep(std::shared_ptr<Socket> s){std::lock_guard<std::mutex> l(gate);jlong h=sequence++;handles[h]=s;return h;}
std::shared_ptr<Socket> get(jlong h){std::lock_guard<std::mutex> l(gate);auto i=handles.find(h);if(i==handles.end())throw std::runtime_error("Bluetooth socket closed");return i->second;}
void secure(SOCKET s){BOOL yes=TRUE;check(setsockopt(s,SOL_RFCOMM,SO_BTH_AUTHENTICATE,reinterpret_cast<char*>(&yes),sizeof(yes))==0,"Bluetooth authentication unavailable");check(setsockopt(s,SOL_RFCOMM,SO_BTH_ENCRYPT,reinterpret_cast<char*>(&yes),sizeof(yes))==0,"Bluetooth encryption unavailable");DWORD ms=8000;setsockopt(s,SOL_SOCKET,SO_SNDTIMEO,reinterpret_cast<char*>(&ms),sizeof(ms));}
audiohub::NativeA2dp a2dp;
jobjectArray strings(JNIEnv* e,const std::vector<std::wstring>& p){auto a=e->NewObjectArray(jsize(p.size()),e->FindClass("java/lang/String"),nullptr);for(size_t i=0;i<p.size();i++){auto s=str(e,p[i]);e->SetObjectArrayElement(a,jsize(i),s);e->DeleteLocalRef(s);}return a;}
}
extern "C" {
JNIEXPORT jlong JNICALL Java_org_audiohub_desktop_WindowsBluetooth_listenNative(JNIEnv* e,jclass){try{
 initialize();BLUETOOTH_FIND_RADIO_PARAMS p{sizeof(p)};HANDLE radio=nullptr;auto find=BluetoothFindFirstRadio(&p,&radio);if(!find)throw std::runtime_error("No enabled Bluetooth radio; enable Bluetooth in Windows Settings");BLUETOOTH_RADIO_INFO info{};info.dwSize=sizeof(info);DWORD result=BluetoothGetRadioInfo(radio,&info);CloseHandle(radio);BluetoothFindRadioClose(find);if(result)throw std::runtime_error("Bluetooth radio information failed");
 auto s=std::make_shared<Socket>();s->value=socket(AF_BTH,SOCK_STREAM,BTHPROTO_RFCOMM);check(s->value!=INVALID_SOCKET,"RFCOMM socket failed");secure(s->value);s->local.addressFamily=AF_BTH;s->local.btAddr=info.address.ullLong;s->local.port=BT_PORT_ANY;check(bind(s->value,reinterpret_cast<sockaddr*>(&s->local),sizeof(s->local))==0,"RFCOMM bind failed");int n=sizeof(s->local);check(getsockname(s->value,reinterpret_cast<sockaddr*>(&s->local),&n)==0,"RFCOMM local address failed");check(listen(s->value,10)==0,"RFCOMM listen failed");s->advertise(RNRSERVICE_REGISTER);s->advertised=true;return keep(s);
 }catch(const std::exception& x){fail(e,x);return 0;}}
JNIEXPORT jstring JNICALL Java_org_audiohub_desktop_WindowsBluetooth_addressNative(JNIEnv* e,jclass,jlong h){try{return str(e,address(get(h)->local.btAddr));}catch(const std::exception& x){fail(e,x);return nullptr;}}
JNIEXPORT jlong JNICALL Java_org_audiohub_desktop_WindowsBluetooth_acceptNative(JNIEnv* e,jclass,jlong h){try{auto server=get(h);auto s=std::make_shared<Socket>();s->value=accept(server->value,nullptr,nullptr);check(s->value!=INVALID_SOCKET,"RFCOMM accept failed");secure(s->value);return keep(s);}catch(const std::exception& x){fail(e,x);return 0;}}
JNIEXPORT jlong JNICALL Java_org_audiohub_desktop_WindowsBluetooth_connectNative(JNIEnv* e,jclass,jstring addr){try{
 initialize();auto text=wide(e,addr);ULONGLONG mac=0;unsigned a[6];if(swscanf(text.c_str(),L"%2x:%2x:%2x:%2x:%2x:%2x",a,a+1,a+2,a+3,a+4,a+5)!=6)throw std::runtime_error("Invalid paired device address");for(auto b:a)mac=(mac<<8)|b;
 auto s=std::make_shared<Socket>();s->value=socket(AF_BTH,SOCK_STREAM,BTHPROTO_RFCOMM);check(s->value!=INVALID_SOCKET,"RFCOMM socket failed");secure(s->value);SOCKADDR_BTH target{};target.addressFamily=AF_BTH;target.btAddr=mac;target.serviceClassId=service;
 u_long nonblocking=1;ioctlsocket(s->value,FIONBIO,&nonblocking);int result=connect(s->value,reinterpret_cast<sockaddr*>(&target),sizeof(target));if(result==SOCKET_ERROR){int err=WSAGetLastError();if(err!=WSAEWOULDBLOCK&&err!=WSAEINPROGRESS)throw std::runtime_error("RFCOMM connect/SDP failed; pair devices first (Windows="+std::to_string(err)+")");fd_set write,error;FD_ZERO(&write);FD_ZERO(&error);FD_SET(s->value,&write);FD_SET(s->value,&error);timeval timeout{12,0};check(select(0,nullptr,&write,&error,&timeout)>0,"RFCOMM connection timed out (12s)");int code=0,n=sizeof(code);getsockopt(s->value,SOL_SOCKET,SO_ERROR,reinterpret_cast<char*>(&code),&n);if(code)throw std::runtime_error("RFCOMM connection failed (Windows="+std::to_string(code)+")");}nonblocking=0;ioctlsocket(s->value,FIONBIO,&nonblocking);return keep(s);
 }catch(const std::exception& x){fail(e,x);return 0;}}
JNIEXPORT jint JNICALL Java_org_audiohub_desktop_WindowsBluetooth_readNative(JNIEnv* e,jclass,jlong h,jbyteArray b,jint o,jint n){try{auto s=get(h);std::vector<char> bytes(size_t(n),0);int count=recv(s->value,bytes.data(),n,0);check(count!=SOCKET_ERROR,"RFCOMM read failed/disconnected");if(count==0)return -1;e->SetByteArrayRegion(b,o,count,reinterpret_cast<jbyte*>(bytes.data()));return count;}catch(const std::exception& x){fail(e,x);return -1;}}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_WindowsBluetooth_writeNative(JNIEnv* e,jclass,jlong h,jbyteArray b,jint o,jint n){try{auto s=get(h);std::vector<char> bytes(size_t(n),0);e->GetByteArrayRegion(b,o,n,reinterpret_cast<jbyte*>(bytes.data()));int done=0;while(done<n){int count=send(s->value,bytes.data()+done,n-done,0);check(count>0,"RFCOMM write failed/congested");done+=count;}}catch(const std::exception& x){fail(e,x);}}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_WindowsBluetooth_closeNative(JNIEnv*,jclass,jlong h){std::shared_ptr<Socket> s;{std::lock_guard<std::mutex> l(gate);auto i=handles.find(h);if(i==handles.end())return;s=i->second;handles.erase(i);}s->close();}
JNIEXPORT jobjectArray JNICALL Java_org_audiohub_desktop_WindowsBluetooth_pairedNative(JNIEnv* e,jclass){try{initialize();BLUETOOTH_DEVICE_SEARCH_PARAMS p{};p.dwSize=sizeof(p);p.fReturnAuthenticated=TRUE;p.fReturnRemembered=TRUE;p.fReturnConnected=TRUE;BLUETOOTH_DEVICE_INFO d{};d.dwSize=sizeof(d);std::vector<std::wstring> pairs;auto f=BluetoothFindFirstDevice(&p,&d);if(f){do{if(d.fAuthenticated){pairs.push_back(address(d.Address.ullLong));pairs.emplace_back(d.szName);}d.dwSize=sizeof(d);}while(pairs.size()<256&&BluetoothFindNextDevice(f,&d));BluetoothFindDeviceClose(f);}return strings(e,pairs);}catch(const std::exception& x){fail(e,x);return nullptr;}}
JNIEXPORT void JNICALL Java_org_audiohub_desktop_WindowsBluetooth_a2dpNative(JNIEnv* e,jclass,jstring action,jstring id){try{auto a=wide(e,action);if(a==L"refresh"){if(a2dp.connected())throw std::runtime_error("Disconnect A2DP before refresh");a2dp.refresh();}else if(a==L"connect")a2dp.connect({wide(e,id),L"已配对设备"});else if(a==L"disconnect")a2dp.stop();else throw std::runtime_error("Unknown A2DP action");}catch(const std::exception& x){fail(e,x);}}
JNIEXPORT jobjectArray JNICALL Java_org_audiohub_desktop_WindowsBluetooth_a2dpStateNative(JNIEnv* e,jclass){std::vector<std::wstring> result{a2dp.status(),a2dp.connected()?L"true":L"false"};for(const auto& d:a2dp.devices()){result.push_back(d.id);result.push_back(d.name);}return strings(e,result);}
}
