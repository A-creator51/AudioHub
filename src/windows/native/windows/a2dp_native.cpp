#define NOMINMAX
#define WINVER 0x0A00
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <roapi.h>
#include <winstring.h>
#include <inspectable.h>
#include <chrono>
#include <cstdio>
#include <stdexcept>
#include "a2dp_native.hpp"
#include "log.hpp"
// Public WinRT ABI, same interfaces exercised by the native observation probe.
// External linkage is required for COM virtual calls with older GCC.
namespace audiohub_a2dp_abi {
struct AsyncInfo:IInspectable {
    virtual HRESULT STDMETHODCALLTYPE id(UINT32*)=0;
    virtual HRESULT STDMETHODCALLTYPE status(INT32*)=0;
    virtual HRESULT STDMETHODCALLTYPE error(HRESULT*)=0;
    virtual HRESULT STDMETHODCALLTYPE cancel()=0;
    virtual HRESULT STDMETHODCALLTYPE close()=0;
};
struct AsyncObject:IInspectable {
    virtual HRESULT STDMETHODCALLTYPE put_completed(IUnknown*)=0;
    virtual HRESULT STDMETHODCALLTYPE get_completed(IUnknown**)=0;
    virtual HRESULT STDMETHODCALLTYPE results(IInspectable**)=0;
};
struct AsyncAction:IInspectable {
    virtual HRESULT STDMETHODCALLTYPE put_completed(IUnknown*)=0;
    virtual HRESULT STDMETHODCALLTYPE get_completed(IUnknown**)=0;
    virtual HRESULT STDMETHODCALLTYPE results()=0;
};
struct DeviceInfo:IInspectable {virtual HRESULT STDMETHODCALLTYPE id(HSTRING*)=0;virtual HRESULT STDMETHODCALLTYPE name(HSTRING*)=0;};
struct DeviceView:IInspectable {virtual HRESULT STDMETHODCALLTYPE at(UINT32,DeviceInfo**)=0;virtual HRESULT STDMETHODCALLTYPE size(UINT32*)=0;};
struct DeviceStatics:IInspectable {
    virtual HRESULT STDMETHODCALLTYPE create_id(HSTRING,IInspectable**)=0;
    virtual HRESULT STDMETHODCALLTYPE create_props(HSTRING,IInspectable*,IInspectable**)=0;
    virtual HRESULT STDMETHODCALLTYPE find_all(IInspectable**)=0;
    virtual HRESULT STDMETHODCALLTYPE find_class(INT32,IInspectable**)=0;
    virtual HRESULT STDMETHODCALLTYPE find_selector(HSTRING,AsyncObject**)=0;
};
struct OpenResult:IInspectable {virtual HRESULT STDMETHODCALLTYPE status(INT32*)=0;virtual HRESULT STDMETHODCALLTYPE extended_error(HRESULT*)=0;};
struct Connection:IInspectable {
    virtual HRESULT STDMETHODCALLTYPE start()=0;
    virtual HRESULT STDMETHODCALLTYPE start_async(AsyncAction**)=0;
    virtual HRESULT STDMETHODCALLTYPE device_id(HSTRING*)=0;
    virtual HRESULT STDMETHODCALLTYPE state(INT32*)=0;
    virtual HRESULT STDMETHODCALLTYPE open(OpenResult**)=0;
    virtual HRESULT STDMETHODCALLTYPE open_async(AsyncObject**)=0;
    virtual HRESULT STDMETHODCALLTYPE add_changed(IUnknown*,INT64*)=0;
    virtual HRESULT STDMETHODCALLTYPE remove_changed(INT64)=0;
};
struct ConnectionStatics:IInspectable {virtual HRESULT STDMETHODCALLTYPE selector(HSTRING*)=0;virtual HRESULT STDMETHODCALLTYPE create(HSTRING,Connection**)=0;};
struct Closable:IInspectable {virtual HRESULT STDMETHODCALLTYPE close()=0;};
const IID async_iid={0x00000036,0,0,{0xc0,0,0,0,0,0,0,0x46}};
const IID devices_iid={0xc17f100e,0x3a46,0x4a78,{0x80,0x13,0x76,0x9d,0xc9,0xb9,0x73,0x90}};
const IID factory_iid={0xe60963a2,0x69e6,0x5ffc,{0x9e,0x13,0x82,0x4a,0x85,0x21,0x3d,0xaf}};
const IID close_iid={0x30d5a829,0x7fa4,0x4026,{0x83,0xbb,0xd7,0x5b,0xae,0x4e,0xa9,0x9e}};
void check(HRESULT hr,const char* operation){if(FAILED(hr)){char text[180];std::snprintf(text,sizeof(text),"%s HRESULT=0x%08lX",operation,static_cast<unsigned long>(hr));throw std::runtime_error(text);}}
template<class T>struct Com {T* p=nullptr;Com()=default;Com(const Com&)=delete;~Com(){if(p)p->Release();}T* operator->(){return p;}T** out(){return &p;}};
struct String {
    HSTRING value=nullptr;
    String()=default;String(const String&)=delete;
    explicit String(const std::wstring& text){check(WindowsCreateString(text.data(),UINT32(text.size()),&value),"WinRT string");}
    ~String(){if(value)WindowsDeleteString(value);}
    std::wstring text(){UINT32 n=0;auto p=WindowsGetStringRawBuffer(value,&n);return n?std::wstring(p,n):std::wstring();}
};
struct Apartment {Apartment(){check(RoInitialize(RO_INIT_MULTITHREADED),"WinRT init");}~Apartment(){RoUninitialize();}};
void wait(IInspectable* op,const std::atomic<bool>& cancel,unsigned timeout=20000){
    Com<AsyncInfo> info;check(op->QueryInterface(async_iid,reinterpret_cast<void**>(info.out())),"Async interface");
    auto end=std::chrono::steady_clock::now()+std::chrono::milliseconds(timeout);
    for(;;){
        if(cancel){info->cancel();throw std::runtime_error("A2DP operation cancelled");}
        INT32 status=0;check(info->status(&status),"Async state");if(status==1)return;
        if(status==2)throw std::runtime_error("A2DP operation cancelled");
        if(status==3){HRESULT hr=E_FAIL;check(info->error(&hr),"Async error");check(hr,"A2DP async operation");throw std::runtime_error("Async operation failed");}
        if(std::chrono::steady_clock::now()>=end){info->cancel();throw std::runtime_error("A2DP timeout");}
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
}
struct Close {Com<Closable> p;explicit Close(Connection* c){check(c->QueryInterface(close_iid,reinterpret_cast<void**>(p.out())),"Connection close interface");}~Close(){if(p.p)p->close();}};
}
namespace audiohub {
void NativeA2dp::state(std::wstring text){{std::lock_guard<std::mutex> lock(mutex_);status_=text;}log(LogLevel::Info,L"A2DP "+text);}
std::wstring NativeA2dp::status(){std::lock_guard<std::mutex> lock(mutex_);return status_;}
std::vector<A2dpDevice> NativeA2dp::devices(){std::lock_guard<std::mutex> lock(mutex_);return devices_;}
void NativeA2dp::stop(){cancel_=true;if(worker_.joinable())worker_.join();connected_=false;state(L"连接已关闭（不改变 Windows 输出）");}
void NativeA2dp::refresh(){stop();cancel_=false;state(L"正在枚举已配对手机…");worker_=std::thread([this]{run(true,{});});}
void NativeA2dp::connect(A2dpDevice device){stop();if(device.id.empty())throw std::invalid_argument("Select an A2DP phone");cancel_=false;state(L"正在连接 "+device.name);worker_=std::thread([this,device]{run(false,device);});}
void NativeA2dp::run(bool list,A2dpDevice device){
    struct Reset {std::atomic<bool>& value;~Reset(){value=false;}} reset{connected_};
    using namespace audiohub_a2dp_abi;
    try{
        Apartment apartment;String klass(L"Windows.Media.Audio.AudioPlaybackConnection");Com<ConnectionStatics> factory;
        check(RoGetActivationFactory(klass.value,factory_iid,reinterpret_cast<void**>(factory.out())),"A2DP factory");
        if(list){
            String selector;check(factory->selector(&selector.value),"A2DP selector");
            String name(L"Windows.Devices.Enumeration.DeviceInformation");Com<DeviceStatics> df;
            check(RoGetActivationFactory(name.value,devices_iid,reinterpret_cast<void**>(df.out())),"Enumeration factory");
            Com<AsyncObject> op;check(df->find_selector(selector.value,op.out()),"Enumerate phones");wait(op.p,cancel_,10000);
            Com<DeviceView> view;check(op->results(reinterpret_cast<IInspectable**>(view.out())),"Enumeration result");
            UINT32 count=0;check(view->size(&count),"Phone count");if(count>128)throw std::runtime_error("Unexpected phone count");
            std::vector<A2dpDevice> result;
            for(UINT32 i=0;i<count;++i){Com<DeviceInfo> item;check(view->at(i,item.out()),"Phone item");String id,name;check(item->id(&id.value),"Phone ID");check(item->name(&name.value),"Phone name");result.push_back({id.text(),name.text()});}
            {std::lock_guard<std::mutex> lock(mutex_);devices_=std::move(result);}
            state(L"枚举完成："+std::to_wstring(count)+L" 台；请选择手机后连接");return;
        }
        String id(device.id);Com<Connection> connection;check(factory->create(id.value,connection.out()),"Create A2DP");
        if(!connection.p)throw std::runtime_error("Windows returned no A2DP connection");
        Close close(connection.p);Com<AsyncAction> start;check(connection->start_async(start.out()),"Enable A2DP");wait(start.p,cancel_);check(start->results(),"Enable result");
        Com<AsyncObject> op;check(connection->open_async(op.out()),"Open A2DP");wait(op.p,cancel_);
        Com<OpenResult> result;check(op->results(reinterpret_cast<IInspectable**>(result.out())),"Open result");
        INT32 status=3;HRESULT extended=E_FAIL;check(result->status(&status),"Open status");check(result->extended_error(&extended),"Open extended error");
        if(status!=0){check(extended,"Open extended failure");throw std::runtime_error("A2DP open status="+std::to_string(status));}
        connected_=true;state(L"已连接 "+device.name+L"；Windows 直放，需另启动下方回采混音");
        auto connected_at=std::chrono::steady_clock::now(),health_at=connected_at;
        log(LogLevel::Info,L"BLUETOOTH-HEALTH state=connected transport=Windows-A2DP codec=unavailable bitrate=unavailable RSSI=unavailable radioLoss=unavailable; connected is NOT proof of continuous sound");
        while(!cancel_){INT32 s=0;check(connection->state(&s),"A2DP health");if(s!=1){log(LogLevel::Warn,L"BLUETOOTH-HEALTH stateChanged rawState="+std::to_wstring(s));throw std::runtime_error("A2DP connection lost");}
            auto now=std::chrono::steady_clock::now();if(now-health_at>=std::chrono::seconds(5)){log(LogLevel::Info,L"BLUETOOTH-HEALTH state=connected uptimeMs="+std::to_wstring(std::chrono::duration_cast<std::chrono::milliseconds>(now-connected_at).count())+L" codec=unavailable RSSI=unavailable radioLoss=unavailable; WinRT connection status only, works without EXE mixing");health_at=now;}
            std::this_thread::sleep_for(std::chrono::milliseconds(100));}
    }catch(const std::exception& e){if(!cancel_){state(L"失败："+log_wide(e.what()));log(LogLevel::Error,L"A2DP "+log_wide(e.what()));}}
}
}
